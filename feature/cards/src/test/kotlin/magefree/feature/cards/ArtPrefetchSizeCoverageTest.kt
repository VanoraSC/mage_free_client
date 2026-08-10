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
 * "Download all art" must warm **every size the UI actually asks for** (story 0043, defect A).
 *
 * The Coil cache key is the resolved URL and `applySize` appends `version=small` for SMALL, so SMALL
 * and LARGE are separate cache entries: warming only one leaves the other's surface blank offline.
 * The expected sizes here are **derived from the production mapping** — the browse/add grid's size
 * comes from [toCardRow] and the inspection view's from [CardInspectionViewModel] — so this test
 * tracks the UI rather than restating a constant.
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
            assertEquals("every size the UI requests must be warmed", displayed, warmedSizes)
            // The one card's front art, once per displayed size.
            assertEquals(displayed.size, warmer.warmed.size)

            val progress = manager.progress.value
            assertEquals(PrefetchStatus.COMPLETED, progress.status)
            assertEquals("the total must count the real targets, not one size's worth", displayed.size, progress.total)
            assertEquals(displayed.size, progress.warmed)
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
