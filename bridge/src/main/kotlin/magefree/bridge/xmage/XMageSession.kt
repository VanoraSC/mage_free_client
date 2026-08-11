package magefree.bridge.xmage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mage.remote.Connection
import mage.remote.SessionImpl
import magefree.bridge.mapping.LobbyRelay
import magefree.bridge.mapping.TableRelay
import magefree.protocol.CreateTableOptions
import magefree.protocol.DeckList
import magefree.protocol.GameTypeSummary
import magefree.protocol.RoomUserSummary
import magefree.protocol.SeatPlayerTypeCode
import magefree.protocol.ServerMessage
import magefree.protocol.SkillLevelCode
import magefree.protocol.TableActionCode
import magefree.protocol.TableActionResult
import magefree.protocol.TableFailureCode
import magefree.protocol.TableNotFound
import magefree.protocol.TableSummary
import java.util.UUID

/**
 * A thin wrapper around XMage's [SessionImpl] that exposes the connect / query-main-room /
 * disconnect surface the bridge needs, with the blocking JBoss-remoting calls confined to
 * [Dispatchers.IO] (never the caller's event thread; see AGENTS.md — no blocking I/O on the main
 * thread).
 *
 * By reusing `SessionImpl` the bridge inherits XMage's connect/auth/keepalive/reconnect and
 * Java-serialization handling correct-by-construction. Story 0005 will own one [XMageSession] per
 * connected app client behind the WebSocket; here it is a plain library surface.
 *
 * @property client the callback sink `SessionImpl` drives; exposed so callers can observe the
 *   connection lifecycle it records.
 */
