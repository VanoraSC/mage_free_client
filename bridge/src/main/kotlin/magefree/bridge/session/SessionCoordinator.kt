package magefree.bridge.session

import io.ktor.server.websocket.WebSocketServerSession
import io.ktor.server.websocket.receiveDeserialized
import io.ktor.server.websocket.sendSerialized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import magefree.protocol.ClientMessage
import magefree.protocol.CreateTable
import magefree.protocol.GameActionCode
import magefree.protocol.GameActionResult
import magefree.protocol.GameFailureCode
import magefree.protocol.GameStateSnapshot
import magefree.protocol.GameStateUnavailable
import magefree.protocol.GameStateUnavailableCode
import magefree.protocol.GameTypeList
import magefree.protocol.GetGameState
import magefree.protocol.GetGameTypes
import magefree.protocol.GetRoomUsers
import magefree.protocol.GetServerInfo
import magefree.protocol.GetTable
import magefree.protocol.GetTables
import magefree.protocol.JoinGame
import magefree.protocol.JoinTable
import magefree.protocol.LeaveTable
import magefree.protocol.Login
import magefree.protocol.Logout
import magefree.protocol.Ping
import magefree.protocol.Pong
import magefree.protocol.ProtocolError
import magefree.protocol.ProtocolErrorCode
import magefree.protocol.QuitMatch
import magefree.protocol.RemoveTable
import magefree.protocol.Resume
import magefree.protocol.ResumeRejected
import magefree.protocol.RoomUserList
import magefree.protocol.SendPlayerAction
import magefree.protocol.SendPlayerBoolean
import magefree.protocol.SendPlayerInteger
import magefree.protocol.SendPlayerManaType
import magefree.protocol.SendPlayerString
import magefree.protocol.SendPlayerUuid
import magefree.protocol.ServerInfo
import magefree.protocol.ServerMessage
import magefree.protocol.SessionResumable
import magefree.protocol.SessionStateCode
import magefree.protocol.SessionStatus
import magefree.protocol.StartMatch
import magefree.protocol.StopWatching
import magefree.protocol.SubmitDeck
import magefree.protocol.TableActionCode
import magefree.protocol.TableActionResult
import magefree.protocol.TableCreated
import magefree.protocol.TableDetail
import magefree.protocol.TableFailureCode
import magefree.protocol.TableList
import magefree.protocol.TableNotFound
import magefree.protocol.UnknownClientMessage
import magefree.protocol.UpdateDeck
import magefree.protocol.WatchGame
import magefree.protocol.WatchTable
import org.slf4j.LoggerFactory

/**
 * Per-socket orchestration between the app-facing WebSocket and one upstream XMage session (
 * ). It assumes the handshake already completed (a `ServerHello` was sent) and then
 * runs the post-handshake message loop:
 *
 * - `Ping` → `Pong` (liveness continues to work alongside a session).
 * - The first `Login` creates a [LiveSession] via the [registry] (its outbound pump runs on the
 *   registry's bridge-level scope, **not** this socket coroutine) and starts a **forwarder** that
 *   streams every `SessionStatus`/relayed push to the socket, stamping the login's `requestId` onto
 *   the first status. On the first `CONNECTED` it registers the session and emits a
 *   [SessionResumable] carrying the bridge-issued **resume id**.
 * - `Resume(resumeId)` (in place of `Login`, on a fresh handshaken socket) looks the id up: on a hit
 *   it acks with [SessionResumable] and **re-binds** the outbound stream to this socket — no second
 *   upstream connect/login; on a miss it replies [ResumeRejected].
 * - `Logout` evicts (and cleanly `disconnect()`s) the session immediately.
 * - `GetServerInfo` replies with `ServerInfo` correlated by `requestId`.
 * - `GetTables`/`GetRoomUsers`/`GetGameTypes` reply with the correlated `TableList`/`RoomUserList`/
 *   `GameTypeList` browsed from the bound session's main room; an unbound socket replies
 *   an empty list. Read-only — no join/create/watch here.
 * - `GetTable` replies the correlated `TableDetail` (summary + per-seat state) for one table, or a typed
 *   `TableNotFound` when the room does not list it / no session is bound.
 * - `GetGameState` replies the correlated `GameStateSnapshot` for one game — the latest snapshot **this
 *   session** was sent, held by its own [LiveSession] cache — or a typed `GameStateUnavailable` when no
 *   snapshot exists for it / no session is bound. Answered entirely by the bridge: upstream
 *   has no verb that reads a game.
 * - A second `Login`/`Resume` while a session is bound is ignored (documented choice) with a log line.
 * - Any other/malformed frame → a non-terminal `ProtocolError(UNKNOWN_MESSAGE_TYPE)`.
 *
 * **Teardown (changed ).** On socket close *without* a `Logout`, a live registered session is
 * **parked** (`registry.park`) rather than disconnected, so a transient app-network drop no longer
 * loses the game; a `Logout` (handled inline) still disconnects immediately, and a login that never
 * reached `CONNECTED` is simply disconnected. The chosen teardown path runs under [NonCancellable].
 */
