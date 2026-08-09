package magefree.feature.cards

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import magefree.cards.model.CardId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * Card inspection must fail **soft** when the catalog read fails (story 0042, defect B).
 *
 * `load` runs inside a bare `viewModelScope.launch`, and `viewModelScope`'s `SupervisorJob` carries no
 * `CoroutineExceptionHandler` — so an unguarded `IOException` from the first-use asset copy crashed
 * the process the moment a user tapped a card.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CardInspectionCatalogFailureTest {
    private val dispatcher = StandardTestDispatcher()

    private val bolt = testCard(1, "Lightning Bolt", manaCost = "{R}", cardTypes = listOf("Instant"))

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
    fun aFailedLookupBecomesAnErrorStateInsteadOfACrash() =
        runTest {
            val catalog = FakeCardCatalog(byId = mapOf(bolt.id to bolt), failWith = storageFailure())
            val vm = CardInspectionViewModel(catalog)

            vm.load(CardId(1))
            advanceUntilIdle()

            val state = vm.uiState.value
            assertEquals(CardInspectionPhase.Error, state.phase)
            val detail = state.errorMessage
            assertNotNull(detail)
            assertTrue(detail!!, detail.contains("No space left on device"))
            assertNull(state.display)
        }

    @Test
    fun aFailureIsDistinctFromAGenuineNotFound() =
        runTest {
            // The catalog answers, and the card simply isn't there — that is NotFound, not Error.
            val vm = CardInspectionViewModel(FakeCardCatalog(byId = mapOf(bolt.id to bolt)))

            vm.load(CardId(999))
            advanceUntilIdle()

            assertEquals(CardInspectionPhase.NotFound, vm.uiState.value.phase)
            assertNull(vm.uiState.value.errorMessage)
        }

    @Test
    fun aLaterLoadStillWorksAfterAFailure() =
        runTest {
            val catalog = FakeCardCatalog(byId = mapOf(bolt.id to bolt), failWith = storageFailure())
            val vm = CardInspectionViewModel(catalog)

            vm.load(CardId(1))
            advanceUntilIdle()
            assertEquals(CardInspectionPhase.Error, vm.uiState.value.phase)

            catalog.failWith = null
            vm.load(CardId(1))
            advanceUntilIdle()

            val state = vm.uiState.value
            assertEquals(CardInspectionPhase.Loaded, state.phase)
            assertEquals("Lightning Bolt", state.display?.name)
            assertNull(state.errorMessage)
        }

    @Test
    fun retryReRunsTheLastLoad() =
        runTest {
            val catalog = FakeCardCatalog(byId = mapOf(bolt.id to bolt), failWith = storageFailure())
            val vm = CardInspectionViewModel(catalog)

            vm.load(CardId(1))
            advanceUntilIdle()
            assertEquals(CardInspectionPhase.Error, vm.uiState.value.phase)

            catalog.failWith = null
            vm.retry()
            advanceUntilIdle()

            assertEquals(CardInspectionPhase.Loaded, vm.uiState.value.phase)
            assertEquals(
                "Lightning Bolt",
                vm.uiState.value.display
                    ?.name,
            )
        }

    @Test
    fun flippingAfterAFailedLoadIsANoOp() =
        runTest {
            val catalog = FakeCardCatalog(byId = mapOf(bolt.id to bolt), failWith = storageFailure())
            val vm = CardInspectionViewModel(catalog)

            vm.load(CardId(1))
            advanceUntilIdle()

            vm.flipFace()
            advanceUntilIdle()
            // No stale card is left behind to flip into a bogus Loaded state.
            assertEquals(CardInspectionPhase.Error, vm.uiState.value.phase)
        }
}
