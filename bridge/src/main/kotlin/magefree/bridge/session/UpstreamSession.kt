package magefree.bridge.session

import kotlinx.coroutines.flow.Flow
import magefree.protocol.ServerInfo
import magefree.protocol.ServerMessage
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
     * Opens the upstream connection for [credentials] and emits [ServerMessage]s until the session
     * ends. The flow is **cold**: collection starts the connect. It emits a `CONNECTING`
     * [SessionStatus] first, then a terminal status (`AUTH_FAILED`/`VERSION_UNSUPPORTED`) and completes
     * on a failed login, or `CONNECTED` and stays hot to relay later `RECONNECTING`/`DISCONNECTED`
     * transitions **and mapped server pushes** ([magefree.protocol.ChatEvent], story 0006) on the same
     * stream. Widening from `SessionStatus` to `ServerMessage` lets both session status and relayed
     * pushes share one per-session outbound stream. Cancelling the collection (e.g. on socket close)
     * tears the upstream down.
     */
    public fun connect(credentials: Credentials): Flow<ServerMessage>

    /**
     * The upstream server's info (version + main room id) for a [magefree.protocol.GetServerInfo]
     * request, or `null` when there is no active/connected session. Sourced from the upstream
     * (`getVersionInfo()` == `getServerState().version`, and `getMainRoomId()`).
     */
    public suspend fun serverInfo(): ServerInfo?

    /**
     * Keepalive probe used by [SessionRegistry] while a session is **parked** (app socket dropped)
     * to keep the upstream link healthy during the grace window (story 0023). Returns `true` if the
     * session is still connected after the probe, `false` otherwise (a `false`/throw evicts the
     * parked entry). Backed by `SessionImpl.ping()` + `isConnected()`; a no-op returning `false` when
     * there is no active session.
     */
    public suspend fun ping(): Boolean

    /**
     * The **upstream** server-assigned session id (`SessionImpl.getSessionId()`), or `null` before a
     * session is established. Stable across an app-socket drop+resume — the resume anchor the live IT
     * asserts is unchanged to prove the same upstream session survived (never re-authenticated). This
     * is the XMage id, distinct from the bridge-issued resume handle the app receives.
     */
    public suspend fun sessionId(): String?

    /** Disconnects the upstream session cleanly. Idempotent; safe to call when never connected. */
    public suspend fun disconnect()
}
