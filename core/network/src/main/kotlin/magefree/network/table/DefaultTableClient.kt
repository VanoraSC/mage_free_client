package magefree.network.table

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import magefree.decks.model.Deck
import magefree.model.ConnectionState
import magefree.network.BridgeClient
import magefree.network.ServerPushSource
import magefree.protocol.CreateTable
import magefree.protocol.JoinTable
import magefree.protocol.LeaveTable
import magefree.protocol.RemoveTable
import magefree.protocol.ServerMessage
import magefree.protocol.StartMatch
import magefree.protocol.SubmitDeck
import magefree.protocol.TableActionResult
import magefree.protocol.TableCreated
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
 * **[observeTable]** merges two sources into a single folding collector (so the held state is mutated by
 * one coroutine, race-free): the [ServerPushSource] stream folded by [TableEventFold] for this table, and
 * a re-sync trigger on each return to [ConnectionState.Connected] (a 0023 resume) that re-emits the held
 * state — mirroring the lobby's non-destructive refresh so a reconnect never strands the seat.
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

    override fun observeTable(
        tableId: String,
        seed: TableState,
    ): Flow<TableState> =
        callbackFlow {
            var state = seed
            trySend(state)

            // Feed both sources as intents into a single collector: `merge` serialises them downstream,
            // so the held `state` is read/written by exactly one coroutine — no lock, no race.
            val pushes = pushSource.serverPushes.map<ServerMessage, Intent> { Intent.Push(it) }
            val resyncs = connectionState.reEstablishments().map { Intent.Resync }

            merge(pushes, resyncs)
                .onEach { intent ->
                    when (intent) {
                        is Intent.Push -> {
                            val next = TableEventFold.fold(state, intent.message)
                            if (next != null && next != state) {
                                state = next
                                trySend(next)
                            }
                        }
                        // A 0023 resume completed: re-emit the held state so the seat re-syncs (the
                        // lobby's non-destructive-refresh analogue), even though the state is unchanged.
                        Intent.Resync -> trySend(state)
                    }
                }.launchIn(this)

            awaitClose { }
        }

    /** An `observeTable` input: a server push to fold, or a resume that re-syncs the current state. */
    private sealed interface Intent {
        data class Push(
            val message: ServerMessage,
        ) : Intent

        data object Resync : Intent
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