public class XMageSession(
    public val client: BridgeMageClient = BridgeMageClient(),
) {
    private val session: SessionImpl = SessionImpl(client)

    /** True while the underlying `SessionImpl` reports an active connection. */
    public val isConnected: Boolean
        get() = session.isConnected

    /**
     * The server-assigned session id via `SessionImpl.getSessionId()`, or `null` before the connect
     * handshake populates it. Stable for the life of a connection — the anchor story 0023 uses to
     * prove a resumed session is the *same* upstream session (never reconnected/re-authed).
     */
    public fun sessionId(): String? = session.sessionId

    /**
     * Connects and authenticates against the server described by [connection], running the blocking
     * `connectStart` on [Dispatchers.IO]. `connectStart` performs the mandatory version handshake
     * and, for non-admin users, sets the user data.
     *
     * @return `true` if the connection was established, `false` otherwise.
     */
    public suspend fun connect(connection: Connection): Boolean =
        withContext(Dispatchers.IO) {
            session.connectStart(connection)
        }

    /**
     * The lobby's main room id, fetched from the server via `SessionImpl.getMainRoomId()`. Non-null
     * once connected; may be `null` if called while disconnected.
     */
    public fun mainRoomId(): UUID? = session.mainRoomId

    /**
     * The server's version string via `SessionImpl.getVersionInfo()` — which is exactly
     * `serverState.getVersion().toString()`, or `"<no server state>"` before the handshake populates it.
     */
    public fun versionInfo(): String = session.versionInfo

    /**
     * The chat id of the lobby's main room: `getMainRoomId()` → `getRoomChatId(roomId)`. Null if
     * disconnected or the server returns no chat for the room. Runs the blocking calls on
     * [Dispatchers.IO].
     */
    public suspend fun mainRoomChatId(): UUID? =
        withContext(Dispatchers.IO) {
            session.mainRoomId?.let { session.getRoomChatId(it).orElse(null) }
        }

    /** Joins the chat [chatId] via `SessionImpl.joinChat`. Returns the server's acceptance flag. */
    public suspend fun joinChat(chatId: UUID): Boolean =
        withContext(Dispatchers.IO) {
            session.joinChat(chatId)
        }

    /** Sends [text] to chat [chatId] via `SessionImpl.sendChatMessage`. Returns the server's flag. */
    public suspend fun sendChatMessage(
        chatId: UUID,
        text: String,
    ): Boolean =
        withContext(Dispatchers.IO) {
            session.sendChatMessage(chatId, text)
        }

    /**
     * The open/active tables in the lobby's main room, mapped to app-schema summaries (story 0027).
     * Resolves the main room id (`getMainRoomId()`) then reads `getTables(roomId)` and maps at the
     * `magefree.bridge.mapping` boundary (via [LobbyRelay]) — the raw `mage.view.TableView` never
     * leaves that package. Returns an empty list when disconnected (no main room id). Runs the blocking
     * remoting calls on [Dispatchers.IO].
     */
    public suspend fun tables(): List<TableSummary> =
        withContext(Dispatchers.IO) {
            val roomId = session.mainRoomId ?: return@withContext emptyList()
            LobbyRelay.tables(session, roomId)
        }

    /**
     * The users currently in the lobby's main room, mapped to app-schema summaries (story 0027).
     * Same room-id resolution + mapper-boundary handling as [tables]; empty when disconnected. Runs on
     * [Dispatchers.IO].
     */
    public suspend fun roomUsers(): List<RoomUserSummary> =
        withContext(Dispatchers.IO) {
            val roomId = session.mainRoomId ?: return@withContext emptyList()
            LobbyRelay.roomUsers(session, roomId)
        }

    /**
     * The game formats (types) the server offers, mapped to app-schema summaries (story 0027).
     * `getGameTypes()` is room-independent. Mapped at the `magefree.bridge.mapping` boundary (via
     * [LobbyRelay]). Runs the blocking remoting call on [Dispatchers.IO].
     */
    public suspend fun gameTypes(): List<GameTypeSummary> =
        withContext(Dispatchers.IO) {
            LobbyRelay.gameTypes(session)
        }

    // ------------------------------------------------------------------------------------------------
    // Table actions (story 0036). Each resolves the room (the request's [roomId] or the main room),
    // dispatches to the wrapped `SessionImpl` verb via [TableRelay] at the mapping boundary (so no
    // `mage.*` shape leaves that package), and runs the blocking remoting call on [Dispatchers.IO]. A
    // create/join/... against a disconnected session (no resolvable room) maps to a failed result.
    // ------------------------------------------------------------------------------------------------

    /** Creates a table from [options] in [roomId] (or the main room), returning `TableCreated`/failed result. */
    public suspend fun createTable(
        roomId: UUID?,
        options: CreateTableOptions,
    ): ServerMessage =
        withContext(Dispatchers.IO) {
            val room = roomId ?: session.mainRoomId ?: return@withContext notConnected(TableActionCode.CREATE)
            TableRelay.createTable(session, room, options)
        }

    /** Joins the constructed table [tableId] in [roomId] (or the main room) as [seatName] with [deck]. */
    public suspend fun joinTable(
        roomId: UUID?,
        tableId: UUID,
        seatName: String,
        deck: DeckList,
        playerType: SeatPlayerTypeCode,
        skill: SkillLevelCode,
        password: String?,
    ): TableActionResult =
        withContext(Dispatchers.IO) {
            val room = roomId ?: session.mainRoomId ?: return@withContext notConnected(TableActionCode.JOIN)
            TableRelay.joinTable(session, room, tableId, seatName, deck, playerType, skill, password)
        }

    /** Submits [deck] (the binding submission) for the seat at [tableId]. */
    public suspend fun submitDeck(
        tableId: UUID,
        deck: DeckList,
    ): TableActionResult =
        withContext(Dispatchers.IO) {
            if (!session.isConnected) return@withContext notConnected(TableActionCode.SUBMIT_DECK)
            TableRelay.submitDeck(session, tableId, deck)
        }

    /** Saves the in-progress [deck] for the seat at [tableId]. */
    public suspend fun updateDeck(
        tableId: UUID,
        deck: DeckList,
    ): TableActionResult =
        withContext(Dispatchers.IO) {
            if (!session.isConnected) return@withContext notConnected(TableActionCode.UPDATE_DECK)
            TableRelay.updateDeck(session, tableId, deck)
        }

    /** Leaves the table [tableId] in [roomId] (or the main room). */
    public suspend fun leaveTable(
        roomId: UUID?,
        tableId: UUID,
    ): TableActionResult =
        withContext(Dispatchers.IO) {
            val room = roomId ?: session.mainRoomId ?: return@withContext notConnected(TableActionCode.LEAVE)
            TableRelay.leaveTable(session, room, tableId)
        }

    /** Removes the table [tableId] in [roomId] (or the main room). */
    public suspend fun removeTable(
        roomId: UUID?,
        tableId: UUID,
    ): TableActionResult =
        withContext(Dispatchers.IO) {
            val room = roomId ?: session.mainRoomId ?: return@withContext notConnected(TableActionCode.REMOVE)
            TableRelay.removeTable(session, room, tableId)
        }

    /** Starts the match at table [tableId] in [roomId] (or the main room). */
    public suspend fun startMatch(
        roomId: UUID?,
        tableId: UUID,
    ): TableActionResult =
        withContext(Dispatchers.IO) {
            val room = roomId ?: session.mainRoomId ?: return@withContext notConnected(TableActionCode.START_MATCH)
            TableRelay.startMatch(session, room, tableId)
        }

    /** Watches (spectates) the table [tableId] in [roomId] (or the main room). */
    public suspend fun watchTable(
        roomId: UUID?,
        tableId: UUID,
    ): TableActionResult =
        withContext(Dispatchers.IO) {
            val room = roomId ?: session.mainRoomId ?: return@withContext notConnected(TableActionCode.WATCH)
            TableRelay.watchTable(session, room, tableId)
        }

    /**
     * Reads the detail (summary + seats) of table [tableId] in [roomId] (or the main room) for a
     * `GetTable` (story 0040), replying [magefree.protocol.TableDetail] or a typed
     * [magefree.protocol.TableNotFound]. Resolved from the room's table list at the [TableRelay]
     * boundary — upstream has no single-table read — on [Dispatchers.IO].
     */
    public suspend fun tableDetail(
        roomId: UUID?,
        tableId: UUID,
    ): ServerMessage =
        withContext(Dispatchers.IO) {
            val room =
                roomId ?: session.mainRoomId
                    ?: return@withContext TableNotFound(tableId = tableId.toString(), reason = "no connected session")
            TableRelay.tableDetail(session, room, tableId)
        }

    /**
     * A failed [TableActionResult] for an action attempted without a connected session/resolvable room.
     * Tagged [TableFailureCode.SESSION_GONE] (story 0050): the server was never asked, so the app must
     * say "you are no longer signed in" rather than "the server declined".
     */
    private fun notConnected(action: TableActionCode): TableActionResult =
        TableActionResult(
            action = action,
            ok = false,
            reason = "no connected session",
            failure = TableFailureCode.SESSION_GONE,
        )

    /**
     * Keepalive probe: `SessionImpl.ping()` then report `isConnected()`. Used by story 0023's
     * [magefree.bridge.session.SessionRegistry] to keep a **parked** session (app socket dropped)
     * alive during its grace window and to notice if the upstream link died. `ping()` itself is
     * best-effort (it routes failures through the connection-error path, surfacing a `disconnected`
     * callback); the returned flag reflects liveness right after. Runs on [Dispatchers.IO].
     *
     * @return `true` if still connected after the probe, `false` otherwise.
     */
    public suspend fun ping(): Boolean =
        withContext(Dispatchers.IO) {
            session.ping()
            session.isConnected
        }

    /**
     * Disconnects cleanly via `connectStop(false, false)` — no reconnect prompt, do not keep the
     * server-side session alive. Runs on [Dispatchers.IO].
     */
    public suspend fun disconnect() {
        withContext(Dispatchers.IO) {
            session.connectStop(false, false)
        }
    }
}
