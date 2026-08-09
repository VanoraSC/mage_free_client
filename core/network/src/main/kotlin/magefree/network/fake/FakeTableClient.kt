package magefree.network.fake

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.onStart
import magefree.decks.model.Deck
import magefree.network.table.CreateTableOptions
import magefree.network.table.Seat
import magefree.network.table.SeatPlayerType
import magefree.network.table.TableClient
import magefree.network.table.TableDetails
import magefree.network.table.TableRef
import magefree.network.table.TableServerState
import magefree.network.table.TableState

/**
 * A scriptable [TableClient] test double (story 0037) for hermetic tests of downstream (0038) code — no
 * bridge, no `:protocol`. Each verb records its call and returns its configured [Result]; [observeTable]
 * replays [tableStates] (emitted first) and then anything pushed through [emitTableState], so a test can
 * drive a table through its lifecycle without a socket.
 *
 * Every result defaults to success; set the matching `…Result` to a [Result.failure] (e.g. a
 * [magefree.network.table.TableActionFailure]) to exercise a caller's error path.
 */
class FakeTableClient(
    var createResult: Result<TableRef> = Result.success(DEFAULT_REF),
    var joinResult: Result<Unit> = Result.success(Unit),
    var submitResult: Result<Unit> = Result.success(Unit),
    var updateResult: Result<Unit> = Result.success(Unit),
    var leaveResult: Result<Unit> = Result.success(Unit),
    var removeResult: Result<Unit> = Result.success(Unit),
    var startResult: Result<Unit> = Result.success(Unit),
    var watchResult: Result<Unit> = Result.success(Unit),
    var refreshResult: Result<TableDetails> = Result.success(DEFAULT_DETAILS),
) : TableClient {
    /** The verbs invoked, in order, for assertion (`"create"`, `"join:t-1"`, …). */
    val calls: MutableList<String> = mutableListOf()

    private val emitted = MutableSharedFlow<TableState>(replay = 0, extraBufferCapacity = 64)

    /** States [observeTable] replays before it forwards live [emitTableState]s (a scripted seed path). */
    var tableStates: List<TableState> = emptyList()

    override suspend fun createTable(options: CreateTableOptions): Result<TableRef> {
        calls += "create"
        return createResult
    }

    override suspend fun joinTable(
        tableId: String,
        seatName: String,
        deck: Deck,
        password: String?,
    ): Result<Unit> {
        calls += "join:$tableId"
        return joinResult
    }

    override suspend fun submitDeck(
        tableId: String,
        deck: Deck,
    ): Result<Unit> {
        calls += "submit:$tableId"
        return submitResult
    }

    override suspend fun updateDeck(
        tableId: String,
        deck: Deck,
    ): Result<Unit> {
        calls += "update:$tableId"
        return updateResult
    }

    override suspend fun leaveTable(tableId: String): Result<Unit> {
        calls += "leave:$tableId"
        return leaveResult
    }

    override suspend fun removeTable(tableId: String): Result<Unit> {
        calls += "remove:$tableId"
        return removeResult
    }

    override suspend fun startMatch(tableId: String): Result<Unit> {
        calls += "start:$tableId"
        return startResult
    }

    override suspend fun watchTable(tableId: String): Result<Unit> {
        calls += "watch:$tableId"
        return watchResult
    }

    override suspend fun refreshTable(tableId: String): Result<TableDetails> {
        calls += "refresh:$tableId"
        return refreshResult
    }

    override fun observeTable(
        tableId: String,
        seed: TableState,
    ): Flow<TableState> = emitted.onStart { tableStates.forEach { emit(it) } }

    /** Push a [TableState] to live [observeTable] collectors, as the real fold would on a table event. */
    suspend fun emitTableState(state: TableState) {
        emitted.emit(state)
    }

    private companion object {
        val DEFAULT_REF =
            TableRef(
                tableId = "t-1",
                name = "table",
                gameType = "Two Player Duel",
                deckType = "Constructed",
                seatsFilled = 1,
                seatsTotal = 2,
            )

        /** A two-seat table still waiting for players — the neutral starting point for a room test. */
        val DEFAULT_DETAILS =
            TableDetails(
                tableId = "t-1",
                name = "table",
                gameType = "Two Player Duel",
                deckType = "Constructed",
                serverState = TableServerState.Waiting,
                seats =
                    listOf(
                        Seat(index = 0, playerType = SeatPlayerType.Human, isOccupied = false),
                        Seat(index = 1, playerType = SeatPlayerType.Human, isOccupied = false),
                    ),
            )
    }
}
