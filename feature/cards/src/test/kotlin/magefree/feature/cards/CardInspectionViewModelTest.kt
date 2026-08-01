package magefree.feature.cards

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import magefree.cards.art.CardArtFace
import magefree.cards.model.CardFaces
import magefree.cards.model.CardId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Hermetic coverage of [CardInspectionViewModel] over a [FakeCardCatalog]: selection -> loaded detail,
 * the DFC face flip, the not-found surface, and the offline-art (null art request) placeholder path.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CardInspectionViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun loadMapsCardToLoadedDetail() =
        runTest {
            val bolt =
                testCard(
                    1,
                    "Lightning Bolt",
                    manaCost = "{R}",
                    cardTypes = listOf("Instant"),
                    rules = listOf("Deal 3 damage to any target."),
                )
            val vm = CardInspectionViewModel(FakeCardCatalog(byId = mapOf(CardId(1) to bolt)))

            vm.load(CardId(1))
            advanceUntilIdle()

            val state = vm.uiState.value
            assertEquals(CardInspectionPhase.Loaded, state.phase)
            assertEquals("Lightning Bolt", state.display?.name)
            assertEquals(CardArtFace.FRONT, state.face)
            assertFalse(state.canFlip)
            assertEquals(CardArtFace.FRONT, state.artRequest?.face)
        }

    @Test
    fun missingCardMapsToNotFound() =
        runTest {
            val vm = CardInspectionViewModel(FakeCardCatalog())

            vm.load(CardId(404))
            advanceUntilIdle()

            assertEquals(CardInspectionPhase.NotFound, vm.uiState.value.phase)
            assertNull(vm.uiState.value.display)
        }

    @Test
    fun doubleFacedCardFlipsBetweenFaces() =
        runTest {
            val delver =
                testCard(
                    2,
                    "Delver of Secrets",
                    manaCost = "{U}",
                    cardTypes = listOf("Creature"),
                    faces = CardFaces(doubleFaced = true, secondSideName = "Insectile Aberration"),
                )
            val vm = CardInspectionViewModel(FakeCardCatalog(byId = mapOf(CardId(2) to delver)))

            vm.load(CardId(2))
            advanceUntilIdle()
            assertTrue(vm.uiState.value.canFlip)
            assertEquals(CardArtFace.FRONT, vm.uiState.value.face)

            vm.flipFace()
            var state = vm.uiState.value
            assertEquals(CardArtFace.BACK, state.face)
            assertEquals(CardArtFace.BACK, state.artRequest?.face)
            assertEquals("Insectile Aberration", state.faceName)

            vm.flipFace()
            state = vm.uiState.value
            assertEquals(CardArtFace.FRONT, state.face)
            assertNull(state.faceName)
        }

    @Test
    fun flipIsNoOpForSingleFacedCard() =
        runTest {
            val bear = testCard(3, "Grizzly Bears")
            val vm = CardInspectionViewModel(FakeCardCatalog(byId = mapOf(CardId(3) to bear)))

            vm.load(CardId(3))
            advanceUntilIdle()
            assertFalse(vm.uiState.value.canFlip)

            vm.flipFace()
            assertEquals(CardArtFace.FRONT, vm.uiState.value.face)
        }

    @Test
    fun cardWithoutPrintingsHasNullArtRequest() =
        runTest {
            val token = testCard(4, "Soldier Token", printings = emptyList())
            val vm = CardInspectionViewModel(FakeCardCatalog(byId = mapOf(CardId(4) to token)))

            vm.load(CardId(4))
            advanceUntilIdle()

            assertEquals(CardInspectionPhase.Loaded, vm.uiState.value.phase)
            assertNull("no printing -> null art request -> placeholder", vm.uiState.value.artRequest)
        }
}
