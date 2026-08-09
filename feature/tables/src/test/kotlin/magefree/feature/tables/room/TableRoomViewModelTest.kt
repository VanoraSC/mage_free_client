package magefree.feature.tables.room

import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import magefree.feature.tables.FakeDeckRepository
import magefree.feature.tables.TableRole
import magefree.network.fake.FakeTableClient
import magefree.network.table.MatchStarting
import magefree.network.table.Seat
import magefree.network.table.SeatPlayerType
import magefree.network.table.TableActionFailure
import magefree.network.table.TablePhase
import magefree.network.table.TableServerState
import magefree.network.table.TableState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Hermetic coverage of [TableRoomViewModel] over 0037's [FakeTableClient] — no bridge, no `:protocol`. It
 * folds a scripted [TableState] (seats filling / phase transitions), pins host-start gating on the
 * server's own readiness, the leave/remove close signals, the match-starting terminal state, and the
 * spectator `watchTable` subscription.
 *
 * These tests inject a finished [TableState], so they verify the room's *composition* of the gate — not
 * that the gate's input is reachable. That reachability (the story-0040 defect) is pinned separately by
 * `TableRoomSeatSeamTest`, which drives this same ViewModel through the real client over a fake bridge.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TableRoomViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(
        client: FakeTableClient = FakeTableClient(),
        repo: FakeDeckRepository = FakeDeckRepository(),
    ) = TableRoomViewModel(client, repo)

    private fun seed() = TableState(tableId = "t-1", optionsSummary = "Two Player Duel")

    @Test
    fun foldsScriptedSeatAndPhaseTransitions() =
        runTest {
            val client = FakeTableClient()
            val vm = viewModel(client)
            vm.observe("t-1", seed(), TableRole.Host)

            // No seats yet.
            assertTrue(
                vm.uiState.value.seats
                    .isEmpty(),
            )

            // The table is read: two slots, one taken.
            client.emitTableState(
                TableState(
                    tableId = "t-1",
                    seats =
                        listOf(
                            Seat(index = 0, name = "You", isOccupied = true),
                            Seat(index = 1, playerType = SeatPlayerType.ComputerMad, isOccupied = false),
                        ),
                    serverState = TableServerState.Waiting,
                ),
            )
            assertEquals(2, vm.uiState.value.seats.size)
            assertEquals(1, vm.uiState.value.seatsFilled)
            assertFalse(vm.uiState.value.isLoading)

            // Both seats fill and construction begins.
            client.emitTableState(
                TableState(
                    tableId = "t-1",
                    seats =
                        listOf(
                            Seat(index = 0, name = "You", isOccupied = true),
                            Seat(index = 1, name = "Rival", isOccupied = true),
                        ),
                    serverState = TableServerState.Constructing,
                    phase = TablePhase.Constructing,
                ),
            )
            assertEquals(2, vm.uiState.value.seatsFilled)
            assertEquals(TablePhase.Constructing, vm.uiState.value.table.phase)
            assertEquals(TableServerState.Constructing, vm.uiState.value.serverState)
        }

    @Test
    fun hostStartIsGatedOnTheServersOwnReadiness() =
        runTest {
            val client = FakeTableClient()
            val vm = viewModel(client)
            vm.observe("t-1", seed(), TableRole.Host)

            // A seat is still open, so the server is only WAITING → cannot start; startMatch is a no-op.
            client.emitTableState(
                TableState(
                    tableId = "t-1",
                    seats = listOf(Seat(index = 0, name = "You", isOccupied = true), Seat(index = 1, isOccupied = false)),
                    serverState = TableServerState.Waiting,
                ),
            )
            assertFalse(vm.uiState.value.canStart)
            vm.startMatch()
            assertTrue(client.calls.none { it.startsWith("start") })

            // Every seat filled → the server reports READY_TO_START → the host may start.
            client.emitTableState(
                TableState(
                    tableId = "t-1",
                    seats =
                        listOf(
                            Seat(index = 0, name = "You", isOccupied = true),
                            Seat(index = 1, name = "Computer", playerType = SeatPlayerType.ComputerMad, isOccupied = true),
                        ),
                    serverState = TableServerState.ReadyToStart,
                ),
            )
            assertTrue(vm.uiState.value.canStart)
            vm.startMatch()
            assertEquals(listOf("start:t-1"), client.calls)
        }

    @Test
    fun leaveSignalsCloseAndCallsClient() =
        runTest {
            val client = FakeTableClient()
            val vm = viewModel(client)
            vm.observe("t-1", seed(), TableRole.Player)

            vm.close.test {
                vm.leaveTable()
                awaitItem()
                cancelAndIgnoreRemainingEvents()
            }
            assertEquals(listOf("leave:t-1"), client.calls)
        }

    @Test
    fun removeSignalsCloseAndCallsClient() =
        runTest {
            val client = FakeTableClient()
            val vm = viewModel(client)
            vm.observe("t-1", seed(), TableRole.Host)

            vm.close.test {
                vm.removeTable()
                awaitItem()
                cancelAndIgnoreRemainingEvents()
            }
            assertEquals(listOf("remove:t-1"), client.calls)
        }

    @Test
    fun leaveDeclineSurfacesTheReasonWithoutClosing() {
        val client = FakeTableClient(leaveResult = Result.failure(TableActionFailure("cannot leave")))
        val vm = viewModel(client)
        vm.observe("t-1", seed(), TableRole.Player)

        vm.leaveTable()

        assertEquals("cannot leave", vm.uiState.value.actionError)
    }

    @Test
    fun matchStartingIsTheTerminalHandOffState() =
        runTest {
            val client = FakeTableClient()
            val vm = viewModel(client)
            vm.observe("t-1", seed(), TableRole.Host)
            assertFalse(vm.uiState.value.isMatchStarting)

            client.emitTableState(
                TableState(tableId = "t-1", phase = TablePhase.Starting, matchStarting = MatchStarting(gameId = "g-1", tableId = "t-1")),
            )
            val state = vm.uiState.value
            assertTrue(state.isMatchStarting)
            assertEquals("g-1", state.matchStarting?.gameId)
            // No host/player actions past the hand-off.
            assertFalse(state.showHostActions)
            assertFalse(state.showPlayerActions)
            assertFalse(state.canStart)
        }

    @Test
    fun spectatorSubscribesViaWatchAndIsReadOnly() =
        runTest {
            val client = FakeTableClient()
            val vm = viewModel(client)
            vm.observe("t-1", seed(), TableRole.Spectator)

            assertTrue(client.calls.contains("watch:t-1"))
            val state = vm.uiState.value
            assertFalse(state.showHostActions)
            assertFalse(state.showPlayerActions)
        }

    @Test
    fun playerCanSubmitADeck() =
        runTest {
            val deck = magefree.decks.model.Deck(id = magefree.decks.model.DeckId("deck-1"), name = "Aggro")
            val client = FakeTableClient()
            val vm = viewModel(client, FakeDeckRepository(listOf(deck)))
            vm.observe("t-1", seed(), TableRole.Player)

            vm.submitDeck(magefree.decks.model.DeckId("deck-1"))
            assertEquals(listOf("submit:t-1"), client.calls)
        }
}
