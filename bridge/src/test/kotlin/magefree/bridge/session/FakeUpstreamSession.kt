package magefree.bridge.session

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import magefree.protocol.CreateTable
import magefree.protocol.GameTypeSummary
import magefree.protocol.GetTable
import magefree.protocol.JoinTable
import magefree.protocol.LeaveTable
import magefree.protocol.RemoveTable
import magefree.protocol.RoomUserSummary
import magefree.protocol.ServerInfo
import magefree.protocol.ServerMessage
import magefree.protocol.StartMatch
import magefree.protocol.SubmitDeck
import magefree.protocol.TableActionCode
import magefree.protocol.TableActionResult
import magefree.protocol.TableNotFound
import magefree.protocol.TableSummary
import magefree.protocol.UpdateDeck
import magefree.protocol.WatchTable
import java.util.concurrent.atomic.AtomicInteger

/**
 * A scripted [UpstreamSession] for hermetic tests — no XMage server needed. [connect] emits the
 * given [script] in order, then relays any messages later pushed via [emit], staying open until the
 * collection is cancelled (socket close / [disconnect]). This lets a test drive **every**
 * `SessionStateCode` path (and the mapped-push/`GetServerInfo` paths) through the real WebSocket +
 * [SessionCoordinator] plumbing, and — for story 0023 — prove a parked session survives an app-socket
 * drop and continues on the resumed socket **with no second `connect`**.
 *
 * The scripted messages are the *upstream* view; the coordinator stamps the login's `requestId` onto
 * the first status, so scripts should carry a null `requestId`. [serverInfo] backs `GetServerInfo`.
 */
public class FakeUpstreamSession(
    private val script: List<ServerMessage>,
    private val scriptedServerInfo: ServerInfo? = null,
    private val scriptedSessionId: String? = "fake-session-id",
    private val scriptedTables: List<TableSummary> = emptyList(),
    private val scriptedRoomUsers: List<RoomUserSummary> = emptyList(),
    private val scriptedGameTypes: List<GameTypeSummary> = emptyList(),
    private val scriptedCreateTable: ServerMessage? = null,
    private val scriptedTableDetail: ServerMessage? = null,
    private val scriptedActionOk: Boolean = true,
) : UpstreamSession {
    /** The last table-action request the coordinator dispatched, captured for assertions (story 0036). */
    @Volatile
    public var lastTableRequest: Any? = null
        private set

    private val disconnectSignal = CompletableDeferred<Unit>()
    private val extra = Channel<ServerMessage>(capacity = Channel.UNLIMITED)

    /** How many times [connect] has been collected — a resume must reuse the session, so this stays 1. */
    public val connectCount: AtomicInteger = AtomicInteger(0)

    /** How many times the registry's keepalive [ping] has been invoked. */
    public val pingCount: AtomicInteger = AtomicInteger(0)

    /**
     * Whether the upstream still answers a keepalive probe. Set to `false` to stand in for XMage having
     * dropped the session underneath the bridge — its `UserManagerImpl` expiring an idle user, a server
     * restart, an upstream network partition. Nothing about the app socket changes; only the upstream
     * stops being alive, which is the one-sided failure story 0050 defect A is about.
     */
    @Volatile
    public var upstreamAlive: Boolean = true

    /** The credentials the coordinator connected with, captured for assertions. */
    @Volatile
    public var lastCredentials: Credentials? = null
        private set

    /** True once [disconnect] has been invoked (e.g. by socket close, `Logout`, or eviction). */
    public val disconnectCalled: Boolean
        get() = disconnectSignal.isCompleted

    override fun connect(credentials: Credentials): Flow<ServerMessage> =
        flow {
            connectCount.incrementAndGet()
            lastCredentials = credentials
            script.forEach { emit(it) }
            // Stay hot like a real connected session: relay any later-pushed messages until cancelled.
            for (message in extra) emit(message)
        }

    /** Pushes an additional upstream message onto a live [connect] stream (post-script), e.g. after resume. */
    public suspend fun emit(message: ServerMessage) {
        extra.send(message)
    }

    override suspend fun serverInfo(): ServerInfo? = scriptedServerInfo

    override suspend fun tables(): List<TableSummary> = scriptedTables

    override suspend fun roomUsers(): List<RoomUserSummary> = scriptedRoomUsers

    override suspend fun gameTypes(): List<GameTypeSummary> = scriptedGameTypes

    override suspend fun createTable(request: CreateTable): ServerMessage {
        lastTableRequest = request
        return scriptedCreateTable ?: TableActionResult(TableActionCode.CREATE, ok = scriptedActionOk)
    }

    override suspend fun joinTable(request: JoinTable): TableActionResult = recordedResult(request, TableActionCode.JOIN)

    override suspend fun submitDeck(request: SubmitDeck): TableActionResult = recordedResult(request, TableActionCode.SUBMIT_DECK)

    override suspend fun updateDeck(request: UpdateDeck): TableActionResult = recordedResult(request, TableActionCode.UPDATE_DECK)

    override suspend fun leaveTable(request: LeaveTable): TableActionResult = recordedResult(request, TableActionCode.LEAVE)

    override suspend fun removeTable(request: RemoveTable): TableActionResult = recordedResult(request, TableActionCode.REMOVE)

    override suspend fun startMatch(request: StartMatch): TableActionResult = recordedResult(request, TableActionCode.START_MATCH)

    override suspend fun watchTable(request: WatchTable): TableActionResult = recordedResult(request, TableActionCode.WATCH)

    override suspend fun tableDetail(request: GetTable): ServerMessage {
        lastTableRequest = request
        return scriptedTableDetail ?: TableNotFound(tableId = request.tableId, reason = "no such table in the room")
    }

    private fun recordedResult(
        request: Any,
        action: TableActionCode,
    ): TableActionResult {
        lastTableRequest = request
        return TableActionResult(action = action, ok = scriptedActionOk)
    }

    override suspend fun ping(): Boolean {
        pingCount.incrementAndGet()
        return upstreamAlive && !disconnectSignal.isCompleted
    }

    override suspend fun sessionId(): String? = scriptedSessionId

    override suspend fun disconnect() {
        disconnectSignal.complete(Unit)
    }

    /** Suspends until [disconnect] is called, for asserting teardown after a socket close/eviction. */
    public suspend fun awaitDisconnect(): Unit = disconnectSignal.await()
}
