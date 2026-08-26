package magefree.network.table

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import magefree.decks.model.Deck
import magefree.model.ConnectionState
import magefree.network.BridgeClient
import magefree.network.ServerPushSource
import magefree.protocol.CreateTable
import magefree.protocol.GetTable
import magefree.protocol.JoinTable
import magefree.protocol.LeaveTable
import magefree.protocol.RemoveTable
import magefree.protocol.ServerMessage
import magefree.protocol.StartMatch
import magefree.protocol.SubmitDeck
import magefree.protocol.TableActionResult
import magefree.protocol.TableCreated
import magefree.protocol.TableDetail
import magefree.protocol.TableFailureCode
import magefree.protocol.TableNotFound
import magefree.protocol.TableSummary
import magefree.protocol.UpdateDeck
import magefree.protocol.WatchTable
import kotlin.uuid.Uuid

/**
 * The production [TableClient], over the same [BridgeClient] singleton `LobbyClient` rides.
 *
 * **Actions** mint a `requestId`, send the matching table request via [BridgeClient.request] (the erased
 * `request<ServerMessage>` seam, so no `:protocol` type crosses the ABI), and map the correlated reply:
 * a [TableCreated] → [TableRef]; a [TableActionResult] with `ok = true` → success, `ok = false` →
 * [Result.failure] carrying a [TableActionFailure] with the server's reason (never a silent drop). A
 * transport failure (no session / timeout / drop, thrown by `request`) is likewise captured as a failed
 * [Result] rather than propagated — the caller always gets a [Result].
 *
 * **[observeTable]** merges three sources into a single folding collector (so the held state is mutated
 * by one coroutine, race-free): the [ServerPushSource] stream folded by [TableEventFold] for this table;
 * a re-sync trigger on each return to [ConnectionState.Connected] (a resume) that re-emits the held
 * state — mirroring the lobby's non-destructive refresh so a reconnect never strands the seat; and the
 * results of [refreshTable] reads, which are where [TableState.seats] and the server's
 * readiness actually come from. Reads are triggered on open, on each table-lifecycle push for this table,
 * after a resume, and — because upstream tells a seated player nothing when someone else takes a seat —
 * every [SEAT_REFRESH_INTERVAL_MS] while the table is still seating. That poll is launched **inside** the
 * flow, so it is scoped to the collection: an unobserved room polls nothing, and it stops for good once
 * [TableState.isPastSeating].
 *
 * @param newRequestId how a correlation id is minted (a UUID in production; overridable so a test can
 *   assert the exact id sent).
 */
