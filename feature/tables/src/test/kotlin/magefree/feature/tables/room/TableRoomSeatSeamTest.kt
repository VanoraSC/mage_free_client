package magefree.feature.tables.room

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import magefree.feature.tables.FakeDeckRepository
import magefree.feature.tables.TableRole
import magefree.model.ConnectionState
import magefree.network.fake.FakeBridgeClient
import magefree.network.table.SeatPlayerType
import magefree.network.table.TableClients
import magefree.network.table.TableServerState
import magefree.network.table.TableState
import magefree.protocol.ClientMessage
import magefree.protocol.GetTable
import magefree.protocol.SeatPlayerTypeCode
import magefree.protocol.ServerMessage
import magefree.protocol.SkillLevelCode
import magefree.protocol.StartMatch
import magefree.protocol.TableActionCode
import magefree.protocol.TableActionResult
import magefree.protocol.TableDetail
import magefree.protocol.TableNotFound
import magefree.protocol.TableSeatSummary
import magefree.protocol.TableStateCode
import magefree.protocol.TableSummary
import magefree.protocol.TableUpdated
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The regression guard for story 0040's defect, driven **through the client seam**.
 *
 * `TableRoomViewModelTest` injects a finished `TableState` through a `FakeTableClient`, which is exactly
 * why the original defect shipped green: it proved the start gate's *logic* while the gate's *input* was
 * unreachable — `TableState.seats` was only ever filled by folding a `SeatUpdated` push that nothing
 * sends, so the room showed zero seats and the host's start control could never enable.
 *
 * This test removes that blind spot by wiring the **real** `TableClient` ([TableClients.overBridge]) over
 * a [FakeBridgeClient] that answers the wire request/response exchange, and driving the same
 * [TableRoomViewModel]. Nothing between the bridge's reply and the rendered state is faked: the request
 * the client sends, the correlation, the wire→app-schema mapping, the fold, and the gate are all
 * production code.
 *
 * **Why it fails if seats become unreachable again.** The only seat data anywhere in this test is the
 * `TableDetail` the fake bridge replies with, and the only way it can reach the assertions is the
 * production path. If the client stops issuing a `GetTable` when the room opens, stops re-reading on a
 * table push, stops mapping the reply, stops folding it into `TableState.seats`, or the gate stops
 * reading the server's `READY_TO_START`, then `seats` stays empty and `canStart` stays false — and these
 * assertions fail. No push this test does not send (least of all a `SeatUpdated`, which has no producer)
 * can rescue it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TableRoomSeatSeamTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * The scripted bridge side of the socket: it answers the two exchanges this room performs — the
     * targeted table read and the host's start — and records both, so the test can pin *when* the room
     * reads the table (and that the start really reached the server).
     */
    private class Bridge(
        private val details: List<ServerMessage>,
    ) {
        val tableReads: MutableList<String> = mutableListOf()
        val started: MutableList<String> = mutableListOf()

        fun reply(message: ClientMessage): ServerMessage =
            when (message) {
                is GetTable -> {
                    tableReads += message.tableId
                    // Replay the last scripted detail once the script runs out (a settled table).
                    details[minOf(tableReads.size - 1, details.size - 1)]
                }

                is StartMatch -> {
                    started += message.tableId
                    TableActionResult(action = TableActionCode.START_MATCH, ok = true)
                }

                else -> TableNotFound(tableId = TABLE_ID, reason = "unexpected request: $message")
            }
    }

    private fun summary(
        state: TableStateCode,
        filled: Int,
    ) = TableSummary(
        tableId = TABLE_ID,
        name = "Duel Night",
        controllerName = "pete",
        gameType = "Two Player Duel",
        deckType = "Constructed - Freeform Unlimited",
        state = state,
        seatsFilled = filled,
        seatsTotal = 2,
        isTournament = false,
        isRated = false,
        isPassworded = false,
        isLimited = false,
        skillLevel = SkillLevelCode.CASUAL,
        createdAtEpochMs = 0L,
    )

    /** The host is seated; the AI seat is still open — the server is WAITING. */
    private fun waiting() =
        TableDetail(
            table = summary(TableStateCode.WAITING, filled = 1),
            seats =
                listOf(
                    TableSeatSummary(index = 0, playerName = "pete", playerType = SeatPlayerTypeCode.HUMAN, occupied = true),
                    TableSeatSummary(index = 1, playerName = null, playerType = SeatPlayerTypeCode.COMPUTER_MAD, occupied = false),
                ),
        )

    /** Both seats filled — the server's own READY_TO_START, the rule `startMatch` enforces upstream. */
    private fun ready() =
        TableDetail(
            table = summary(TableStateCode.READY_TO_START, filled = 2),
            seats =
                listOf(
                    TableSeatSummary(index = 0, playerName = "pete", playerType = SeatPlayerTypeCode.HUMAN, occupied = true),
                    TableSeatSummary(index = 1, playerName = "Computer", playerType = SeatPlayerTypeCode.COMPUTER_MAD, occupied = true),
                ),
        )

    /** The room, wired to the production `TableClient` over [bridge]; the socket double is returned too. */
    private fun room(bridge: Bridge): Pair<TableRoomViewModel, FakeBridgeClient> {
        val socket = FakeBridgeClient(responder = bridge::reply)
        val client = TableClients.overBridge(socket, MutableStateFlow(ConnectionState.Connected))
        return TableRoomViewModel(client, FakeDeckRepository()) to socket
    }

    @Test
    fun theRoomShowsTheTablesRealSeatsAndTheStartGateEnablesOnTheServersReadiness() =
        runTest {
            val bridge = Bridge(listOf(waiting(), ready()))
            val (vm, socket) = room(bridge)

            vm.observe(TABLE_ID, TableState(tableId = TABLE_ID, optionsSummary = "Two Player Duel"), TableRole.Host)

            // 1. Opening the room read the table — with no push of any kind, the seats are on screen.
            assertEquals(listOf(TABLE_ID), bridge.tableReads)
            val waitingState = vm.uiState.value
            assertEquals(2, waitingState.seats.size)
            assertEquals(listOf("pete", null), waitingState.seats.map { it.name })
            assertEquals(listOf(true, false), waitingState.seats.map { it.isOccupied })
            assertEquals(SeatPlayerType.ComputerMad, waitingState.seats[1].playerType)
            assertEquals(1, waitingState.seatsFilled)
            assertEquals(TableServerState.Waiting, waitingState.serverState)

            // 2. A seat is still open, so the host cannot start — and pressing the control does nothing.
            assertFalse("a half-filled table must not enable the start control", waitingState.canStart)
            vm.startMatch()
            assertTrue("the gate must actually block the action", bridge.started.isEmpty())

            // 3. The AI takes the open seat. Upstream sends no per-seat event, so the room learns it by
            //    re-reading the table on the next table-lifecycle push it already receives.
            socket.emitPush(TableUpdated(tableId = TABLE_ID, isOwner = true))

            assertEquals(listOf(TABLE_ID, TABLE_ID), bridge.tableReads)
            val readyState = vm.uiState.value
            assertEquals(listOf("pete", "Computer"), readyState.seats.map { it.name })
            assertEquals(2, readyState.seatsFilled)
            assertEquals(TableServerState.ReadyToStart, readyState.serverState)

            // 4. The server says every seat is filled → the host may start, and the action reaches it.
            assertTrue("the server reports READY_TO_START, so the host must be able to start", readyState.canStart)
            vm.startMatch()
            assertEquals(listOf(TABLE_ID), bridge.started)
        }

    @Test
    fun aRoomThatIsNotObservedIssuesNoReads() =
        runTest {
            // The refresh is bound to the observation: no polling loop exists to keep running when the
            // room is not on screen. Constructing the ViewModel alone must touch nothing.
            val bridge = Bridge(listOf(waiting()))
            room(bridge)

            assertTrue("an unobserved room must not read the table", bridge.tableReads.isEmpty())
        }

    private companion object {
        const val TABLE_ID = "t-1"
    }
}
