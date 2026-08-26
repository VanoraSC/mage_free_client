package magefree.feature.decks.builder

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import magefree.cards.model.CardId
import magefree.decks.io.DeckImportResult
import magefree.decks.legality.DeckLegalityResult
import magefree.decks.model.Deck
import magefree.decks.model.DeckEntry
import magefree.decks.model.DeckFormat
import magefree.decks.model.DeckId
import magefree.decks.model.DeckZone
import magefree.feature.decks.FakeArtCacheController
import magefree.feature.decks.FakeCardCatalog
import magefree.feature.decks.FakeDeckArtDownloader
import magefree.feature.decks.FakeDeckIO
import magefree.feature.decks.FakeDeckLegality
import magefree.feature.decks.FakeDeckRepository
import magefree.feature.decks.testCard
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Pins the atomicity of builder deck mutations across the catalog suspension.
 *
 * `addCard` has to look the card up in the catalog before it can mutate the deck, and that lookup
 * suspends. The fake catalog here makes the lookup take (virtual) time on purpose, so two taps land in
 * the window between the read of the current deck and the write of the next one — exactly what a user
 * double-tapping "add" produces on a real device, where the lookup is a SQLite read on the IO
 * dispatcher.
 *
 * Against a `read → suspend → write` implementation that snapshots the deck *before* the launch, both
 * coroutines start from v0 and both write v0+1, so two taps yield one copy and the truncated deck is
 * persisted. These tests fail in exactly that way on that implementation and pass once every mutation
 * re-reads the authoritative deck inside a serialised critical section.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BuilderMutationRaceTest {
    private val dispatcher = UnconfinedTestDispatcher()

    private val bolt = testCard(1, "Lightning Bolt", manaValue = 1, cardTypes = listOf("Instant"))
    private val bear = testCard(2, "Grizzly Bears", manaValue = 2, cardTypes = listOf("Creature"))

    /** A catalog whose card lookup genuinely suspends — the window the race lives in. */
    private val slowCatalog = FakeCardCatalog(listOf(bolt, bear), cardLookupDelayMillis = 50L)

    private val legal = DeckLegalityResult(format = DeckFormat.MODERN, isLegal = true, violations = emptyList())
    private val emptyImport = DeckImportResult(deck = Deck(id = DeckId(""), name = ""))

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun repositoryWith(vararg main: DeckEntry) =
        FakeDeckRepository(listOf(Deck(id = DeckId("deck-1"), name = "Test Deck", main = main.toList())))

    private fun viewModel(repo: FakeDeckRepository) =
        BuilderViewModel(
            repository = repo,
            catalog = slowCatalog,
            legality = FakeDeckLegality { legal },
            deckIO = FakeDeckIO(emptyImport),
            artDownloader = FakeDeckArtDownloader(),
            artCache = FakeArtCacheController(),
        )

    @Test
    fun `two rapid adds of the same card yield two copies`() =
        runTest {
            val repo = repositoryWith()
            val vm = viewModel(repo)
            vm.load(DeckId("deck-1"))
            advanceUntilIdle()
            assertEquals(BuilderPhase.Ready, vm.uiState.value.phase)

            // Both taps happen inside the catalog-lookup window.
            vm.addCard(CardId(2), DeckZone.MAIN)
            vm.addCard(CardId(2), DeckZone.MAIN)
            advanceUntilIdle()

            assertEquals("state lost a copy", 2, vm.uiState.value.mainCount)
            assertEquals("persisted deck lost a copy", 2, repo.snapshot().single().mainCount)
        }

    @Test
    fun `four rapid adds of distinct cards all land`() =
        runTest {
            val repo = repositoryWith()
            val vm = viewModel(repo)
            vm.load(DeckId("deck-1"))
            advanceUntilIdle()

            repeat(2) { vm.addCard(CardId(1), DeckZone.MAIN) }
            repeat(2) { vm.addCard(CardId(2), DeckZone.MAIN) }
            advanceUntilIdle()

            assertEquals(4, vm.uiState.value.mainCount)
            val persisted = repo.snapshot().single()
            assertEquals(4, persisted.mainCount)
            assertEquals(2, persisted.main.size)
            assertEquals(listOf(2, 2), persisted.main.map { it.quantity })
        }

    @Test
    fun `adding to main then sideboard keeps the main copy`() =
        runTest {
            val repo = repositoryWith()
            val vm = viewModel(repo)
            vm.load(DeckId("deck-1"))
            advanceUntilIdle()

            vm.addCard(CardId(1), DeckZone.MAIN)
            vm.addCard(CardId(1), DeckZone.SIDEBOARD)
            advanceUntilIdle()

            val state = vm.uiState.value
            assertEquals("main copy was clobbered by the sideboard add", 1, state.mainCount)
            assertEquals(1, state.sideboardCount)
            val persisted = repo.snapshot().single()
            assertEquals(1, persisted.mainCount)
            assertEquals(1, persisted.sideboardCount)
        }

    @Test
    fun `a quantity change interleaved with an add does not lose either`() =
        runTest {
            val repo = repositoryWith(DeckEntry("Lightning Bolt", "TST", "1", 2))
            val vm = viewModel(repo)
            vm.load(DeckId("deck-1"))
            advanceUntilIdle()

            // The add suspends on the catalog; the quantity bump is synchronous. Whichever order they
            // commit in, both must be reflected: 2 + 1 (bump) + 1 (add) = 4.
            vm.addCard(CardId(2), DeckZone.MAIN)
            vm.changeQuantity("Lightning Bolt|TST|1", DeckZone.MAIN, 1)
            advanceUntilIdle()

            assertEquals(4, vm.uiState.value.mainCount)
            assertEquals(4, repo.snapshot().single().mainCount)
        }
}
