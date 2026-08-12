package magefree.bridge.session

import kotlinx.coroutines.flow.Flow
import magefree.protocol.ClientMessage
import magefree.protocol.CreateTable
import magefree.protocol.GameActionResult
import magefree.protocol.GameTypeSummary
import magefree.protocol.GetTable
import magefree.protocol.JoinTable
import magefree.protocol.LeaveTable
import magefree.protocol.RemoveTable
import magefree.protocol.RoomUserSummary
import magefree.protocol.ServerInfo
import magefree.protocol.ServerMessage
import magefree.protocol.SessionStatus
import magefree.protocol.StartMatch
import magefree.protocol.SubmitDeck
import magefree.protocol.TableActionResult
import magefree.protocol.TableSummary
import magefree.protocol.UpdateDeck
import magefree.protocol.WatchTable

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
     * The open/active tables in the pinned server's main lobby room, mapped to app-schema
     * [TableSummary] (story 0027), or an **empty list** when there is no active/connected session.
     * The blocking upstream reads run on `Dispatchers.IO`; the mapping happens at the
     * `magefree.bridge.mapping` boundary so no `mage.view.*` type crosses this interface.
     */
    public suspend fun tables(): List<TableSummary>

    /**
     * The users currently in the main lobby room, mapped to app-schema [RoomUserSummary] (story 0027),
     * or an **empty list** when there is no active/connected session. Same IO/mapping contract as
     * [tables].
     */
    public suspend fun roomUsers(): List<RoomUserSummary>

    /**
     * The game formats the pinned server offers, mapped to app-schema [GameTypeSummary] (story 0027),
     * or an **empty list** when there is no active/connected session. Same IO/mapping contract as
     * [tables].
     */
    public suspend fun gameTypes(): List<GameTypeSummary>

    /**
     * Creates (hosts) a table from [request] and replies with a `TableCreated` (the mapped new table)
     * or a failed [TableActionResult] (story 0036). The `mage.*` construction happens at the
     * `magefree.bridge.mapping` boundary; no upstream shape crosses this interface. A create against an
     * unbound/disconnected session maps to a failed result. Runs the blocking upstream call on IO.
     */
    public suspend fun createTable(request: CreateTable): ServerMessage

    /** Joins the constructed table in [request], mapping the boolean verb to a [TableActionResult]. */
    public suspend fun joinTable(request: JoinTable): TableActionResult

    /** Submits the deck in [request] (binding), mapping the boolean verb to a [TableActionResult]. */
    public suspend fun submitDeck(request: SubmitDeck): TableActionResult

    /** Saves the in-progress deck in [request], mapping the boolean verb to a [TableActionResult]. */
    public suspend fun updateDeck(request: UpdateDeck): TableActionResult

    /** Leaves the table in [request], mapping the boolean verb to a [TableActionResult]. */
    public suspend fun leaveTable(request: LeaveTable): TableActionResult

    /** Removes the table in [request], mapping the boolean verb to a [TableActionResult]. */
    public suspend fun removeTable(request: RemoveTable): TableActionResult

    /** Starts the match in [request], mapping the boolean verb to a [TableActionResult]. */
    public suspend fun startMatch(request: StartMatch): TableActionResult

    /** Watches (spectates) the table in [request], mapping the boolean verb to a [TableActionResult]. */
    public suspend fun watchTable(request: WatchTable): TableActionResult

    /**
     * Reads the detail (summary + per-seat state) of the table in [request] (story 0040), replying a
     * [magefree.protocol.TableDetail] or a typed [magefree.protocol.TableNotFound] — including when
     * there is no active/connected session, or the room does not list the table. Resolved from the
     * room's table list at the `magefree.bridge.mapping` boundary (upstream has no single-table read),
     * so no `mage.view.*` type crosses this interface. Runs the blocking upstream read on IO.
     */
    public suspend fun tableDetail(request: GetTable): ServerMessage

    /**
     * Dispatches one in-game request (story 0051) — join/watch/quit/stop, one of the five `sendPlayerX`
     * answers, or a player action — and replies a typed [GameActionResult].
     *
     * A single method rather than one per verb, because unlike the table actions these share exactly one
     * shape: a game id plus a payload, answered by a bare upstream `boolean`. The [request] is the
     * protocol message itself so the id-parsing and the `mage.*` argument construction both stay behind
     * this seam. A request against an unbound/disconnected session, or one carrying an unparseable id,
     * replies a failed result — never a stream error, mirroring the table side.
     */
    public suspend fun gameRequest(request: ClientMessage): GameActionResult

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
