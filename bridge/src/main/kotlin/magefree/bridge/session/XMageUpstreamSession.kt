package magefree.bridge.session

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mage.remote.MageVersionException
import magefree.bridge.mapping.CallbackRelay
import magefree.bridge.xmage.XMageClientEvent
import magefree.bridge.xmage.XMageConnection
import magefree.bridge.xmage.XMageSession
import magefree.protocol.ClientMessage
import magefree.protocol.CreateTable
import magefree.protocol.GameActionCode
import magefree.protocol.GameActionResult
import magefree.protocol.GameFailureCode
import magefree.protocol.GameTypeSummary
import magefree.protocol.GetTable
import magefree.protocol.JoinGame
import magefree.protocol.JoinTable
import magefree.protocol.LeaveTable
import magefree.protocol.QuitMatch
import magefree.protocol.RemoveTable
import magefree.protocol.RoomUserSummary
import magefree.protocol.SendPlayerAction
import magefree.protocol.SendPlayerBoolean
import magefree.protocol.SendPlayerInteger
import magefree.protocol.SendPlayerManaType
import magefree.protocol.SendPlayerString
import magefree.protocol.SendPlayerUuid
import magefree.protocol.ServerInfo
import magefree.protocol.ServerMessage
import magefree.protocol.SessionStateCode
import magefree.protocol.SessionStatus
import magefree.protocol.StartMatch
import magefree.protocol.StopWatching
import magefree.protocol.SubmitDeck
import magefree.protocol.TableActionCode
import magefree.protocol.TableActionResult
import magefree.protocol.TableFailureCode
import magefree.protocol.TableNotFound
import magefree.protocol.TableSummary
import magefree.protocol.UpdateDeck
import magefree.protocol.WatchGame
import magefree.protocol.WatchTable
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * The real [UpstreamSession] over the `XMageSession`/`BridgeMageClient`/`SessionImpl`.
 *
 * It builds a `Connection` to the bridge's **pinned** [target], runs `connectStart` on
 * `Dispatchers.IO` (inside `XMageSession.connect`), and translates the lifecycle into
 * [SessionStatus] frames:
 *
 * | upstream signal                                   | status               |
 * |---------------------------------------------------|----------------------|
 * | before connect                                    | `CONNECTING`         |
 * | `connectStart` → true (`connected(...)` fired)    | `CONNECTED`          |
 * | `connectStart` → false                            | `AUTH_FAILED`        |
 * | `MageVersionException` (see note)                 | `VERSION_UNSUPPORTED` |
 * | `disconnected(askToReconnect=true, ...)`          | `RECONNECTING`       |
 * | `disconnected(askToReconnect=false, ...)`         | `DISCONNECTED`       |
 *
 * **Thread hand-off.** Remoting-thread callbacks are captured by `BridgeMageClient` and re-published
 * on its `events` [kotlinx.coroutines.flow.SharedFlow]; the `relay` coroutine here collects them and
 * turns them into frames via the `channelFlow` `send`, which is the only place the Ktor coroutine is
 * touched — a remoting thread never sends a WebSocket frame.
 *
 * **Version-mismatch note.** The baked `mage-common:1.4.60` `SessionImpl.connectStart` *catches*
 * `MageVersionException` internally and returns `false`, so a real version gap presents as
 * `AUTH_FAILED` and the `VERSION_UNSUPPORTED` arm below is defensive (unreachable with this upstream).
 * `MageVersionException` also exposes neither version — it only formats a message — so the arm reports
 * the bridge's own version and `server=unknown`. Under the pinned-server posture a gap cannot occur;
 * the exact `"server=<v> bridge=<v>"` contract is proven hermetically by `FakeUpstreamSession`.
 */
