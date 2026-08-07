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
import magefree.network.table.TableActionFailure
import magefree.network.table.TablePhase
import magefree.network.table.TableState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Hermetic coverage of [TableRoomViewModel] over 0037's [FakeTableClient] — no bridge, no `:protocol`. It
 * folds a scripted [TableState] (seat joins / ready toggles / phase transitions), pins host-start gating
 * by readiness, the leave/remove close signals, the match-starting terminal state, and the spectator
 * `watchTable` subscription.
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

            // A seat joins (not ready).
            client.emitTableState(
                TableState(tableId = "t-1", seats = listOf(Seat(playerId = "p1", name = "You", isReady = false))),
            )
            assertEquals(1, vm.uiState.value.seats.size)
            assertFalse(
                vm.uiState.value.seats
                    .first()
                    .isReady,
            )
            assertFalse(vm.uiState.value.isLoading)

            // The seat readies + a second seat joins ready → construction phase.
            client.emitTableState(
                TableState(
                    tableId = "t-1",
                    seats =
                        listOf(
                            Seat(playerId = "p1", name = "You", isReady = true, isDeckSubmitted = true),
                            Seat(playerId = "p2", name = "Rival", isReady = true),
                        ),
                    phase = TablePhase.Constructing,
                ),
            )
            assertEquals(2, vm.uiState.value.seats.size)
            assertTrue(
                vm.uiState.value.seats
                    .all { it.isReady },
            )
            assertEquals(TablePhase.Constructing, vm.uiState.value.table.phase)
        }

    @Test
    fun hostStartIsGatedByReadiness() =
        runTest {
            val client = FakeTableClient()
            val vm = viewModel(client)
            vm.observe("t-1", seed(), TableRole.Host)

            // One seat, not ready → cannot start; startMatch is a no-op.
            client.emitTableState(TableState(tableId = "t-1", seats = listOf(Seat(playerId = "p1", isReady = false))))
            assertFalse(vm.uiState.value.canStart)
            vm.startMatch()
            assertTrue(client.calls.none { it.startsWith("start") })

            // All seats ready → can start; startMatch calls the client.
            client.emitTableState(
                TableState(tableId = "t-1", seats = listOf(Seat(playerId = "p1", isReady = true), Seat(playerId = "p2", isReady = true))),
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