public class SessionCoordinator(
    private val registry: SessionRegistry,
    private val newUpstream: () -> UpstreamSession,
) {
    private val logger = LoggerFactory.getLogger(SessionCoordinator::class.java)

    /**
     * A typed failed [TableActionResult] for a table action attempted on an unbound socket,
     * carrying [TableFailureCode.SESSION_GONE] so the app can say "you are not signed in" rather than
     * "the server declined" — the server was never asked.
     */
    private fun unboundFailure(action: TableActionCode): TableActionResult =
        TableActionResult(
            action = action,
            ok = false,
            reason = "no active session on this socket",
            failure = TableFailureCode.SESSION_GONE,
        )

    /**
     * A typed failed [GameActionResult] for a game request attempted on an unbound socket,
     * carrying [GameFailureCode.SESSION_GONE]: the server was never asked, so the app must offer
     * re-authentication rather than report a decline that did not happen (the convention).
     */
    private fun unboundGameFailure(request: ClientMessage): GameActionResult =
        GameActionResult(
            action = gameActionCodeOf(request),
            ok = false,
            reason = "no active session on this socket",
            failure = GameFailureCode.SESSION_GONE,
        )

    /** Which [GameActionCode] an unbound-socket failure should answer, per game request type. */
    private fun gameActionCodeOf(request: ClientMessage): GameActionCode =
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
            else -> GameActionCode.PLAYER_ACTION
        }

    /** The `requestId` a game request carries, echoed onto its [GameActionResult]. */
    private fun gameRequestId(request: ClientMessage): String? =
        when (request) {
            is JoinGame -> request.requestId
            is WatchGame -> request.requestId
            is QuitMatch -> request.requestId
            is StopWatching -> request.requestId
            is SendPlayerUuid -> request.requestId
            is SendPlayerBoolean -> request.requestId
            is SendPlayerInteger -> request.requestId
            is SendPlayerString -> request.requestId
            is SendPlayerManaType -> request.requestId
            is SendPlayerAction -> request.requestId
            else -> null
        }

    /** Stamps [id] onto a correlated table/game reply; other messages pass through unchanged. */
    private fun ServerMessage.withRequestId(id: String?): ServerMessage =
        when (this) {
            is TableCreated -> copy(requestId = id)
            is TableActionResult -> copy(requestId = id)
            is TableDetail -> copy(requestId = id)
            is TableNotFound -> copy(requestId = id)
            // the game-state read: both arms are correlated, so a miss is as answerable as a
            // hit — an uncorrelated not-found would leave the app's waiter blocked until it timed out.
            is GameStateSnapshot -> copy(requestId = id)
            is GameStateUnavailable -> copy(requestId = id)
            else -> this
        }

    /** The session currently bound to this socket, plus the fields the forwarder/teardown share. */
    private class Bound(
        val live: LiveSession,
    ) {
        /** The resume id, set once the session registers on its first `CONNECTED`. */
        @Volatile var resumeId: String? = null

        /** Set by the forwarder when the outbound channel closes — the upstream session ended. */
        @Volatile var ended: Boolean = false

        @Volatile var forwarder: Job? = null
    }

    /** Drives the post-handshake loop for [ws] until the socket closes or the coroutine is cancelled. */
    public suspend fun run(ws: WebSocketServerSession) {
        coroutineScope {
            var bound: Bound? = null
            try {
                while (true) {
                    val message =
                        try {
                            ws.receiveDeserialized<ClientMessage>()
                        } catch (closed: ClosedReceiveChannelException) {
                            break
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (failure: Exception) {
                            ws.sendSerialized<ServerMessage>(
                                ProtocolError(
                                    ProtocolErrorCode.UNKNOWN_MESSAGE_TYPE,
                                    "Unrecognised or malformed message.",
                                ),
                            )
                            continue
                        }

                    when (message) {
                        is Ping ->
                            ws.sendSerialized<ServerMessage>(Pong(nonce = message.nonce, requestId = message.requestId))

                        is Login ->
                            if (bound != null) {
                                logger.warn("Ignoring Login while a session is already bound to this socket.")
                            } else {
                                val live =
                                    registry.createSession(
                                        newUpstream(),
                                        Credentials(message.username, message.password),
                                    )
                                val newBound = Bound(live)
                                newBound.forwarder =
                                    startForwarder(ws, newBound, firstRequestId = message.requestId, alreadyRegistered = false)
                                bound = newBound
                            }

                        is Resume ->
                            if (bound != null) {
                                logger.warn("Ignoring Resume while a session is already bound to this socket.")
                                ws.sendSerialized<ServerMessage>(
                                    ResumeRejected(
                                        reason = "a session is already active on this socket",
                                        requestId = message.requestId,
                                    ),
                                )
                            } else {
                                val live = registry.resume(message.resumeId)
                                if (live == null) {
                                    // The id may be refused because the entry is
                                    // still *bound* to a socket that silently died (a radio that went
                                    // away delivers no FIN). The app holding this handle has just shown
                                    // it abandoned that socket, so reap the stale session — otherwise
                                    // the `Login` it falls back to opens a second upstream login for the
                                    // same username, which is the leak the smoke saw on every offline
                                    // excursion.
                                    val reaped = registry.evictIfBound(message.resumeId)
                                    ws.sendSerialized<ServerMessage>(
                                        ResumeRejected(
                                            reason =
                                                if (reaped) {
                                                    "the held session was stale and has been torn down; log in again"
                                                } else {
                                                    "unknown or expired resume handle"
                                                },
                                            requestId = message.requestId,
                                        ),
                                    )
                                } else {
                                    val newBound = Bound(live)
                                    newBound.resumeId = message.resumeId
                                    // Positive ack, then re-bind the still-live outbound stream to this socket.
                                    ws.sendSerialized<ServerMessage>(
                                        SessionResumable(resumeId = message.resumeId, requestId = message.requestId),
                                    )
                                    newBound.forwarder =
                                        startForwarder(ws, newBound, firstRequestId = null, alreadyRegistered = true)
                                    bound = newBound
                                }
                            }

                        is Logout -> {
                            val closing = bound
                            bound = null
                            closing?.forwarder?.cancelAndJoin()
                            val id = closing?.resumeId
                            if (id != null) {
                                registry.evict(id)
                            } else {
                                closing?.live?.close()
                            }
                        }

                        is GetServerInfo -> {
                            val info =
                                bound?.live?.serverInfo()
                                    ?: ServerInfo(serverVersion = "unavailable", mainRoomId = null)
                            ws.sendSerialized<ServerMessage>(info.copy(requestId = message.requestId))
                        }

                        // Read-only lobby browse: query the bound session's main room,
                        // map at the mapper boundary, and reply the correlated list. An unbound/not-yet-
                        // connected socket replies an empty list (never an error) — the blocking upstream
                        // reads already ran on Dispatchers.IO inside the upstream session.
                        is GetTables -> {
                            val tables = bound?.live?.tables() ?: emptyList()
                            ws.sendSerialized<ServerMessage>(TableList(tables = tables, requestId = message.requestId))
                        }

                        is GetRoomUsers -> {
                            val users = bound?.live?.roomUsers() ?: emptyList()
                            ws.sendSerialized<ServerMessage>(RoomUserList(users = users, requestId = message.requestId))
                        }

                        is GetGameTypes -> {
                            val gameTypes = bound?.live?.gameTypes() ?: emptyList()
                            ws.sendSerialized<ServerMessage>(
                                GameTypeList(gameTypes = gameTypes, requestId = message.requestId),
                            )
                        }

                        // Table actions: dispatch the action to the bound session's room
                        // via TableRelay and reply the correlated result. An unbound/not-yet-connected
                        // socket replies a typed failure (never an error), mirroring the read side. The
                        // create reply is a TableCreated on success or a failed TableActionResult; the
                        // boolean verbs reply a TableActionResult.
                        is CreateTable -> {
                            val reply =
                                bound?.live?.createTable(message)
                                    ?: unboundFailure(TableActionCode.CREATE)
                            ws.sendSerialized<ServerMessage>(reply.withRequestId(message.requestId))
                        }

                        is JoinTable ->
                            ws.sendSerialized<ServerMessage>(
                                (bound?.live?.joinTable(message) ?: unboundFailure(TableActionCode.JOIN))
                                    .copy(requestId = message.requestId),
                            )

                        is SubmitDeck ->
                            ws.sendSerialized<ServerMessage>(
                                (bound?.live?.submitDeck(message) ?: unboundFailure(TableActionCode.SUBMIT_DECK))
                                    .copy(requestId = message.requestId),
                            )

                        is UpdateDeck ->
                            ws.sendSerialized<ServerMessage>(
                                (bound?.live?.updateDeck(message) ?: unboundFailure(TableActionCode.UPDATE_DECK))
                                    .copy(requestId = message.requestId),
                            )

                        is LeaveTable ->
                            ws.sendSerialized<ServerMessage>(
                                (bound?.live?.leaveTable(message) ?: unboundFailure(TableActionCode.LEAVE))
                                    .copy(requestId = message.requestId),
                            )

                        is RemoveTable ->
                            ws.sendSerialized<ServerMessage>(
                                (bound?.live?.removeTable(message) ?: unboundFailure(TableActionCode.REMOVE))
                                    .copy(requestId = message.requestId),
                            )

                        is StartMatch ->
                            ws.sendSerialized<ServerMessage>(
                                (bound?.live?.startMatch(message) ?: unboundFailure(TableActionCode.START_MATCH))
                                    .copy(requestId = message.requestId),
                            )

                        is WatchTable ->
                            ws.sendSerialized<ServerMessage>(
                                (bound?.live?.watchTable(message) ?: unboundFailure(TableActionCode.WATCH))
                                    .copy(requestId = message.requestId),
                            )

                        // Targeted single-table read: reply the table's detail (summary +
                        // seats) resolved from the bound session's room, or a typed not-found. An
                        // unbound socket replies a not-found rather than an error, mirroring the other
                        // reads — the app surfaces it as a failed refresh, never a hang.
                        is GetTable -> {
                            val reply =
                                bound?.live?.tableDetail(message)
                                    ?: TableNotFound(tableId = message.tableId, reason = "no active session on this socket")
                            ws.sendSerialized<ServerMessage>(reply.withRequestId(message.requestId))
                        }

                        // Targeted game-state read: answered from *this session's* cache of
                        // the snapshots the bridge already relayed to it — there is no upstream verb to
                        // ask, and re-joining a running game does not resync, so this is the only way a
                        // reconnecting client can see the board before the next push. An unbound socket
                        // replies a typed SESSION_GONE; a session with no snapshot for that game replies
                        // NO_STATE_YET. Never an empty board: an all-defaults GameStateView is a legal
                        // snapshot, so a client could not tell it from the truth.
                        is GetGameState -> {
                            val reply =
                                bound?.live?.gameState(message)
                                    ?: GameStateUnavailable(
                                        gameId = message.gameId,
                                        reason = GameStateUnavailableCode.SESSION_GONE,
                                        detail = "no active session on this socket",
                                    )
                            ws.sendSerialized<ServerMessage>(reply.withRequestId(message.requestId))
                        }

                        // In-game requests: join/watch/quit/stop, the five sendPlayerX
                        // answers, and player actions. All share one shape — a game id plus a payload
                        // answered by a bare upstream boolean — so they route through a single
                        // `gameRequest` seam and reply the correlated GameActionResult. An unbound
                        // socket replies a typed SESSION_GONE failure (never an error): a game request
                        // that vanishes leaves the player waiting on a prompt no one will answer.
                        is JoinGame,
                        is WatchGame,
                        is QuitMatch,
                        is StopWatching,
                        is SendPlayerUuid,
                        is SendPlayerBoolean,
                        is SendPlayerInteger,
                        is SendPlayerString,
                        is SendPlayerManaType,
                        is SendPlayerAction,
                        ->
                            ws.sendSerialized<ServerMessage>(
                                (bound?.live?.gameRequest(message) ?: unboundGameFailure(message))
                                    .copy(requestId = gameRequestId(message)),
                            )

                        is UnknownClientMessage -> {
                            // Additive forward-compat: a newer app may send a `type` this
                            // bridge does not know. Per ProtocolVersion's minor-tolerance contract we log
                            // and DROP it — no ProtocolError, no close — so the session is unaffected.
                            logger.info("Dropping unknown client message type '{}' (forward-compat).", message.type)
                        }

                        else ->
                            ws.sendSerialized<ServerMessage>(
                                ProtocolError(
                                    ProtocolErrorCode.UNKNOWN_MESSAGE_TYPE,
                                    "Unhandled message after handshake.",
                                ),
                            )
                    }
                }
            } finally {
                // Park (keep alive) on an unexpected close; disconnect only an unrecoverable/dead one.
                // A Logout is handled inline and leaves `bound` null, so it never reaches this branch.
                withContext(NonCancellable) {
                    val closing = bound
                    if (closing != null) {
                        closing.forwarder?.cancelAndJoin()
                        val id = closing.resumeId
                        when {
                            id == null -> closing.live.close() // never reached CONNECTED → not resumable
                            closing.ended -> registry.evict(id) // upstream ended → drop the entry
                            else -> registry.park(id) // healthy live session, socket dropped → PARK
                        }
                    }
                }
            }
        }
    }

    /**
     * Launches the outbound forwarder: consumes the [Bound.live] durable channel and relays each
     * frame to [ws]. For a fresh login it stamps [firstRequestId] onto the first status and, on the
     * first `CONNECTED`, registers the session and emits its [SessionResumable]. When the channel
     * closes (upstream ended) it flags [Bound.ended]; a socket-write failure ends it quietly (the read
     * loop's break drives teardown). Cancellation (socket drop) leaves the channel open so the session
     * can be parked and later re-bound.
     */
    private fun CoroutineScope.startForwarder(
        ws: WebSocketServerSession,
        bound: Bound,
        firstRequestId: String?,
        alreadyRegistered: Boolean,
    ): Job =
        launch {
            var first = !alreadyRegistered
            try {
                for (message in bound.live.messages) {
                    val framed =
                        if (first && message is SessionStatus) {
                            message.copy(requestId = firstRequestId)
                        } else {
                            message
                        }
                    first = false
                    ws.sendSerialized<ServerMessage>(framed)

                    if (bound.resumeId == null &&
                        message is SessionStatus &&
                        message.state == SessionStateCode.CONNECTED
                    ) {
                        val id = registry.register(bound.live)
                        bound.resumeId = id
                        ws.sendSerialized<ServerMessage>(SessionResumable(resumeId = id))
                    }
                }
                // Channel closed by the pump → the upstream session ended (clean drop / death).
                bound.ended = true
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                // Socket write failed (peer gone). Stop forwarding; teardown will park/evict.
                logger.debug("Outbound forwarder stopped: {}", failure.message)
            }
        }
}