public class XMageUpstreamSession(
    private val target: UpstreamTarget,
    private val sessionFactory: () -> XMageSession = { XMageSession() },
) : UpstreamSession {
    private val logger = LoggerFactory.getLogger(XMageUpstreamSession::class.java)

    @Volatile
    private var current: XMageSession? = null

    override fun connect(credentials: Credentials): Flow<ServerMessage> =
        channelFlow {
            val session = sessionFactory()
            current = session

            // Collect the handed-off remoting events and relay drops/reconnects as status frames.
            // send(...) here runs on the flow's collecting coroutine, never on a remoting thread.
            val relay =
                launch {
                    session.client.events.collect { event ->
                        when (event) {
                            is XMageClientEvent.Disconnected ->
                                if (event.askToReconnect) {
                                    send(SessionStatus(SessionStateCode.RECONNECTING))
                                } else {
                                    send(SessionStatus(SessionStateCode.DISCONNECTED))
                                    close()
                                }
                            else -> Unit // Connected handled inline; messages/errors carry no status here.
                        }
                    }
                }

            // Relay mapped server pushes (chat, etc.) onto the SAME outbound stream. The
            // raw callbacks are handed off by BridgeMageClient off the remoting thread; CallbackRelay
            // does the decompress+map on this coroutine and the ClientCallback/mage.view.* references
            // stay entirely inside magefree.bridge.mapping — this layer only ever sees ServerMessage.
            val callbackRelay =
                launch {
                    CallbackRelay.relay(session.client.callbacks).collect { message ->
                        send(message)
                    }
                }

            try {
                send(SessionStatus(SessionStateCode.CONNECTING))

                val connection =
                    XMageConnection.build(
                        host = target.host,
                        port = target.port,
                        username = credentials.username,
                        password = credentials.password ?: "",
                    )

                val connected =
                    try {
                        session.connect(connection)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (versionMismatch: MageVersionException) {
                        logger.warn("Upstream version mismatch", versionMismatch)
                        send(
                            SessionStatus(
                                state = SessionStateCode.VERSION_UNSUPPORTED,
                                message = "server=unknown bridge=${session.client.getVersion()}",
                            ),
                        )
                        session.disconnect()
                        return@channelFlow
                    }

                if (connected) {
                    send(SessionStatus(SessionStateCode.CONNECTED))
                    // Stay hot: relay later RECONNECTING/DISCONNECTED until the collector cancels
                    // (socket close / Logout) or the relay closes the channel on a clean drop.
                    awaitClose()
                } else {
                    send(SessionStatus(SessionStateCode.AUTH_FAILED))
                    session.disconnect()
                }
            } finally {
                relay.cancel()
                callbackRelay.cancel()
            }
        }

    override suspend fun serverInfo(): ServerInfo? {
        val session = current ?: return null
        if (!session.isConnected) return null
        return withContext(Dispatchers.IO) {
            ServerInfo(
                serverVersion = session.versionInfo(),
                mainRoomId = session.mainRoomId()?.toString(),
            )
        }
    }

    override suspend fun tables(): List<TableSummary> = lobbyQuery("tables") { it.tables() }

    override suspend fun roomUsers(): List<RoomUserSummary> = lobbyQuery("roomUsers") { it.roomUsers() }

    override suspend fun gameTypes(): List<GameTypeSummary> = lobbyQuery("gameTypes") { it.gameTypes() }

    /**
     * Runs a read-only lobby [query] against the connected [XMageSession], returning an empty list when
     * there is no connected session or the blocking upstream read fails (e.g. a `MageRemoteException`
     * transport error) — a browse must never surface as a stream error. [name] labels the
     * log line.
     */
    private suspend fun <T> lobbyQuery(
        name: String,
        query: suspend (XMageSession) -> List<T>,
    ): List<T> {
        val session = current ?: return emptyList()
        if (!session.isConnected) return emptyList()
        return try {
            query(session)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            logger.warn("Upstream {} query failed: {}", name, failure.toString())
            emptyList()
        }
    }

    override suspend fun createTable(request: CreateTable): ServerMessage {
        val session = current
        if (session == null || !session.isConnected) return noSession(TableActionCode.CREATE)
        return try {
            session.createTable(parseUuid(request.roomId), request.options)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            logger.warn("Upstream createTable action failed: {}", failure.toString())
            actionFailed(TableActionCode.CREATE, "action failed")
        }
    }

    override suspend fun joinTable(request: JoinTable): TableActionResult =
        tableAction(TableActionCode.JOIN) { session ->
            val tableId = parseUuid(request.tableId) ?: return@tableAction actionFailed(TableActionCode.JOIN, INVALID_TABLE_ID)
            session.joinTable(
                roomId = parseUuid(request.roomId),
                tableId = tableId,
                seatName = request.seatName,
                deck = request.deck,
                playerType = request.playerType,
                skill = request.skill,
                password = request.password,
            )
        }

    override suspend fun submitDeck(request: SubmitDeck): TableActionResult =
        tableAction(TableActionCode.SUBMIT_DECK) { session ->
            val tableId = parseUuid(request.tableId) ?: return@tableAction actionFailed(TableActionCode.SUBMIT_DECK, INVALID_TABLE_ID)
            session.submitDeck(tableId, request.deck)
        }

    override suspend fun updateDeck(request: UpdateDeck): TableActionResult =
        tableAction(TableActionCode.UPDATE_DECK) { session ->
            val tableId = parseUuid(request.tableId) ?: return@tableAction actionFailed(TableActionCode.UPDATE_DECK, INVALID_TABLE_ID)
            session.updateDeck(tableId, request.deck)
        }

    override suspend fun leaveTable(request: LeaveTable): TableActionResult =
        tableAction(TableActionCode.LEAVE) { session ->
            val tableId = parseUuid(request.tableId) ?: return@tableAction actionFailed(TableActionCode.LEAVE, INVALID_TABLE_ID)
            session.leaveTable(parseUuid(request.roomId), tableId)
        }

    override suspend fun removeTable(request: RemoveTable): TableActionResult =
        tableAction(TableActionCode.REMOVE) { session ->
            val tableId = parseUuid(request.tableId) ?: return@tableAction actionFailed(TableActionCode.REMOVE, INVALID_TABLE_ID)
            session.removeTable(parseUuid(request.roomId), tableId)
        }

    override suspend fun startMatch(request: StartMatch): TableActionResult =
        tableAction(TableActionCode.START_MATCH) { session ->
            val tableId = parseUuid(request.tableId) ?: return@tableAction actionFailed(TableActionCode.START_MATCH, INVALID_TABLE_ID)
            session.startMatch(parseUuid(request.roomId), tableId)
        }

    override suspend fun watchTable(request: WatchTable): TableActionResult =
        tableAction(TableActionCode.WATCH) { session ->
            val tableId = parseUuid(request.tableId) ?: return@tableAction actionFailed(TableActionCode.WATCH, INVALID_TABLE_ID)
            session.watchTable(parseUuid(request.roomId), tableId)
        }

    override suspend fun tableDetail(request: GetTable): ServerMessage {
        val session = current
        if (session == null || !session.isConnected) return notFound(request.tableId, "no connected session")
        val tableId = parseUuid(request.tableId) ?: return notFound(request.tableId, INVALID_TABLE_ID)
        return try {
            session.tableDetail(parseUuid(request.roomId), tableId)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            // A read must never surface as a stream error (mirroring `lobbyQuery`): a transport failure
            // becomes a typed not-found, so the app sees a result rather than a hang.
            logger.warn("Upstream tableDetail read failed: {}", failure.toString())
            notFound(request.tableId, "read failed")
        }
    }

    private fun notFound(
        tableId: String,
        reason: String,
    ): TableNotFound = TableNotFound(tableId = tableId, reason = reason)

    /**
     * In-game request dispatch. The `when` is what makes the protocol's game verbs total at
     * this seam: each message is answered by exactly one `XMageSession` call, and anything that is not a
     * game request is a programming error the coordinator cannot produce — reported as a typed failure
     * rather than thrown, because a game request that vanishes leaves the player waiting forever.
     */
    override suspend fun gameRequest(request: ClientMessage): GameActionResult {
        val action = gameActionOf(request)
        val session = current
        if (session == null || !session.isConnected) return noGameSession(action)
        val gameId = parseUuid(gameIdOf(request)) ?: return gameFailed(action, INVALID_GAME_ID)
        return try {
            when (request) {
                is JoinGame -> session.joinGame(gameId)
                is WatchGame -> session.watchGame(gameId)
                is QuitMatch -> session.quitMatch(gameId)
                is StopWatching -> session.stopWatching(gameId)
                is SendPlayerBoolean -> session.sendPlayerBoolean(gameId, request.value)
                is SendPlayerInteger -> session.sendPlayerInteger(gameId, request.value)
                is SendPlayerString -> session.sendPlayerString(gameId, request.value)
                is SendPlayerUuid ->
                    parseUuid(request.value)
                        ?.let { session.sendPlayerUuid(gameId, it) }
                        ?: gameFailed(action, INVALID_OBJECT_ID)
                is SendPlayerManaType ->
                    parseUuid(request.playerId)
                        ?.let { session.sendPlayerManaType(gameId, it, request.manaType) }
                        ?: gameFailed(action, INVALID_PLAYER_ID)
                is SendPlayerAction ->
                    session.sendPlayerAction(gameId, request.action, request.dataInt ?: request.dataText)
                else -> gameFailed(action, NOT_A_GAME_REQUEST)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            logger.warn("Upstream {} game request failed: {}", action, failure.toString())
            gameFailed(action, "request failed")
        }
    }

    /** The [GameActionCode] a game request answers — the one place the message→code mapping lives. */
    private fun gameActionOf(request: ClientMessage): GameActionCode =
        when (request) {
            is JoinGame -> GameActionCode.JOIN_GAME
            is WatchGame -> GameActionCode.WATCH_GAME
            is QuitMatch -> GameActionCode.QUIT_MATCH
            is StopWatching -> GameActionCode.STOP_WATCHING
            is SendPlayerUuid -> GameActionCode.SEND_UUID
            is SendPlayerBoolean -> GameActionCode.SEND_BOOLEAN
            is SendPlayerInteger -> GameActionCode.SEND_INTEGER
            is SendPlayerString -> GameActionCode.SEND_STRING
            is SendPlayerManaType -> GameActionCode.SEND_MANA_TYPE
            is SendPlayerAction -> GameActionCode.PLAYER_ACTION
            else -> GameActionCode.PLAYER_ACTION
        }

    /** The game id a game request addresses, or `null` when the message is not a game request. */
    private fun gameIdOf(request: ClientMessage): String? =
        when (request) {
            is JoinGame -> request.gameId
            is WatchGame -> request.gameId
            is QuitMatch -> request.gameId
            is StopWatching -> request.gameId
            is SendPlayerUuid -> request.gameId
            is SendPlayerBoolean -> request.gameId
            is SendPlayerInteger -> request.gameId
            is SendPlayerString -> request.gameId
            is SendPlayerManaType -> request.gameId
            is SendPlayerAction -> request.gameId
            else -> null
        }

    /** A typed failed game result; [failure] defaults to a refusal (see [noGameSession] for the other kind). */
    private fun gameFailed(
        action: GameActionCode,
        reason: String,
        failure: GameFailureCode = GameFailureCode.REFUSED,
    ): GameActionResult = GameActionResult(action = action, ok = false, reason = reason, failure = failure)

    /** The failure used wherever this session has no connected upstream to run a game request against. */
    private fun noGameSession(action: GameActionCode): GameActionResult =
        gameFailed(action, NO_CONNECTED_SESSION, GameFailureCode.SESSION_GONE)

    /**
     * Runs a table [action] against the connected [XMageSession], mapping the absence of a connected
     * session — or a transport failure — to a **typed** failed [TableActionResult]: an
     * action must never surface as a stream error, mirroring [lobbyQuery].
     */
    private suspend fun tableAction(
        action: TableActionCode,
        block: suspend (XMageSession) -> TableActionResult,
    ): TableActionResult {
        val session = current
        if (session == null || !session.isConnected) return noSession(action)
        return try {
            block(session)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            logger.warn("Upstream {} action failed: {}", action, failure.toString())
            actionFailed(action, "action failed")
        }
    }

    /**
     * A typed failed action. [reason] is the human-readable detail; [failure] is the *kind* the app
     * branches on — [TableFailureCode.SESSION_GONE] when there is no live upstream session
     * to act through, so the app can offer re-authentication instead of reporting a server decline that
     * never happened.
     */
    private fun actionFailed(
        action: TableActionCode,
        reason: String,
        failure: TableFailureCode = TableFailureCode.REFUSED,
    ): TableActionResult = TableActionResult(action = action, ok = false, reason = reason, failure = failure)

    /** The failure used wherever this session has no connected upstream to run an action against. */
    private fun noSession(action: TableActionCode): TableActionResult =
        actionFailed(action, NO_CONNECTED_SESSION, TableFailureCode.SESSION_GONE)

    /** Parses [value] to a [UUID], or `null` when absent/malformed (a null room falls back to the main room). */
    private fun parseUuid(value: String?): UUID? =
        value?.let {
            try {
                UUID.fromString(it)
            } catch (_: IllegalArgumentException) {
                null
            }
        }

    override suspend fun ping(): Boolean {
        val session = current ?: return false
        if (!session.isConnected) return false
        return session.ping()
    }

    override suspend fun sessionId(): String? = current?.sessionId()

    override suspend fun disconnect() {
        current?.disconnect()
    }

    private companion object {
        const val INVALID_TABLE_ID = "invalid table id"

        /** The reason paired with [TableFailureCode.SESSION_GONE]; the app renders its own wording. */
        const val NO_CONNECTED_SESSION = "no connected session"

        const val INVALID_GAME_ID = "invalid game id"
        const val INVALID_OBJECT_ID = "invalid object id"
        const val INVALID_PLAYER_ID = "invalid player id"

        /** Defensive: the coordinator only routes game messages here, so this is unreachable in practice. */
        const val NOT_A_GAME_REQUEST = "not a game request"
    }
}
