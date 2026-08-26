package magefree.feature.cards

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import magefree.cards.model.CardColor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * A catalog read failure must fail **soft**.
 *
 * The catalog is a bundled read-only asset, and its first use copies ~14 MB into `filesDir`; on a
 * device with no free storage that copy throws `IOException`. The search flow calls the catalog inside
 * a `flow { }` that is `launchIn(viewModelScope)`, and `viewModelScope`'s `SupervisorJob` carries no
 * `CoroutineExceptionHandler` — so an unguarded throw reached the default handler and took the process
 * down, permanently terminating the combine/flatMapLatest pipeline with it.
 *
 * These tests pin the two halves of the fix: the failure becomes state, and the pipeline survives it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CardSearchCatalogFailureTest {
    private val dispatcher = StandardTestDispatcher()

    private val bolt = testCard(1, "Lightning Bolt", manaCost = "{R}", colors = setOf(CardColor.RED), cardTypes = listOf("Instant"))

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun storageFailure(): () -> Throwable = { IOException("No space left on device") }

    @Test
    fun searchFailureBecomesAnErrorStateInsteadOfACrash() =
        runTest {
            val catalog = FakeCardCatalog(searchResult = { listOf(bolt) }, failWith = storageFailure())
            val vm = CardSearchViewModel(catalog, debounceMillis = 0L)
            advanceUntilIdle()

            vm.onQueryChange("bolt")
            advanceUntilIdle()

            val state = vm.uiState.value
            assertEquals(CardSearchPhase.Error, state.phase)
            val detail = state.errorMessage
            assertNotNull(detail)
            assertTrue(detail!!, detail.contains("No space left on device"))
            assertTrue("stale results must not sit under an error", state.results.isEmpty())
            assertEquals("bolt", state.query)
        }

    @Test
    fun filterFailureBecomesAnErrorStateInsteadOfACrash() =
        runTest {
            val catalog = FakeCardCatalog(filterResult = { listOf(bolt) }, failWith = storageFailure())
            val vm = CardSearchViewModel(catalog, debounceMillis = 0L)
            advanceUntilIdle()

            vm.toggleColor(CardColor.RED)
            advanceUntilIdle()

            assertEquals(CardSearchPhase.Error, vm.uiState.value.phase)
        }

    @Test
    fun theSearchPipelineStillServesTheNextQueryAfterAFailure() =
        runTest {
            val catalog = FakeCardCatalog(searchResult = { listOf(bolt) }, failWith = storageFailure())
            val vm = CardSearchViewModel(catalog, debounceMillis = 0L)
            advanceUntilIdle()

            vm.onQueryChange("bolt")
            advanceUntilIdle()
            assertEquals(CardSearchPhase.Error, vm.uiState.value.phase)

            // The catalog recovers (storage freed). A new query must still be served — proof the
            // pipeline was not terminated by the throw.
            catalog.failWith = null
            vm.onQueryChange("lightning")
            advanceUntilIdle()

            val state = vm.uiState.value
            assertEquals(CardSearchPhase.Results, state.phase)
            assertNull(state.errorMessage)
            assertEquals(listOf("Lightning Bolt"), state.results.map { it.display.name })
        }

    @Test
    fun retryReRunsTheSameQueryWithoutRetyping() =
        runTest {
            val catalog = FakeCardCatalog(searchResult = { listOf(bolt) }, failWith = storageFailure())
            val vm = CardSearchViewModel(catalog, debounceMillis = 0L)
            advanceUntilIdle()

            vm.onQueryChange("bolt")
            advanceUntilIdle()
            assertEquals(CardSearchPhase.Error, vm.uiState.value.phase)

            catalog.failWith = null
            vm.retry()
            advanceUntilIdle()

            assertEquals(CardSearchPhase.Results, vm.uiState.value.phase)
            assertEquals("bolt", vm.uiState.value.query)
        }

    @Test
    fun aSecondFailureAfterARecoveryIsStillHandled() =
        runTest {
            val catalog = FakeCardCatalog(searchResult = { listOf(bolt) })
            val vm = CardSearchViewModel(catalog, debounceMillis = 0L)
            advanceUntilIdle()

            vm.onQueryChange("bolt")
            advanceUntilIdle()
            assertEquals(CardSearchPhase.Results, vm.uiState.value.phase)

            catalog.failWith = storageFailure()
            vm.onQueryChange("bear")
            advanceUntilIdle()
            assertEquals(CardSearchPhase.Error, vm.uiState.value.phase)

            catalog.failWith = null
            vm.onQueryChange("bolt again")
            advanceUntilIdle()
            assertEquals(CardSearchPhase.Results, vm.uiState.value.phase)
        }
}
