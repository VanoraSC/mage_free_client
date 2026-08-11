package magefree.feature.cards

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import magefree.cards.model.Card
import magefree.cards.model.CardColor
import magefree.designsystem.theme.MageTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * Story 0049 — the coverage whose absence let the dead search field ship (verification standards 1
 * and 2).
 *
 * Every existing `CardSearchViewModel` test calls `onQueryChange(...)` directly and asserts the
 * resulting pipeline state. That is correct and completely blind to the defect, because nothing ever
 * rendered the field: `OutlinedTextField(value = uiState.query, …)` was fully controlled on the
 * **debounced** state, so each keystroke recomposed the field with the old, unchanged text and reverted
 * it. No character could ever land.
 *
 * This test types into the **rendered** composable, wired exactly as `CardsRoute` wires it (real
 * ViewModel, real screen, `collectAsStateWithLifecycle`), and pins the reachability answer: the
 * displayed value must be produced **synchronously by the keystroke**, before — and independently of —
 * the debounced catalog round trip.
 *
 * Time is virtual: `Dispatchers.Main` is a [StandardTestDispatcher], so the ViewModel's debounce
 * advances only when the test says so. That is what lets a single test assert both halves — the text is
 * visible *before* any catalog query is issued, and the results narrow *after* the debounce elapses.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(
    // A plain Application: the ViewModel is constructed directly with a fake catalog, so no Hilt
    // component is needed (and asking for one would only add DI coupling to a UI test).
    application = Application::class,
    // Robolectric 4.14 predates compileSdk 36; the field/grid plumbing under test is stable at 34.
    sdk = [34],
    // Robolectric's default 320x470dp window clips the results grid; pin a representative phone.
    qualifiers = "w411dp-h891dp",
)
class CardSearchTypingTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val dispatcher = StandardTestDispatcher()

    private val forest = testCard(1, "Forest", cardTypes = listOf("Land"), subTypes = listOf("Forest"))
    private val bolt = testCard(2, "Lightning Bolt", manaCost = "{R}", colors = setOf(CardColor.RED), cardTypes = listOf("Instant"))

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** A catalog that answers a name query the way the bundled one does: substring, case-insensitive. */
    private fun catalog(vararg cards: Card) =
        FakeCardCatalog(
            searchResult = { query -> cards.filter { it.name.contains(query, ignoreCase = true) } },
        )

    /** Render the search screen exactly as `CardsRoute` does: real ViewModel, collected UI state. */
    private fun renderSearchScreen(viewModel: CardSearchViewModel) {
        composeTestRule.setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            MageTheme {
                CardSearchScreen(
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
                    onCardSelected = {},
                    onRetry = viewModel::retry,
                    onOpenArtSettings = {},
                )
            }
        }
    }

    /** Let every pending ViewModel coroutine (incl. the debounce) run, then settle the composition. */
    private fun settle(afterMillis: Long = 1_000L) {
        dispatcher.scheduler.advanceTimeBy(afterMillis)
        dispatcher.scheduler.runCurrent()
        composeTestRule.waitForIdle()
    }

    /** The one text-entry node on the screen; located by its input semantics, not by a label. */
    private fun searchField() = composeTestRule.onNode(hasSetTextAction())

    @Test
    fun typedTextIsDisplayedImmediately_beforeAnyCatalogQuery() {
        val catalog = catalog(forest, bolt)
        renderSearchScreen(CardSearchViewModel(catalog, debounceMillis = 300L))
        composeTestRule.onNodeWithText(CARDS_SEARCH_HINT).assertIsDisplayed()

        searchField().performTextInput("forest")
        composeTestRule.waitForIdle()

        // The keystroke itself must produce the displayed value — no debounce, no round trip.
        searchField().assert(hasText("forest"))
        composeTestRule.onNodeWithText(CARDS_SEARCH_HINT).assertDoesNotExist()
        assertTrue("the debounce must still hold the catalog query back", catalog.searchQueries.isEmpty())
    }

    @Test
    fun consecutiveKeystrokesAccumulateIntoOneDebouncedQuery() {
        val catalog = catalog(forest, bolt)
        renderSearchScreen(CardSearchViewModel(catalog, debounceMillis = 300L))

        // One character at a time — the way a player types, and the case the defect ate: each keystroke
        // recomposed the field with the stale debounced value, so nothing could ever accumulate.
        "for".forEach { character ->
            searchField().performTextInput(character.toString())
            composeTestRule.waitForIdle()
        }

        searchField().assert(hasText("for"))
        settle()
        // Three keystrokes, one catalog query: the debounce throttles queries, never the displayed text.
        assertEquals(listOf("for"), catalog.searchQueries)
        composeTestRule.onNodeWithContentDescription("Forest", substring = true).assertIsDisplayed()
    }

    @Test
    fun typingNarrowsResultsOnceTheDebounceElapses() {
        val catalog = catalog(forest, bolt)
        renderSearchScreen(CardSearchViewModel(catalog, debounceMillis = 300L))

        searchField().performTextInput("forest")
        settle()

        // One catalog query for the whole word — the debounce still throttles what it throttled before.
        assertEquals(listOf("forest"), catalog.searchQueries)
        searchField().assert(hasText("forest"))
        composeTestRule.onNodeWithContentDescription("Forest", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Lightning Bolt", substring = true).assertDoesNotExist()
    }

    @Test
    fun aLaggingHoistedQueryNeverRewritesWhatIsOnScreen() {
        // Found on-device: an earlier cut of the field re-adopted the hoisted query whenever it
        // differed from what was displayed. Mid-word the hoisted value is legitimately *behind* — it
        // can only arrive after the keystroke that produced it — so adopting it rewrote the IME's
        // editing buffer and ate characters: `input text "lightning"` landed `lghtnin`.
        val hoisted = mutableStateOf("")
        val reported = mutableListOf<String>()
        composeTestRule.setContent {
            MageTheme {
                CardSearchField(
                    query = hoisted.value,
                    onQueryChange = { reported += it },
                    onClearQuery = {},
                    placeholder = CARDS_SEARCH_HINT,
                )
            }
        }

        searchField().performTextInput("li")
        // The producer catches up only as far as the first character.
        hoisted.value = "l"
        composeTestRule.waitForIdle()

        searchField().assert(hasText("li"))
        assertEquals("the pipeline must still be told what is on screen", "li", reported.last())
    }

    @Test
    fun aCatalogFailureShowsRetryAndLeavesTheFieldTypeable() {
        // Story 0042's fail-soft contract, observed through the rendered screen: a catalog read failure
        // is an error + retry surface, and the pipeline behind it survives to serve the next keystroke.
        val catalog = catalog(forest, bolt).apply { failWith = { IOException("No space left on device") } }
        renderSearchScreen(CardSearchViewModel(catalog, debounceMillis = 300L))

        searchField().performTextInput("forest")
        settle()
        composeTestRule.onNodeWithText("Couldn't read the card catalog", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Retry").assertIsDisplayed()

        catalog.failWith = null
        searchField().performTextInput("s")
        searchField().assert(hasText("forests"))
        settle()

        // The next query was served, so nothing killed the pipeline. (The failed read never reaches the
        // fake's recording, so the one entry here *is* the post-failure query.)
        assertEquals(listOf("forests"), catalog.searchQueries)
        composeTestRule.onNodeWithText("Couldn't read the card catalog", substring = true).assertDoesNotExist()
    }

    @Test
    fun clearingTheFieldEmptiesItAndResetsToIdleInstantly() {
        val catalog = catalog(forest, bolt)
        renderSearchScreen(CardSearchViewModel(catalog, debounceMillis = 300L))

        searchField().performTextInput("forest")
        settle()
        composeTestRule.onNodeWithContentDescription("Forest", substring = true).assertIsDisplayed()

        composeTestRule.onNodeWithContentDescription("Clear search").performClick()
        settle(afterMillis = 0L)

        // The field empties (the placeholder is back) and the idle prompt returns with no debounce wait
        // — story 0042's instant blank-query reset, now observed through the rendered screen.
        searchField().assert(hasText(""))
        composeTestRule.onNodeWithText(CARDS_SEARCH_HINT).assertIsDisplayed()
        composeTestRule.onNodeWithText("Search for cards").assertIsDisplayed()
    }
}
