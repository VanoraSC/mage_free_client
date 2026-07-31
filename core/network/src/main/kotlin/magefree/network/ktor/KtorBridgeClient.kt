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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withTimeout
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
import magefree.network.reconnect.AlwaysForegroundAppLifecycleObserver
import magefree.network.reconnect.AlwaysOnlineConnectivityObserver
import magefree.network.reconnect.AppLifecycleObserver
import magefree.network.reconnect.ConnectivityObserver
import magefree.network.reconnect.PendingRequests
import magefree.network.reconnect.ReconnectPolicy
import magefree.network.reconnect.ReconnectingSession
import magefree.network.reconnect.ResumeHandle
import magefree.network.reconnect.SessionOutcome
import magefree.network.reconnect.SessionRelay
import magefree.network.reconnect.SessionRunner
import magefree.protocol.ClientHello
import magefree.protocol.ClientMessage
import magefree.protocol.ProtocolJson
import magefree.protocol.ProtocolVersion
import magefree.protocol.ServerHello
import magefree.protocol.ServerMessage

/**
 * A reconnection-aware [BridgeClient] over a Ktor WebSocket speaking the `:protocol` contract.
 *
 * On [connect] it opens `ws(s)://host:port/<major>/session`, performs the [ClientHello]/[ServerHello]
 * handshake (rejecting a major mismatch as [SessionEvent.VersionUnsupported]), then hands the socket to
 * [SessionRelay], which sends `Login` (first connect) or `Resume(resumeId)` (a reconnect resuming the
 * parked session, story 0023) and relays each server frame through [SessionMapper]. An unexpected socket
 * drop drives a [ReconnectingSession] loop: bounded exponential back-off + jitter ([ReconnectPolicy]),
 * cut short when [connectivity] returns or the app is refocused ([lifecycle]); authentication/version/
 * disconnect outcomes are terminal. The captured `resumeId` is carried across attempts via a
 * [ResumeHandle], and a `ResumeRejected` falls back to a fresh `Login` with the retained credentials.
 *
 * All wire (`:protocol`) types are confined to this class, [SessionRelay], and the mapper — callers see
 * only `:core:model` [SessionEvent]s. Credentials are held only for the duration of a [connect] flow
 * (for the resume fallback) and never logged or persisted.
 */
class KtorBridgeClient(
    private val httpClientFactory: () -> HttpClient = ::defaultHttpClient,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val reconnectPolicy: ReconnectPolicy = ReconnectPolicy(),
    private val connectivity: ConnectivityObserver = AlwaysOnlineConnectivityObserver,
    private val lifecycle: AppLifecycleObserver = AlwaysForegroundAppLifecycleObserver,
    private val requestTimeoutMillis: Long = DEFAULT_REQUEST_TIMEOUT_MILLIS,
) : BridgeClient {
    private val _connectionState = MutableStateFlow(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    /**
     * The correlation registry multiplexing [request]/response over the live socket alongside the
     * session-event stream (story 0028). One per client instance: at most one session is active at a
     * time, and each session end fails any outstanding waiter.
     */
    private val pending = PendingRequests()

    @Volatile
    private var activeSession: DefaultClientWebSocketSession? = null

    @Volatile
    private var disconnectRequested = false

    override fun connect(
        server: ServerTarget,
        credentials: Credentials,
    ): Flow<SessionEvent> {
        disconnectRequested = false
        val client = httpClientFactory()
        val runner = SessionRunner { handle, emit -> runSession(client, server, credentials, handle, emit) }
        return ReconnectingSession(
            policy = reconnectPolicy,
            connectivity = connectivity,
            lifecycle = lifecycle,
            isDisconnectRequested = { disconnectRequested },
            runner = runner,
        ).events()
            .onCompletion {
                activeSession = null
                client.close()
            }.onEach { _connectionState.value = it.connectionState }
            .flowOn(ioDispatcher)
    }

    override suspend fun disconnect() {
        disconnectRequested = true
        pending.failAll(IllegalStateException("session disconnected before reply"))
        activeSession?.close(CloseReason(CloseReason.Codes.NORMAL, "client disconnect"))
        activeSession = null
        _connectionState.value = ConnectionState.Disconnected
    }

    override suspend fun <ReplyT : Any> request(
        message: Any,
        requestId: String,
    ): ReplyT {
        val session =
            activeSession ?: throw IllegalStateException("no active session for request $requestId")
        val clientMessage =
            message as? ClientMessage
                ?: throw IllegalArgumentException("request payload must be a ClientMessage, was ${message::class}")
        val deferred = pending.register(requestId)
        return try {
            session.sendMessage(clientMessage)
            @Suppress("UNCHECKED_CAST")
            withTimeout(requestTimeoutMillis) { deferred.await() } as ReplyT
        } catch (e: Throwable) {
            // Timeout/cancellation/failure: drop the waiter so the registry does not leak.
            pending.forget(requestId)
            throw e
        }
    }

    private suspend fun runSession(
        client: HttpClient,
        server: ServerTarget,
        credentials: Credentials,
        handle: ResumeHandle,
        emit: suspend (SessionEvent) -> Unit,
    ): SessionOutcome {
        val session =
            try {
                client.webSocketSession(urlString = server.wsUrl())
            } catch (e: Exception) {
                emit(SessionEvent.Error(ConnectionError.Transport("failed to open websocket: ${e.message}", e)))
                return SessionOutcome.RETRY
            }
        activeSession = session
        return try {
            // Handshake: announce our version, expect the bridge's hello.
            session.sendMessage(ClientHello(ProtocolVersion.MAJOR, ProtocolVersion.MINOR))
            when (val hello = session.receiveMessage()) {
                is ServerHello -> {
                    handshakeResult(hello)?.let {
                        emit(it)
                        return SessionOutcome.TERMINAL
                    }
                    // Post-handshake: Resume (if a handle is held) or Login, then relay.
                    SessionRelay.run(
                        server = server,
                        credentials = credentials,
                        bridgeVersion = hello.bridgeVersion,
                        handle = handle,
                        receive = { session.receiveMessage() },
                        send = { session.sendMessage(it) },
                        emit = emit,
                        isDisconnectRequested = { disconnectRequested },
                        pending = pending,
                    )
                }
                null -> SessionOutcome.RETRY
                else -> {
                    // The bridge answered the hello with something else (e.g. a version ProtocolError).
                    val event = SessionMapper.toSessionEvent(hello, server, credentials.username, null)
                    if (event != null) {
                        emit(event)
                        SessionOutcome.TERMINAL
                    } else {
                        SessionOutcome.RETRY
                    }
                }
            }
        } catch (e: Exception) {
            emit(SessionEvent.Error(ConnectionError.Transport("session error: ${e.message}", e)))
            SessionOutcome.RETRY
        } finally {
            // The socket for this attempt is gone: no reply will arrive, so fail any in-flight request
            // (a reconnect gets a fresh socket; the requester surfaces this and can refresh again).
            pending.failAll(IllegalStateException("session ended before reply"))
            session.close()
            if (activeSession === session) activeSession = null
        }
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

    companion object {
        /** How long [request] waits for a correlated reply before failing (story 0028). */
        private const val DEFAULT_REQUEST_TIMEOUT_MILLIS = 15_000L

        /** The default Android-friendly engine (OkHttp) with the WebSockets plugin installed. */
        fun defaultHttpClient(): HttpClient =
            HttpClient(OkHttp) {
                install(WebSockets)
            }
    }
}
