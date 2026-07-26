package magefree.network.ktor

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import magefree.model.ConnectionError
import magefree.model.ConnectionState
import magefree.model.Credentials
import magefree.model.ServerTarget
import magefree.model.SessionEvent
import magefree.network.BridgeClient
import magefree.network.mapper.SessionMapper
import magefree.network.mapper.SessionMapper.handshakeResult
import magefree.protocol.ClientHello
import magefree.protocol.ClientMessage
import magefree.protocol.Login
import magefree.protocol.ProtocolJson
import magefree.protocol.ProtocolVersion
import magefree.protocol.ServerHello
import magefree.protocol.ServerMessage

/**
 * A reconnection-aware [BridgeClient] over a Ktor WebSocket speaking the `:protocol` contract.
 *
 * On [connect] it opens `ws(s)://host:port/<major>/session`, performs the [ClientHello]/[ServerHello]
 * handshake (rejecting a major mismatch as [SessionEvent.VersionUnsupported]), sends [Login], then
 * relays each server frame through [SessionMapper]. An unexpected socket drop triggers a bounded
 * reconnect loop ([ReconnectPolicy]); authentication/version/disconnect outcomes are terminal.
 *
 * All wire (`:protocol`) types are confined to this class and the mapper — callers see only
 * `:core:model` [SessionEvent]s.
 */
class KtorBridgeClient(
    private val httpClientFactory: () -> HttpClient = ::defaultHttpClient,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val reconnectPolicy: ReconnectPolicy = ReconnectPolicy(),
) : BridgeClient {
    private val _connectionState = MutableStateFlow(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    @Volatile
    private var activeSession: DefaultClientWebSocketSession? = null

    @Volatile
    private var disconnectRequested = false

    /** How a single socket session ended, deciding whether the [connect] loop reconnects. */
    private enum class Outcome { TERMINAL, CLEAN_CLOSE, RETRY }

    override fun connect(
        server: ServerTarget,
        credentials: Credentials,
    ): Flow<SessionEvent> =
        channelFlow {
            disconnectRequested = false
            val client = httpClientFactory()
            try {
                var attempt = 0
                while (isActive && !disconnectRequested) {
                    send(if (attempt == 0) SessionEvent.Connecting else SessionEvent.Reconnecting)
                    val outcome = runSession(client, server, credentials) { send(it) }
                    if (outcome == Outcome.TERMINAL) break
                    if (outcome == Outcome.CLEAN_CLOSE) {
                        send(SessionEvent.Disconnected())
                        break
                    }
                    // Outcome.RETRY: the socket dropped unexpectedly.
                    attempt++
                    if (disconnectRequested) break
                    if (attempt > reconnectPolicy.maxAttempts) {
                        send(SessionEvent.Disconnected("reconnect attempts exhausted"))
                        break
                    }
                    delay(reconnectPolicy.delayMillis)
                }
            } finally {
                activeSession = null
                client.close()
            }
        }.onEach { _connectionState.value = it.connectionState }
            .flowOn(ioDispatcher)

    override suspend fun disconnect() {
        disconnectRequested = true
        activeSession?.close(CloseReason(CloseReason.Codes.NORMAL, "client disconnect"))
        activeSession = null
        _connectionState.value = ConnectionState.Disconnected
    }

    private suspend fun runSession(
        client: HttpClient,
        server: ServerTarget,
        credentials: Credentials,
        emit: suspend (SessionEvent) -> Unit,
    ): Outcome {
        val session =
            try {
                client.webSocketSession(urlString = server.wsUrl())
            } catch (e: Exception) {
                emit(SessionEvent.Error(ConnectionError.Transport("failed to open websocket: ${e.message}", e)))
                return Outcome.RETRY
            }
        activeSession = session
        return try {
            // Handshake: announce our version, expect the bridge's hello.
            session.sendMessage(ClientHello(ProtocolVersion.MAJOR, ProtocolVersion.MINOR))
            when (val hello = session.receiveMessage()) {
                is ServerHello -> {
                    handshakeResult(hello)?.let {
                        emit(it)
                        return Outcome.TERMINAL
                    }
                    session.relaySession(server, credentials, hello.bridgeVersion, emit)
                }
                null -> Outcome.RETRY
                else -> {
                    // The bridge answered the hello with something else (e.g. a version ProtocolError).
                    val event = SessionMapper.toSessionEvent(hello, server, credentials.username, null)
                    if (event != null) {
                        emit(event)
                        Outcome.TERMINAL
                    } else {
                        Outcome.RETRY
                    }
                }
            }
        } catch (e: Exception) {
            emit(SessionEvent.Error(ConnectionError.Transport("session error: ${e.message}", e)))
            Outcome.RETRY
        } finally {
            session.close()
            if (activeSession === session) activeSession = null
        }
    }

    private suspend fun DefaultClientWebSocketSession.relaySession(
        server: ServerTarget,
        credentials: Credentials,
        bridgeVersion: String?,
        emit: suspend (SessionEvent) -> Unit,
    ): Outcome {
        sendMessage(Login(credentials.username, credentials.password))
        while (true) {
            val message = receiveMessage() ?: break
            val event =
                SessionMapper.toSessionEvent(message, server, credentials.username, bridgeVersion)
                    ?: continue
            emit(event)
            when (event) {
                is SessionEvent.AuthFailed,
                is SessionEvent.VersionUnsupported,
                is SessionEvent.Disconnected,
                is SessionEvent.Error,
                -> return Outcome.TERMINAL
                else -> Unit // Connecting / Connected / Reconnecting: keep relaying.
            }
        }
        // Incoming closed without a terminal status.
        return if (disconnectRequested) Outcome.CLEAN_CLOSE else Outcome.RETRY
    }

    private suspend fun DefaultClientWebSocketSession.sendMessage(message: ClientMessage) {
        send(Frame.Text(ProtocolJson.json.encodeToString<ClientMessage>(message)))
    }

    private suspend fun DefaultClientWebSocketSession.receiveMessage(): ServerMessage? {
        while (true) {
            val frame = incoming.receiveCatching().getOrNull() ?: return null
            if (frame is Frame.Text) {
                return ProtocolJson.json.decodeFromString<ServerMessage>(frame.readText())
            }
            // Ignore control/binary frames; keep waiting for the next text frame.
        }
    }

    private fun ServerTarget.wsUrl(): String {
        val scheme = if (secure) "wss" else "ws"
        return "$scheme://$host:$port/${ProtocolVersion.PATH_SEGMENT}/session"
    }

    /** Bounded reconnect strategy for an unexpectedly-dropped socket. */
    data class ReconnectPolicy(
        val maxAttempts: Int = 3,
        val delayMillis: Long = 1_000,
    )

    companion object {
        /** The default Android-friendly engine (OkHttp) with the WebSockets plugin installed. */
        fun defaultHttpClient(): HttpClient =
            HttpClient(OkHttp) {
                install(WebSockets)
            }
    }
}
