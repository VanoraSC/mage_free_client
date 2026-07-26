package magefree.bridge.session

import kotlinx.coroutines.flow.Flow
import magefree.protocol.SessionStatus

/**
 * Credentials the app supplies in a `Login`. Under the **pinned-server posture** the app never names
 * a host/port — only who to log in as. [password] is optional (the local reference server runs with
 * authentication disabled).
 */
public data class Credentials(
    val username: String,
    val password: String?,
)

/**
 * The test seam between the per-socket [SessionCoordinator] and the upstream XMage server. One
 * instance backs one upstream connection (one per socket).
 *
 * The real implementation ([XMageUpstreamSession]) drives 0003's `XMageSession`/`SessionImpl`; a
 * [magefree.bridge.session.FakeUpstreamSession] (test util) emits scripted sequences so every
 * [magefree.protocol.SessionStateCode] path is exercised hermetically through the real
 * WebSocket/coordinator plumbing.
 */
public interface UpstreamSession {
    /**
     * Opens the upstream connection for [credentials] and emits [SessionStatus] transitions until the
     * session ends. The flow is **cold**: collection starts the connect. It emits `CONNECTING` first,
     * then a terminal status (`AUTH_FAILED`/`VERSION_UNSUPPORTED`) and completes on a failed login, or
     * `CONNECTED` and stays hot to relay later `RECONNECTING`/`DISCONNECTED` transitions. Cancelling
     * the collection (e.g. on socket close) tears the upstream down.
     */
    public fun connect(credentials: Credentials): Flow<SessionStatus>

    /** Disconnects the upstream session cleanly. Idempotent; safe to call when never connected. */
    public suspend fun disconnect()
}
