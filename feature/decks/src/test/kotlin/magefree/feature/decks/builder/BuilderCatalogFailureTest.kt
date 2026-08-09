package magefree.feature.decks.builder

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import magefree.cards.model.CardId
import magefree.decks.DeckRepository
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * The builder must fail **soft** when the catalog behind its derived view fails (story 0042, defect B),
 * and — the part that matters most here — a derivation failure must never cost the user an edit.
 *
 * `load`, `addCard` and every mutation's `rebuild` read the bundled catalog inside bare
 * `viewModelScope.launch`es. `viewModelScope`'s `SupervisorJob` has no `CoroutineExceptionHandler`, so
 * an unguarded throw crashed the process the moment a user opened a deck.
 *
 * The deck is authoritative and is persisted **inside the mutation lock, before** `rebuild` runs, so a
 * catalog failure can only degrade the derived view: rows still render from the deck's own stored
 * names, sets and quantities; only the type grouping, mana curve and legality go blank.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BuilderCatalogFailureTest {
    private val dispatcher = UnconfinedTestDispatcher()

    private val bolt = testCard(1, "Lightning Bolt", manaValue = 1, cardTypes = listOf("Instant"))
    private val bear = testCard(2, "Grizzly Bears", manaValue = 2, cardTypes = listOf("Creature"))

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

    private fun storageFailure(): () -> Throwable = { IOException("No space left on device") }

    private fun deck(
        main: List<DeckEntry> = listOf(DeckEntry("Lightning Bolt", "TST", "1", 4)),
        format: DeckFormat? = null,
    ) = Deck(id = DeckId("deck-1"), name = "Test Deck", main = main, format = format)

    private fun viewModel(
        repo: FakeDeckRepository,
        catalog: FakeCardCatalog,
        legality: FakeDeckLegality = FakeDeckLegality { legal },
    ) = BuilderViewModel(repo, catalog, legality, FakeDeckIO(emptyImport), FakeDeckArtDownloader(), FakeArtCacheController())

    @Test
    fun openingADeckWhenTheCatalogFailsDegradesInsteadOfCrashing() =
        runTest {
            val repo = FakeDeckRepository(listOf(deck()))
            val vm = viewModel(repo, FakeCardCatalog(listOf(bolt, bear), failWith = storageFailure()))

            vm.load(DeckId("deck-1"))
            advanceUntilIdle()

            val state = vm.uiState.value
            // The deck is still editable — not a dead-end error screen.
            assertEquals(BuilderPhase.Ready, state.phase)
            assertTrue(state.catalogUnavailable)
            val detail = state.errorMessage
            assertNotNull(detail)
            assertTrue(detail!!, detail.contains("No space left on device"))
            // Rows come from the deck's own stored fields, so nothing the user saved is hidden.
            assertEquals(4, state.mainCount)
            assertEquals(listOf("Lightning Bolt"), state.groups.flatMap { g -> g.rows.map { it.cardName } })
            // Only the catalog-derived detail is missing.
            assertEquals(0, state.manaCurve.total)
        }

    @Test
    fun anEditIsStillPersistedWhenTheDerivationFails() =
        runTest {
            val repo = FakeDeckRepository(listOf(deck()))
            val catalog = FakeCardCatalog(listOf(bolt, bear), failWith = storageFailure())
            val vm = viewModel(repo, catalog)

            vm.load(DeckId("deck-1"))
            advanceUntilIdle()

            vm.changeQuantity("Lightning Bolt|TST|1", DeckZone.MAIN, 1)
            advanceUntilIdle()

            // The edit landed in the repository even though the re-derivation could not read the catalog.
            assertEquals(5, repo.snapshot().single().mainCount)
            val state = vm.uiState.value
            assertEquals(5, state.mainCount)
            assertTrue(state.catalogUnavailable)
        }

    @Test
    fun aFailedRebuildDoesNotRollBackOrHalfApplyTheDeck() =
        runTest {
            val repo = FakeDeckRepository(listOf(deck()))
            val catalog = FakeCardCatalog(listOf(bolt, bear))
            val vm = viewModel(repo, catalog)

            vm.load(DeckId("deck-1"))
            advanceUntilIdle()
            assertEquals(4, vm.uiState.value.mainCount)

            // The catalog dies between the edit and its re-derivation.
            catalog.failWith = storageFailure()
            vm.removeRow("Lightning Bolt|TST|1", DeckZone.MAIN)
            advanceUntilIdle()

            // Exactly one removal applied — not zero (rolled back) and not a stale view.
            assertEquals(0, repo.snapshot().single().mainCount)
            assertEquals(0, vm.uiState.value.mainCount)
            assertTrue(vm.uiState.value.catalogUnavailable)

            // And the next edit, still with a dead catalog, also persists correctly.
            catalog.failWith = null
            vm.load(DeckId("deck-1"))
            advanceUntilIdle()
            assertFalse(vm.uiState.value.catalogUnavailable)
            assertEquals(0, vm.uiState.value.mainCount)
        }

    @Test
    fun aFailedAddReportsAndChangesNothing() =
        runTest {
            val repo = FakeDeckRepository(listOf(deck()))
            val catalog = FakeCardCatalog(listOf(bolt, bear))
            val vm = viewModel(repo, catalog)

            vm.load(DeckId("deck-1"))
            advanceUntilIdle()

            catalog.failWith = storageFailure()
            vm.addCard(CardId(2), DeckZone.MAIN)
            advanceUntilIdle()

            assertTrue(vm.uiState.value.catalogUnavailable)
            assertNotNull(vm.uiState.value.errorMessage)
            // Nothing was added, and the existing deck is untouched.
            assertEquals(4, repo.snapshot().single().mainCount)
            assertEquals(
                1,
                repo
                    .snapshot()
                    .single()
                    .main.size,
            )
        }

    @Test
    fun aFailingLegalityCheckDegradesButKeepsTheDeckEditable() =
        runTest {
            val repo = FakeDeckRepository(listOf(deck(format = DeckFormat.MODERN)))
            val legality = FakeDeckLegality { legal }.apply { failWith = storageFailure() }
            val vm = viewModel(repo, FakeCardCatalog(listOf(bolt, bear)), legality)

            vm.load(DeckId("deck-1"))
            advanceUntilIdle()

            val state = vm.uiState.value
            assertEquals(BuilderPhase.Ready, state.phase)
            assertNull("no verdict is better than a wrong verdict", state.legality)
            assertTrue(state.catalogUnavailable)
            // Card detail resolved fine — only legality degraded.
            assertEquals(4, state.mainCount)
            assertEquals(4, state.manaCurve.total)
        }

    @Test
    fun retryRecoversTheDerivedViewOnceTheCatalogIsBack() =
        runTest {
            val repo = FakeDeckRepository(listOf(deck(format = DeckFormat.MODERN)))
            val catalog = FakeCardCatalog(listOf(bolt, bear), failWith = storageFailure())
            val vm = viewModel(repo, catalog)

            vm.load(DeckId("deck-1"))
            advanceUntilIdle()
            assertTrue(vm.uiState.value.catalogUnavailable)

            catalog.failWith = null
            vm.retry()
            advanceUntilIdle()

            val state = vm.uiState.value
            assertFalse(state.catalogUnavailable)
            assertNull(state.errorMessage)
            assertEquals(4, state.mainCount)
            assertEquals(4, state.manaCurve.total)
            assertEquals(true, state.legality?.isLegal)
            assertTrue(state.groups.any { it.type == "Instant" })
        }

    @Test
    fun aFailingDeckLoadIsAnErrorPhaseWithRetry() =
        runTest {
            val repo = ThrowingLoadRepository()
            val vm =
                BuilderViewModel(
                    repo,
                    FakeCardCatalog(listOf(bolt, bear)),
                    FakeDeckLegality { legal },
                    FakeDeckIO(emptyImport),
                    FakeDeckArtDownloader(),
                    FakeArtCacheController(),
                )

            vm.load(DeckId("deck-1"))
            advanceUntilIdle()

            assertEquals(BuilderPhase.Error, vm.uiState.value.phase)
            assertNotNull(vm.uiState.value.errorMessage)

            repo.failing = false
            vm.retry()
            advanceUntilIdle()

            assertEquals(BuilderPhase.Ready, vm.uiState.value.phase)
            assertEquals(4, vm.uiState.value.mainCount)
        }

    /** Delegates to a real in-memory repository, but [load] throws while [failing]. */
    private inner class ThrowingLoadRepository(
        private val delegate: FakeDeckRepository = FakeDeckRepository(listOf(deck())),
    ) : DeckRepository by delegate {
        var failing = true

        override suspend fun load(id: DeckId): Deck? {
            if (failing) throw IOException("database is locked")
            return delegate.load(id)
        }
    }
}
