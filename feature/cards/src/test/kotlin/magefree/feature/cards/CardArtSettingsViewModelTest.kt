package magefree.feature.cards

import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import magefree.cards.art.CardArtCachePolicy
import magefree.cards.art.PrefetchProgress
import magefree.cards.art.PrefetchScope
import magefree.cards.art.PrefetchStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Hermetic coverage of [CardArtSettingsViewModel] over a fake [ArtCacheController] (the "fake image
 * loader" of the art subsystem — no Coil, no DataStore). Pins the policy + prefetch mapping and that
 * the mutations reach the controller.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CardArtSettingsViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeArtCacheController : ArtCacheController {
        val policyState = MutableStateFlow(CardArtCachePolicy.PERSISTENT)
        val progressState = MutableStateFlow(PrefetchProgress())
        var startedScope: PrefetchScope? = null
        var cancelled = false

        override val policy: Flow<CardArtCachePolicy> get() = policyState
        override val prefetchProgress: StateFlow<PrefetchProgress> get() = progressState.asStateFlow()

        override suspend fun setPolicy(policy: CardArtCachePolicy) {
            policyState.value = policy
        }

        override fun startPrefetch(scope: PrefetchScope) {
            startedScope = scope
            progressState.value = PrefetchProgress(status = PrefetchStatus.RUNNING, total = 10)
        }

        override fun cancelPrefetch() {
            cancelled = true
            progressState.value = progressState.value.copy(status = PrefetchStatus.CANCELLED)
        }
    }

    @Test
    fun exposesPolicyAndProgress() =
        runTest {
            val controller = FakeArtCacheController()
            val vm = CardArtSettingsViewModel(controller)

            vm.uiState.test {
                assertEquals(CardArtCachePolicy.PERSISTENT, awaitItem().policy)

                controller.progressState.value = PrefetchProgress(status = PrefetchStatus.RUNNING, total = 10, warmed = 3)
                val running = awaitItem()
                assertTrue(running.isPrefetching)
                assertEquals(0.3f, running.prefetch.fraction)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun setPolicyReachesController() =
        runTest {
            val controller = FakeArtCacheController()
            val vm = CardArtSettingsViewModel(controller)

            vm.setPolicy(CardArtCachePolicy.SESSION_ONLY)
            assertEquals(CardArtCachePolicy.SESSION_ONLY, controller.policyState.value)
        }

    @Test
    fun downloadAndCancelReachController() =
        runTest {
            val controller = FakeArtCacheController()
            val vm = CardArtSettingsViewModel(controller)

            vm.downloadAllArt()
            assertEquals(PrefetchScope.All, controller.startedScope)

            vm.cancelDownload()
            assertTrue(controller.cancelled)
        }
}