internal class DefaultTableClient(
    private val bridgeClient: BridgeClient,
    private val pushSource: ServerPushSource,
    private val connectionState: StateFlow<@JvmSuppressWildcards ConnectionState>,
    private val newRequestId: () -> String = { Uuid.random().toString() },
) : TableClient {
    override suspend fun createTable(options: CreateTableOptions): Result<TableRef> =
        action { id ->
            val request = CreateTable(options = options.toProtocol(), requestId = id)
            when (val reply = bridgeClient.request<ServerMessage>(request, id)) {
                is TableCreated -> Result.success(reply.table.toRef())
                is TableActionResult -> reply.asFailure()
                else -> unexpected(reply)
            }
        }

    override suspend fun joinTable(
        tableId: String,
        seatName: String,
        deck: Deck,
        password: String?,
        playerType: SeatPlayerType,
    ): Result<Unit> =
        unitAction { id ->
            bridgeClient.request(
                JoinTable(
                    tableId = tableId,
                    seatName = seatName,
                    deck = deck.toProtocolDeckList(),
                    password = password,
                    // the seat's kind travels with the join — the `JoinTable` and the
                    // bridge's `TableRelay` already carry it, so seating an AI needs no wire change.
                    playerType = playerType.toCode(),
                    requestId = id,
                ),
                id,
            )
        }

    override suspend fun submitDeck(
        tableId: String,
        deck: Deck,
    ): Result<Unit> =
        unitAction { id ->
            bridgeClient.request(SubmitDeck(tableId = tableId, deck = deck.toProtocolDeckList(), requestId = id), id)
        }

    override suspend fun updateDeck(
        tableId: String,
        deck: Deck,
    ): Result<Unit> =
        unitAction { id ->
            bridgeClient.request(UpdateDeck(tableId = tableId, deck = deck.toProtocolDeckList(), requestId = id), id)
        }

    override suspend fun leaveTable(tableId: String): Result<Unit> =
        unitAction { id -> bridgeClient.request(LeaveTable(tableId = tableId, requestId = id), id) }

    override suspend fun removeTable(tableId: String): Result<Unit> =
        unitAction { id -> bridgeClient.request(RemoveTable(tableId = tableId, requestId = id), id) }

    override suspend fun startMatch(tableId: String): Result<Unit> =
        unitAction { id -> bridgeClient.request(StartMatch(tableId = tableId, requestId = id), id) }

    override suspend fun watchTable(tableId: String): Result<Unit> =
        unitAction { id -> bridgeClient.request(WatchTable(tableId = tableId, requestId = id), id) }

    override suspend fun refreshTable(tableId: String): Result<TableDetails> =
        action { id ->
            when (val reply = bridgeClient.request<ServerMessage>(GetTable(tableId = tableId, requestId = id), id)) {
                is TableDetail -> Result.success(reply.toDetails())
                is TableNotFound -> Result.failure(TableNotFoundFailure(tableId = reply.tableId, reason = reply.reason))
                else -> unexpected(reply)
            }
        }

    override fun observeTable(
        tableId: String,
        seed: TableState,
    ): Flow<TableState> =
        callbackFlow {
            var state = seed
            trySend(state)

            // The single seat/readiness re-read trigger every source funnels into.
            // CONFLATED is what coalesces them: at most one token is ever pending, and the reader below
            // is sequential (it suspends inside the read), so a tick that lands while a push-triggered
            // read is in flight collapses into one follow-up read instead of a second concurrent one.
            val refreshes = Channel<Unit>(Channel.CONFLATED)

            // Written only by the collector below, read only by the poller: the point at which seats can
            // no longer change and the poll should stop for good.
            val pastSeating = MutableStateFlow(seed.isPastSeating)

            // Feed every source as an intent into a single collector: `merge` serialises them
            // downstream, so the held `state` is read/written by exactly one coroutine — no lock, no
            // race. The detail read suspends inside its *own* branch, so a slow read never delays a push.
            val pushes = pushSource.serverPushes.map<ServerMessage, Intent> { Intent.Push(it) }
            val resyncs = connectionState.reEstablishments().map { Intent.Resync }
            val details =
                refreshes
                    .receiveAsFlow()
                    .mapNotNull { refreshTable(tableId).getOrNull()?.let { Intent.Detail(it) } }

            merge(pushes, resyncs, details)
                .onEach { intent ->
                    when (intent) {
                        is Intent.Push -> {
                            val next = TableEventFold.fold(state, intent.message)
                            if (next != null && next != state) {
                                state = next
                                trySend(next)
                            }
                            // The table changed on the server; re-read its seats/state. This is what
                            // replaces the per-seat push XMage never sends.
                            if (TableEventFold.triggersDetailRefresh(tableId, intent.message)) {
                                refreshes.trySend(Unit)
                            }
                        }
                        // A resume completed: re-emit the held state so the seat re-syncs (the
                        // lobby's non-destructive-refresh analogue) and re-read the table, since seats
                        // may have moved while the socket was down.
                        Intent.Resync -> {
                            trySend(state)
                            refreshes.trySend(Unit)
                        }

                        is Intent.Detail -> {
                            val next = state.withDetails(intent.details)
                            if (next != state) {
                                state = next
                                trySend(next)
                            }
                        }
                    }
                    pastSeating.value = state.isPastSeating
                }.launchIn(this)

            // The while-observed poll. It is launched *inside* this flow's scope, so it lives and dies
            // with the collection: when the room leaves the screen and the collector is cancelled, the
            // polling stops with it — there is no app-scope timer, and an unobserved table costs nothing.
            //
            // It is needed because the pushes above do not cover every seat change: upstream's
            // `TableController.joinTable` tells already-seated players *nothing* when a human takes a
            // seat, so a host waiting for an opponent would otherwise never re-read and never see the
            // table become ready. The desktop client's waiting room polls for exactly this reason.
            launch {
                while (!pastSeating.value) {
                    delay(SEAT_REFRESH_INTERVAL_MS)
                    if (pastSeating.value) break
                    refreshes.trySend(Unit)
                }
            }

            // Seed the seats: read the table once as the room opens.
            refreshes.trySend(Unit)

            awaitClose { refreshes.close() }
        }

    /**
     * An `observeTable` input: a server push to fold, a resume that re-syncs the current state, or a
     * completed table read carrying the current seats/server state.
     */
    private sealed interface Intent {
        data class Push(
            val message: ServerMessage,
        ) : Intent

        data object Resync : Intent

        data class Detail(
            val details: TableDetails,
        ) : Intent
    }

    // --- action plumbing -------------------------------------------------------------------------

    /**
     * Run one table action: mint an id, run [block], and capture any transport throw (no session /
     * timeout / drop from `request`) as a failed [Result] — so the caller always gets a [Result], never
     * an unhandled throw. [CancellationException] is re-thrown so structured cancellation is preserved.
     */
    private inline fun <T> action(block: (id: String) -> Result<T>): Result<T> =
        try {
            block(newRequestId())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

    /** A table action whose reply is a bare [TableActionResult] (`ok`), mapping it to `Result<Unit>`. */
    private inline fun unitAction(block: (id: String) -> ServerMessage): Result<Unit> =
        action { id ->
            when (val reply = block(id)) {
                is TableActionResult -> reply.asUnit()
                else -> unexpected(reply)
            }
        }

    private fun TableActionResult.asUnit(): Result<Unit> = if (ok) Result.success(Unit) else asFailure()

    /**
     * A declined action becomes a typed failure — and, the *right* typed failure: a
     * [magefree.protocol.TableFailureCode.SESSION_GONE] reply means the bridge had no session to act
     * through, so the action never reached the server and the caller must offer re-authentication rather
     * than repeat the server's (non-existent) refusal. Anything else stays a plain [TableActionFailure],
     * including a reply from an older bridge that does not send the field at all.
     */
    private fun <T> TableActionResult.asFailure(): Result<T> =
        Result.failure(
            if (failure == TableFailureCode.SESSION_GONE) SessionGoneFailure(reason) else TableActionFailure(reason),
        )

    private fun <T> unexpected(reply: ServerMessage): Result<T> =
        Result.failure(TableActionFailure("table: unexpected reply ${reply::class.simpleName}"))

    private fun TableSummary.toRef(): TableRef =
        TableRef(
            tableId = tableId,
            name = name,
            gameType = gameType,
            deckType = deckType,
            seatsFilled = seatsFilled,
            seatsTotal = seatsTotal,
        )

    /**
     * A `false → true`-into-[ConnectionState.Connected] edge (a resume completing). The initial
     * `Connected` (nothing before it) is skipped — the seed already covers the current state; only a
     * *return* to Connected after a drop re-syncs.
     */
    internal companion object {
        /**
         * How often an **observed** table is re-read while it is still seating.
         *
         * Three seconds is paced to the server, not to the UI: `GamesRoomImpl` rebuilds the lobby's
         * table-list snapshot — the very list a `GetTable` is resolved from — on a **two-second** timer,
         * so reading faster than that cannot surface anything sooner and just burns requests. Three
         * seconds stays just behind that tick, bounding the visible lag at roughly one interval.
         */
        internal const val SEAT_REFRESH_INTERVAL_MS: Long = 3_000L
    }

    private fun StateFlow<ConnectionState>.reEstablishments(): Flow<Unit> =
        flow {
            var previous: ConnectionState? = null
            collect { current ->
                if (current == ConnectionState.Connected &&
                    previous != null &&
                    previous != ConnectionState.Connected
                ) {
                    emit(Unit)
                }
                previous = current
            }
        }
}
