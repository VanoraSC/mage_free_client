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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import magefree.model.ConnectionError
import magefree.model.ConnectionState
import magefree.model.Credentials
import magefree.model.ServerTarget
import magefree.model.SessionEvent
import magefree.network.BridgeClient
import magefree.network.ServerPushSource
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
import magefree.protocol.Logout
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
 *
 * ### Recorded finding — the on-device "~33 s socket close" is the platform, not this client
 *
 * Story 0048's smoke saw the bridge log `101 Switching Protocols: GET - /v1/session in
 * 33099/33135/33319/33433/33516ms` on every post-offline session, each immediately followed by a
 * successful `Resume`. Story 0050 diagnosed it before changing anything; the answer is **Android's
 * network linger**, and there is nothing here to fix:
 *
 * - Neither peer runs a WebSocket pinger, so it cannot be a ping/pong timeout: Ktor 3.5.1 defaults
 *   the server's `WebSockets.pingPeriodMillis` and the client's `WebSockets.pingIntervalMillis` to
 *   `PINGER_DISABLED`, and neither the bridge nor [defaultHttpClient] overrides them.
 * - It is not an idle timeout in the transport path either: a session held **idle** against the same
 *   published bridge port closed after neither 90 s (raw socket from the container host) nor 100 s
 *   (this client's engine from the host JVM) — the bridge logged the full `… in 90764ms` /
 *   `… in 100520ms` and nothing closed early.
 * - Reproduced on the emulator and traced in `ConnectivityService`: after the smoke's airplane-mode
 *   off the emulator brings CELLULAR up first and the app connects over it; WIFI validates ~3 s later
 *   and takes over as the default network, at which point Android logs
 *   `Setting inactive [112 CELLULAR] for 30000ms` and then, exactly 30 s later,
 *   `handleLingerComplete for [112 CELLULAR]` — in the same second the bridge logged
 *   `… /v1/session in 32776ms`. The socket dies because the network it was bound to is torn down
 *   after Android's fixed 30 s linger; ~3 s of settling plus that constant 30 s timer is the whole
 *   ~33 s, and the fixed timer is why the number is so repeatable.
 *
 * So this is an ordinary transport drop on a network hand-off — **intended platform behaviour, left
 * alone** — and stories 0023/0024's park + resume is precisely the right response to it, which is why
 * the smoke survived it every time.
 */
class KtorBridgeClient(
    private val httpClientFactory: () -> HttpClient = ::defaultHttpClient,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val reconnectPolicy: ReconnectPolicy = ReconnectPolicy(),
    private val connectivity: ConnectivityObserver = AlwaysOnlineConnectivityObserver,
    private val lifecycle: AppLifecycleObserver = AlwaysForegroundAppLifecycleObserver,
    private val requestTimeoutMillis: Long = DEFAULT_REQUEST_TIMEOUT_MILLIS,
) : BridgeClient,
    ServerPushSource {
    private val _connectionState = MutableStateFlow(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    /**
     * The uncorrelated server-push side-channel (story 0037's seam (b)): the relay forwards each 0036
     * table event (and any other spontaneous, non-lifecycle frame) here, and the table client folds the
     * ones for its table. Hot and replay-less (`replay = 0`); a slow subscriber drops the oldest rather
     * than back-pressuring the socket read loop. `:protocol`-typed but confined to `:core:network` via
     * the `internal` [ServerPushSource].
     */
    private val _serverPushes =
        MutableSharedFlow<ServerMessage>(
            replay = 0,
            extraBufferCapacity = 64,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    override val serverPushes: SharedFlow<ServerMessage> = _serverPushes.asSharedFlow()

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

    override suspend fun signOut() {
        // Set first: a Logout makes the bridge tear the session down, so the socket close that follows
        // must already be understood as *requested* — otherwise the relay would read it as an
        // unexpected drop and start reconnecting the session we just ended.
        disconnectRequested = true
        val session = activeSession
        if (session != null) {
            try {
                // Best effort, bounded: a half-dead socket must not hold the user in a session they
                // asked to leave. Failing to signal only costs the bridge's resume TTL — the exact
                // cost of not signalling at all, which is what happened before story 0046.
                // KNOWN ISSUE (observed 2026-08-11, accepted — do not "fix" without a reason).
                // This send can lose the race with the close that follows, most reliably on a
                // freshly-resumed socket: the bridge then sees a bare close and logs
                // `Parked session … for up to 1m` instead of `Evicted`, e.g.
                //     11:29:54 Resumed …   11:30:47 Parked …   (that Parked was a sign-out)
                // The session still goes away at the resume TTL (~60 s) and nothing is orphaned, so
                // the cost is bounded and self-healing with no user-visible effect. Closing the race
                // properly means reworking the teardown handshake, which risks story 0023/0024's
                // park+resume — behaviour that is correct and load-bearing — for no observable gain.
                // Deliberately logged rather than fixed; see docs/stories/README.md § Known issues.
                withTimeoutOrNull(LOGOUT_SEND_TIMEOUT_MILLIS) { session.sendMessage(Logout()) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // Already closed / failed write: fall through to teardown, which is what matters.
            }
        }
        disconnect()
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
                        featurePush = { _serverPushes.emit(it) },
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

        /**
         * How long a [signOut] will wait for its `Logout` write to land before giving up and tearing
         * down anyway (story 0046). Deliberately short: this is one frame on an already-open socket,
         * and the user is waiting.
         */
        private const val LOGOUT_SEND_TIMEOUT_MILLIS = 2_000L

        /** The default Android-friendly engine (OkHttp) with the WebSockets plugin installed. */
        fun defaultHttpClient(): HttpClient =
            HttpClient(OkHttp) {
                install(WebSockets)
            }
    }
}
