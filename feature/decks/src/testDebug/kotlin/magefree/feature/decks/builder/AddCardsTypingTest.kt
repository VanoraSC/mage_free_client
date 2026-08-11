package magefree.feature.decks.builder

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import magefree.cards.model.CardId
import magefree.decks.model.DeckZone
import magefree.designsystem.theme.MageTheme
import magefree.feature.cards.CardSearchViewModel
import magefree.feature.cards.PlaceholderCardArtRenderer
import magefree.feature.decks.FakeCardCatalog
import magefree.feature.decks.testCard
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Story 0049 — the builder's add-cards surface must accept typing too.
 *
 * It is the second half of the same defect: `AddCardsScreen` had its own hand-rolled
 * `OutlinedTextField` controlled on the **debounced** `uiState.query`, so a player could not find a
 * card by name in the one place where finding cards by name builds a deck. Both surfaces now render
 * the shared `CardSearchField`; this test is what keeps *this* call site honest, independently of
 * `:feature:cards`.
 *
 * Wired the way `DecksRoute` wires it (real [CardSearchViewModel] on its **production** debounce — its
 * shortening constructor is internal to `:feature:cards` — collected state) and driven on virtual time,
 * so the text is asserted *before* the debounce elapses and the results *after*.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(
    application = Application::class,
    // Robolectric 4.14 predates compileSdk 36; the field/list plumbing under test is stable at 34.
    sdk = [34],
    qualifiers = "w411dp-h891dp",
)
class AddCardsTypingTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val dispatcher = StandardTestDispatcher()

    private val forest = testCard(1, "Forest", cardTypes = listOf("Land"))
    private val bolt = testCard(2, "Lightning Bolt", manaValue = 1, cardTypes = listOf("Instant"))

    private val added = mutableListOf<Pair<CardId, DeckZone>>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun renderAddCards(viewModel: CardSearchViewModel) {
        composeTestRule.setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            MageTheme {
                AddCardsScreen(
                    uiState = state,
                    artRenderer = PlaceholderCardArtRenderer,
                    onBack = {},
                    onQueryChange = viewModel::onQueryChange,
                    onClearQuery = viewModel::clearQuery,
                    onColorToggle = viewModel::toggleColor,
                    onCardTypeSelected = viewModel::setCardType,
                    onManaValueSelected = viewModel::setManaValue,
                    onRaritySelected = viewModel::setRarity,
                    onResetFilters = viewModel::resetFilters,
                    onRetry = viewModel::retry,
                    onAdd = { cardId, zone -> added += cardId to zone },
                    onInspect = {},
                )
            }
        }
    }

    private fun settle(afterMillis: Long = 1_000L) {
        dispatcher.scheduler.advanceTimeBy(afterMillis)
        dispatcher.scheduler.runCurrent()
        composeTestRule.waitForIdle()
    }

    private fun searchField() = composeTestRule.onNode(hasSetTextAction())

    @Test
    fun keystrokesAreDisplayedImmediatelyAndNarrowTheAddableResults() {
        val catalog = FakeCardCatalog(cards = listOf(forest, bolt))
        renderAddCards(CardSearchViewModel(catalog))
        composeTestRule.onNodeWithText(ADD_CARDS_SEARCH_HINT).assertIsDisplayed()

        "for".forEach { character ->
            searchField().performTextInput(character.toString())
            composeTestRule.waitForIdle()
        }

        // Immediate: the text is on screen with no catalog query issued yet.
        searchField().assert(hasText("for"))
        composeTestRule.onNodeWithText(ADD_CARDS_SEARCH_HINT).assertDoesNotExist()
        assertTrue("the debounce must still hold the catalog query back", catalog.searchQueries.isEmpty())

        settle()

        assertEquals(listOf("for"), catalog.searchQueries)
        composeTestRule.onNodeWithContentDescription("Forest", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Lightning Bolt", substring = true).assertDoesNotExist()

        // The point of the surface: the card found by name can actually be added to the deck.
        composeTestRule.onNodeWithText("Add to main").performClick()
        assertEquals(listOf(forest.id to DeckZone.MAIN), added)
    }

    @Test
    fun clearingTheFieldEmptiesItAndReturnsToThePrompt() {
        val catalog = FakeCardCatalog(cards = listOf(forest, bolt))
        renderAddCards(CardSearchViewModel(catalog))

        searchField().performTextInput("forest")
        settle()
        composeTestRule.onNodeWithContentDescription("Forest", substring = true).assertIsDisplayed()

        composeTestRule.onNodeWithContentDescription("Clear search").performClick()
        settle(afterMillis = 0L)

        searchField().assert(hasText(""))
        composeTestRule.onNodeWithText(ADD_CARDS_SEARCH_HINT).assertIsDisplayed()
        composeTestRule.onNodeWithText("Search for cards").assertIsDisplayed()
    }
}
