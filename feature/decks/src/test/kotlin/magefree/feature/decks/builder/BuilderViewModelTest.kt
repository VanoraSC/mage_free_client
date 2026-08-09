package magefree.feature.decks.builder

import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import magefree.cards.art.CardArtCachePolicy
import magefree.cards.model.CardFaces
import magefree.cards.model.CardId
import magefree.decks.io.DeckImportResult
import magefree.decks.legality.DeckLegalityResult
import magefree.decks.legality.LegalityViolation
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Hermetic Turbine coverage of [BuilderViewModel] over in-memory fakes — no Room, no catalog SQLite,
 * no Coil, no network. Pins add/remove/sideboard/quantity, the mana-curve computation, live legality
 * per format, and the deck-scoped art-download enqueue.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BuilderViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    // Catalog cards the builder resolves lines/adds against.
    private val bolt = testCard(1, "Lightning Bolt", manaValue = 1, cardTypes = listOf("Instant"))
    private val bear = testCard(2, "Grizzly Bears", manaValue = 2, cardTypes = listOf("Creature"))
    private val titan = testCard(3, "Primeval Titan", manaValue = 6, cardTypes = listOf("Creature"))
    private val forest = testCard(4, "Forest", manaValue = 0, cardTypes = listOf("Land"))
    private val dfc =
        testCard(5, "Delver of Secrets", manaValue = 1, cardTypes = listOf("Creature"), faces = CardFaces(doubleFaced = true))

    private val catalog = FakeCardCatalog(listOf(bolt, bear, titan, forest, dfc))
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

    private fun viewModel(
        deck: Deck,
        legality: FakeDeckLegality = FakeDeckLegality { legal },
        downloader: FakeDeckArtDownloader = FakeDeckArtDownloader(),
        artCache: FakeArtCacheController = FakeArtCacheController(),
    ): BuilderViewModel {
        val repo = FakeDeckRepository(listOf(deck))
        return BuilderViewModel(
            repository = repo,
            catalog = catalog,
            legality = legality,
            deckIO = FakeDeckIO(emptyImport),
            artDownloader = downloader,
            artCache = artCache,
        )
    }

    private fun deck(
        id: String = "deck-1",
        main: List<DeckEntry> = emptyList(),
        sideboard: List<DeckEntry> = emptyList(),
        format: DeckFormat? = null,
    ) = Deck(id = DeckId(id), name = "Test Deck", main = main, sideboard = sideboard, format = format)

    @Test
    fun loadMissingDeckIsNotFound() =
        runTest {
            val repo = FakeDeckRepository()
            val vm =
                BuilderViewModel(
                    repo,
                    catalog,
                    FakeDeckLegality { legal },
                    FakeDeckIO(emptyImport),
                    FakeDeckArtDownloader(),
                    FakeArtCacheController(),
                )
            vm.load(DeckId("missing"))
            assertEquals(BuilderPhase.NotFound, vm.uiState.value.phase)
        }

    @Test
    fun addCardToMainAndSideboardTracksSeparately() =
        runTest {
            val vm = viewModel(deck())
            vm.load(DeckId("deck-1"))

            vm.uiState.test {
                assertEquals(BuilderPhase.Ready, awaitItem().phase)

                vm.addCard(CardId(2), DeckZone.MAIN)
                val afterMain = awaitItem()
                assertEquals(1, afterMain.mainCount)
                assertEquals(0, afterMain.sideboardCount)
                assertTrue(afterMain.groups.any { it.type == "Creature" })

                vm.addCard(CardId(2), DeckZone.MAIN) // second copy merges
                assertEquals(2, awaitItem().mainCount)

                vm.addCard(CardId(1), DeckZone.SIDEBOARD)
                val afterSide = awaitItem()
                assertEquals(2, afterSide.mainCount)
                assertEquals(1, afterSide.sideboardCount)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun quantityDownToZeroRemovesTheLine() =
        runTest {
            val vm = viewModel(deck(main = listOf(DeckEntry("Grizzly Bears", "TST", "2", 2))))
            vm.load(DeckId("deck-1"))
            assertEquals(2, vm.uiState.value.mainCount)

            val key = "Grizzly Bears|TST|2"
            vm.changeQuantity(key, DeckZone.MAIN, -1)
            assertEquals(1, vm.uiState.value.mainCount)

            vm.changeQuantity(key, DeckZone.MAIN, -1)
            assertEquals(0, vm.uiState.value.mainCount)
            assertTrue(
                vm.uiState.value.groups
                    .isEmpty(),
            )
        }

    @Test
    fun removeRowDropsTheLine() =
        runTest {
            val vm = viewModel(deck(main = listOf(DeckEntry("Lightning Bolt", "TST", "1", 4))))
            vm.load(DeckId("deck-1"))
            assertEquals(4, vm.uiState.value.mainCount)

            vm.removeRow("Lightning Bolt|TST|1", DeckZone.MAIN)
            assertEquals(0, vm.uiState.value.mainCount)
        }

    @Test
    fun manaCurveBucketsNonLandMainSpellsAndExcludesLands() =
        runTest {
            val vm =
                viewModel(
                    deck(
                        main =
                            listOf(
                                DeckEntry("Lightning Bolt", "TST", "1", 4), // mv 1
                                DeckEntry("Grizzly Bears", "TST", "2", 3), // mv 2
                                DeckEntry("Primeval Titan", "TST", "3", 2), // mv 6 -> 6+
                                DeckEntry("Forest", "TST", "4", 10), // land, excluded
                            ),
                    ),
                )
            vm.load(DeckId("deck-1"))

            val curve = vm.uiState.value.manaCurve
            val byLabel = curve.buckets.associate { it.label to it.count }
            assertEquals(4, byLabel["1"])
            assertEquals(3, byLabel["2"])
            assertEquals(2, byLabel["6+"])
            assertEquals(0, byLabel["0"]) // Forest is a land, not a 0-cost spell
            assertEquals(9, curve.total)
        }

    @Test
    fun formatPickDrivesLiveLegalityFeedback() =
        runTest {
            val violations = listOf<LegalityViolation>(LegalityViolation.DeckTooSmall(actual = 1, required = 60))
            val legality =
                FakeDeckLegality { format ->
                    if (format == DeckFormat.MODERN) {
                        DeckLegalityResult(format, isLegal = false, violations = violations)
                    } else {
                        DeckLegalityResult(format, isLegal = true, violations = emptyList())
                    }
                }
            val vm = viewModel(deck(main = listOf(DeckEntry("Lightning Bolt", "TST", "1", 1))), legality = legality)
            vm.load(DeckId("deck-1"))
            // No format chosen yet -> no legality result.
            assertNull(vm.uiState.value.legality)

            vm.setFormat(DeckFormat.MODERN)
            val modern = vm.uiState.value
            assertEquals(DeckFormat.MODERN, modern.format)
            val modernLegality = modern.legality!!
            assertFalse(modernLegality.isLegal)
            assertEquals(violations, modernLegality.violations)

            vm.setFormat(DeckFormat.LEGACY)
            assertTrue(
                vm.uiState.value.legality!!
                    .isLegal,
            )
            assertTrue(legality.checkedFormats.containsAll(listOf(DeckFormat.MODERN, DeckFormat.LEGACY)))
        }

    @Test
    fun downloadDeckArtEnqueuesTheCurrentDeckScopedPrefetch() =
        runTest {
            val downloader = FakeDeckArtDownloader()
            val vm =
                viewModel(
                    deck(
                        main = listOf(DeckEntry("Delver of Secrets", "TST", "5", 4)),
                        sideboard = listOf(DeckEntry("Lightning Bolt", "TST", "1", 2)),
                    ),
                    downloader = downloader,
                )
            vm.load(DeckId("deck-1"))

            vm.downloadDeckArt()
            assertEquals(1, downloader.downloadedDecks.size)
            val enqueued = downloader.downloadedDecks.single()
            assertEquals("deck-1", enqueued.id.value)
            assertEquals(4, enqueued.main.single().quantity)
            assertTrue(vm.uiState.value.isPrefetching)

            vm.cancelDownload()
            assertTrue(downloader.cancelled)
        }

    @Test
    fun rebuildResolvesLinesThroughTheBatchedExactNameLookup() =
        runTest {
            // A fresh catalog per test so the recorded calls belong to this rebuild only.
            val spy = FakeCardCatalog(listOf(bolt, bear, titan, forest, dfc))
            val repo =
                FakeDeckRepository(
                    listOf(
                        deck(
                            main = listOf(DeckEntry("Lightning Bolt", "TST", "1", 4), DeckEntry("Grizzly Bears", "TST", "2", 2)),
                            sideboard = listOf(DeckEntry("Forest", "TST", "4", 3)),
                        ),
                    ),
                )
            val vm =
                BuilderViewModel(
                    repo,
                    spy,
                    FakeDeckLegality { legal },
                    FakeDeckIO(emptyImport),
                    FakeDeckArtDownloader(),
                    FakeArtCacheController(),
                )
            vm.load(DeckId("deck-1"))

            assertEquals(BuilderPhase.Ready, vm.uiState.value.phase)
            assertTrue("the builder must not resolve deck lines via the substring search", spy.searchQueries.isEmpty())
            // One batch per rebuild, carrying every line's name.
            assertEquals(1, spy.exactNameLookups.size)
            assertEquals(
                listOf("Forest", "Grizzly Bears", "Lightning Bolt"),
                spy.exactNameLookups.single().sorted(),
            )

            // A subsequent edit re-derives with exactly one more batch, still no substring search.
            vm.changeQuantity("Lightning Bolt|TST|1", DeckZone.MAIN, 1)
            assertEquals(2, spy.exactNameLookups.size)
            assertTrue(spy.searchQueries.isEmpty())
        }

    @Test
    fun policyToggleReachesTheCacheControllerAndState() =
        runTest {
            val artCache = FakeArtCacheController()
            val vm = viewModel(deck(), artCache = artCache)
            vm.load(DeckId("deck-1"))
            assertEquals(CardArtCachePolicy.PERSISTENT, vm.uiState.value.policy)

            vm.setPolicy(CardArtCachePolicy.SESSION_ONLY)
            assertEquals(CardArtCachePolicy.SESSION_ONLY, artCache.policyState.value)
            assertEquals(CardArtCachePolicy.SESSION_ONLY, vm.uiState.value.policy)
        }
}
