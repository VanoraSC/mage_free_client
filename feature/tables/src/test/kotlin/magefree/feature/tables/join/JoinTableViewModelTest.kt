package magefree.feature.tables.join

import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import magefree.decks.model.Deck
import magefree.decks.model.DeckFormat
import magefree.decks.model.DeckId
import magefree.feature.tables.FakeDeckLegality
import magefree.feature.tables.FakeDeckRepository
import magefree.feature.tables.illegal
import magefree.feature.tables.legal
import magefree.network.fake.FakeTableClient
import magefree.network.table.TableActionFailure
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Hermetic coverage of [JoinTableViewModel] over 0037's [FakeTableClient] plus in-memory
 * `DeckRepository`/`DeckLegality` fakes — no bridge, no `:protocol`, deck pick + legality entirely offline.
 * Pins the deck-pick → legality-gate → password → submit contract.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class JoinTableViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val modernDeck = Deck(id = DeckId("deck-1"), name = "Mono-Red", format = DeckFormat.MODERN)

    private fun target(passworded: Boolean = false) =
        JoinTarget(
            tableId = "t-1",
            tableName = "Friday duel",
            gameType = "Two Player Duel",
            deckType = "Constructed - Modern",
            passworded = passworded,
        )

    private fun viewModel(
        client: FakeTableClient = FakeTableClient(),
        repo: FakeDeckRepository = FakeDeckRepository(listOf(modernDeck)),
        legality: FakeDeckLegality = FakeDeckLegality { legal(it) },
    ) = JoinTableViewModel(client, repo, legality)

    @Test
    fun startResolvesFormatAndLoadsLibrary() {
        val vm = viewModel()
        vm.start(target())
        val state = vm.uiState.value
        assertEquals(DeckFormat.MODERN, state.format)
        assertEquals(listOf("Mono-Red"), state.library.map { it.name })
        assertFalse(state.hasNoDecks)
    }

    @Test
    fun emptyLibraryIsTheBuildADeckPath() {
        val vm = viewModel(repo = FakeDeckRepository())
        vm.start(target())
        assertTrue(vm.uiState.value.hasNoDecks)
        assertFalse(vm.uiState.value.canJoin)
    }

    @Test
    fun pickingALegalDeckEnablesJoinAndShowsLegality() {
        val legality = FakeDeckLegality { legal(it) }
        val vm = viewModel(legality = legality)
        vm.start(target())
        vm.selectDeck(DeckId("deck-1"))

        val state = vm.uiState.value
        assertEquals(listOf(DeckFormat.MODERN), legality.checkedFormats)
        assertTrue(state.legality?.isLegal == true)
        assertTrue(state.canJoin)
    }

    @Test
    fun anIllegalDeckGatesTheJoin() {
        val vm = viewModel(legality = FakeDeckLegality { illegal(it) })
        vm.start(target())
        vm.selectDeck(DeckId("deck-1"))

        val state = vm.uiState.value
        assertFalse(state.legality?.isLegal == true)
        assertFalse(state.canJoin)
    }

    @Test
    fun aProtectedTableNeedsAPasswordBeforeJoining() {
        val vm = viewModel()
        vm.start(target(passworded = true))
        vm.selectDeck(DeckId("deck-1"))
        assertFalse(vm.uiState.value.canJoin)

        vm.setPassword("hunter2")
        assertTrue(vm.uiState.value.canJoin)
    }

    @Test
    fun joinSubmitsTheDeckAndSignalsOpenRoom() =
        runTest {
            val client = FakeTableClient()
            val vm = viewModel(client = client)
            vm.start(target())
            vm.selectDeck(DeckId("deck-1"))

            vm.joined.test {
                vm.join()
                assertEquals("t-1", awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
            assertEquals(listOf("join:t-1"), client.calls)
        }

    @Test
    fun joinDeclineSurfacesTheReason() {
        val client = FakeTableClient(joinResult = Result.failure(TableActionFailure("seat taken")))
        val vm = viewModel(client = client)
        vm.start(target())
        vm.selectDeck(DeckId("deck-1"))

        vm.join()

        assertEquals("seat taken", vm.uiState.value.errorMessage)
        assertFalse(vm.uiState.value.isSubmitting)
    }

    @Test
    fun anUnrecognisedFormatSkipsTheLegalityGate() {
        val legality = FakeDeckLegality { legal(it) }
        val vm = viewModel(legality = legality)
        // A game/deck-type with no bundled-format token: no offline gate, server is the authority.
        vm.start(target().copy(deckType = "Limited", gameType = "Booster Draft"))
        vm.selectDeck(DeckId("deck-1"))

        val state = vm.uiState.value
        assertNull(state.format)
        assertTrue(legality.checkedFormats.isEmpty())
        assertTrue(state.canJoin)
    }
}
