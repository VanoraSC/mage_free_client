package magefree.network.table

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
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
import magefree.protocol.TableNotFound
import magefree.protocol.TableSummary
import magefree.protocol.UpdateDeck
import magefree.protocol.WatchTable
import java.util.UUID

/**
 * The production [TableClient] (story 0037), over the same [BridgeClient] singleton `LobbyClient` rides.
 *
 * **Actions** mint a `requestId`, send the matching 0036 request via [BridgeClient.request] (the erased
 * `request<ServerMessage>` seam, so no `:protocol` type crosses the ABI), and map the correlated reply:
 * a [TableCreated] → [TableRef]; a [TableActionResult] with `ok = true` → success, `ok = false` →
 * [Result.failure] carrying a [TableActionFailure] with the server's reason (never a silent drop). A
 * transport failure (no session / timeout / drop, thrown by `request`) is likewise captured as a failed
 * [Result] rather than propagated — the caller always gets a [Result].
 *
 * **[observeTable]** merges three sources into a single folding collector (so the held state is mutated
 * by one coroutine, race-free): the [ServerPushSource] stream folded by [TableEventFold] for this table;
 * a re-sync trigger on each return to [ConnectionState.Connected] (a 0023 resume) that re-emits the held
 * state — mirroring the lobby's non-destructive refresh so a reconnect never strands the seat; and the
 * results of [refreshTable] reads (story 0040), which are where [TableState.seats] and the server's
 * readiness actually come from. Reads are triggered on open, on each table-lifecycle push for this table
 * and after a resume — never on a timer, so an unobserved room costs nothing.
 *
 * @param newRequestId how a correlation id is minted (a UUID in production; overridable so a test can
 *   assert the exact id sent).
 */
internal class DefaultTableClient(
    private val bridgeClient: BridgeClient,
    private val pushSource: ServerPushSource,
    private val connectionState: StateFlow<@JvmSuppressWildcards ConnectionState>,
    private val newRequestId: () -> String = { UUID.randomUUID().toString() },
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
    ): Result<Unit> =
        unitAction { id ->
            bridgeClient.request(
                JoinTable(
                    tableId = tableId,
                    seatName = seatName,
                    deck = deck.toProtocolDeckList(),
                    password = password,
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

            // The seat/readiness re-read trigger (story 0040). Conflated: several triggers arriving
            // while a read is in flight collapse into one follow-up read — no queue, no polling.
            val refreshes = Channel<Unit>(Channel.CONFLATED)

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
                        // A 0023 resume completed: re-emit the held state so the seat re-syncs (the
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
                }.launchIn(this)

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

    private fun <T> TableActionResult.asFailure(): Result<T> = Result.failure(TableActionFailure(reason))

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
     * A `false → true`-into-[ConnectionState.Connected] edge (a 0023 resume completing). The initial
     * `Connected` (nothing before it) is skipped — the seed already covers the current state; only a
     * *return* to Connected after a drop re-syncs.
     */
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
