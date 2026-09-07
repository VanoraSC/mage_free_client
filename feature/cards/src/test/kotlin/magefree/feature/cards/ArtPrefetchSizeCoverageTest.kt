package magefree.feature.cards

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import magefree.cards.art.ArtDownloadManager
import magefree.cards.art.ArtWarmer
import magefree.cards.art.CardArtCachePolicyRepository
import magefree.cards.art.CardArtRequest
import magefree.cards.art.CardArtSize
import magefree.cards.art.CatalogPrefetchTargetSource
import magefree.cards.art.PREFETCH_SIZES
import magefree.cards.art.PrefetchScope
import magefree.cards.art.PrefetchStatus
import magefree.cards.model.CardId
import magefree.cards.model.CardPrinting
import magefree.cards.model.Rarity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections

/**
 * "Download all art" must warm **every size the UI actually asks for**.
 *
 * The Coil cache key is the resolved URL and `applySize` appends a `version` for everything but
 * LARGE, so each size is a separate cache entry: warming only one leaves the others' surfaces blank
 * offline. The sizes checked here are **derived from the production mapping** — the browse/add grid's
 * from [toCardRow] and the inspection view's from [CardInspectionViewModel] — so this test tracks the
 * UI rather than restating a constant.
 *
 * It cannot track all of it. The board asks for an art crop, which is a different picture and its own
 * cache entry, and deriving that would mean reaching into `:feature:game` from here. So what this
 * asserts is that the sizes it *can* derive are covered, and that a run warms one image per size —
 * the claim that every size is one some surface wants belongs to `PREFETCH_SIZES` itself.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ArtPrefetchSizeCoverageTest {
    private val mainDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** The cache-policy store is irrelevant to a prefetch; this keeps the controller constructible. */
    private class NoopPreferences : DataStore<Preferences> {
        override val data: Flow<Preferences> = flowOf(emptyPreferences())

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = emptyPreferences()
    }

    private class RecordingWarmer : ArtWarmer {
        val warmed: MutableList<CardArtRequest> = Collections.synchronizedList(ArrayList())

        override suspend fun isCached(request: CardArtRequest): Boolean = false

        override suspend fun warm(request: CardArtRequest): Boolean {
            warmed += request
            return true
        }
    }

    private val card =
        testCard(
            id = 1,
            name = "Opt",
            printings = listOf(CardPrinting(setCode = "XLN", collectorNumber = "121", rarity = Rarity.COMMON)),
        )

    /** The size the browse/add results grid requests, read off the production row mapping. */
    private fun gridSize(): CardArtSize = card.toCardRow().artRequest!!.size

    /** The size the inspection view requests, read off the production ViewModel. */
    private fun inspectionSize(catalog: FakeCardCatalog): CardArtSize {
        val vm = CardInspectionViewModel(catalog)
        vm.load(CardId(1))
        mainDispatcher.scheduler.advanceUntilIdle()
        return vm.uiState.value.artRequest!!
            .size
    }

    @Test
    fun `download all art warms every size the UI displays`() =
        runTest {
            val catalog = FakeCardCatalog(byId = mapOf(card.id to card), filterResult = { listOf(card) })
            val warmer = RecordingWarmer()
            val manager =
                ArtDownloadManager(
                    targetSource = CatalogPrefetchTargetSource(catalog),
                    warmer = warmer,
                    appScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
                    politeDelayMs = 0,
                )
            val controller = DefaultArtCacheController(CardArtCachePolicyRepository(NoopPreferences()), manager)

            controller.startPrefetch(PrefetchScope.All)
            advanceUntilIdle()

            val displayed = setOf(gridSize(), inspectionSize(catalog))
            val warmedSizes = warmer.warmed.map { it.size }.toSet()
            assertTrue(
                "every size the UI requests must be warmed: $displayed against $warmedSizes",
                warmedSizes.containsAll(displayed),
            )
            // Containment rather than equality, because this module cannot see every surface that
            // displays art. The board asks for [CardArtSize.ART_CROP] — a different picture, its own
            // cache entry, and one this test would have to reach into `:feature:game` to derive. What
            // is checkable from here is that the two browse sizes are covered, and that a run warms
            // one image per size and no more.
            assertEquals(PREFETCH_SIZES, warmedSizes)
            assertEquals("one image per size, for the one card", PREFETCH_SIZES.size, warmer.warmed.size)

            val progress = manager.progress.value
            assertEquals(PrefetchStatus.COMPLETED, progress.status)
            assertEquals("the total must count the real targets, not one size's worth", PREFETCH_SIZES.size, progress.total)
            assertEquals(PREFETCH_SIZES.size, progress.warmed)
            assertEquals(1f, progress.fraction)
        }

    @Test
    fun `the warmed requests carry the grid's own art identity`() =
        runTest {
            val catalog = FakeCardCatalog(byId = mapOf(card.id to card), filterResult = { listOf(card) })
            val warmer = RecordingWarmer()
            val manager =
                ArtDownloadManager(
                    targetSource = CatalogPrefetchTargetSource(catalog),
                    warmer = warmer,
                    appScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
                    politeDelayMs = 0,
                )
            DefaultArtCacheController(CardArtCachePolicyRepository(NoopPreferences()), manager).startPrefetch(PrefetchScope.All)
            advanceUntilIdle()

            // The exact request object the grid would hand Coil must have been warmed.
            val gridRequest = card.toCardRow().artRequest!!
            assertTrue("the grid's own request was never warmed: ${warmer.warmed}", warmer.warmed.contains(gridRequest))
        }
}
