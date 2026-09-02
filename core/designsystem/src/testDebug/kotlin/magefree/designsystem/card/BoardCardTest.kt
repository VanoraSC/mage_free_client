package magefree.designsystem.card

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import magefree.designsystem.theme.MageTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.roundToInt

/**
 * The Board tier rendered.
 *
 * The assertions worth having here are the ones about **footprint**, because tapping is the one piece
 * of card state with a layout consequence: a tapped permanent is a landscape shape where an untapped
 * one is portrait, and a component that rotated only its pixels would leave the board overlapping its
 * own cards while every other test stayed green.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w891dp-h411dp")
class BoardCardTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun show(state: BoardCardState) {
        composeTestRule.setContent {
            MageTheme {
                Box { BoardCard(state = state, width = CARD_WIDTH) }
            }
        }
    }

    @Test
    fun `an untapped card stands portrait`() {
        show(BoardCardState(card = BEARS))

        composeTestRule.onNodeWithTag(BoardCardTestTags.CARD).assertIsDisplayed()
        composeTestRule.onNodeWithText("Grizzly Bears").assertIsDisplayed()
    }

    @Test
    fun `a tapped card takes a landscape footprint, not just a rotated picture`() {
        composeTestRule.setContent {
            MageTheme {
                Box {
                    BoardCard(
                        state = BoardCardState(card = BEARS, tapped = true),
                        width = CARD_WIDTH,
                        modifier = Modifier.testTag(FOOTPRINT),
                    )
                }
            }
        }

        // The footprint swaps: as wide as an untapped card is tall, and as tall as one is wide.
        val cardHeight = (CARD_WIDTH.value / CARD_ASPECT_RATIO).roundToInt().dp
        composeTestRule.onNodeWithTag(FOOTPRINT).assertWidthIsEqualTo(cardHeight)
        composeTestRule.onNodeWithTag(FOOTPRINT).assertHeightIsEqualTo(CARD_WIDTH)
    }

    @Test
    fun `an untapped card keeps a portrait footprint`() {
        composeTestRule.setContent {
            MageTheme {
                Box {
                    BoardCard(
                        state = BoardCardState(card = BEARS),
                        width = CARD_WIDTH,
                        modifier = Modifier.testTag(FOOTPRINT),
                    )
                }
            }
        }

        val cardHeight = (CARD_WIDTH.value / CARD_ASPECT_RATIO).roundToInt().dp
        composeTestRule.onNodeWithTag(FOOTPRINT).assertWidthIsEqualTo(CARD_WIDTH)
        composeTestRule.onNodeWithTag(FOOTPRINT).assertHeightIsEqualTo(cardHeight)
    }

    @Test
    fun `counters render on the card face`() {
        show(BoardCardState(card = BEARS, counters = listOf(BoardCounter("+1/+1", 2))))

        composeTestRule.onNodeWithTag(BoardCardTestTags.COUNTERS).assertIsDisplayed()
        composeTestRule.onNodeWithText("+1/+1 2").assertIsDisplayed()
    }

    @Test
    fun `a counter kind this build does not know still renders`() {
        show(BoardCardState(card = BEARS, counters = listOf(BoardCounter("wibble", 1))))

        composeTestRule.onNodeWithText("wibble 1").assertIsDisplayed()
    }

    @Test
    fun `a heavily countered permanent still shows a card rather than a wall of counters`() {
        show(
            BoardCardState(
                card = BEARS,
                counters =
                    listOf(
                        BoardCounter("+1/+1", 2),
                        BoardCounter("poison", 1),
                        BoardCounter("energy", 3),
                        BoardCounter("charge", 4),
                        BoardCounter("loyalty", 5),
                    ),
            ),
        )

        // Three shown, the rest collapsed — the card stays a card.
        composeTestRule.onNodeWithText("+1/+1 2").assertIsDisplayed()
        composeTestRule.onNodeWithText("+2").assertIsDisplayed()
    }

    @Test
    fun `a creature shows its stats and a non-creature shows none`() {
        show(BoardCardState(card = BEARS, power = "2", toughness = "2"))
        composeTestRule.onNodeWithTag(BoardCardTestTags.STATS).assertIsDisplayed()
        composeTestRule.onNodeWithText("2/2").assertIsDisplayed()
    }

    @Test
    fun `a card with no stats renders no stat label`() {
        show(BoardCardState(card = FOREST))

        composeTestRule.onNodeWithTag(BoardCardTestTags.STATS).assertDoesNotExist()
    }

    @Test
    fun `one signal claims the border alone, with no pips to explain it`() {
        show(BoardCardState(card = BEARS, signals = setOf(BoardCardSignal.Playable)))

        // A single signal is already unambiguous from the border; pips would be noise.
        composeTestRule.onNodeWithTag(BoardCardTestTags.SIGNAL_PIPS).assertDoesNotExist()
    }

    @Test
    fun `simultaneous signals are all shown, because the border can only carry one`() {
        show(
            BoardCardState(
                card = BEARS,
                signals = setOf(BoardCardSignal.Attacking, BoardCardSignal.Targeted),
            ),
        )

        composeTestRule.onNodeWithTag(BoardCardTestTags.SIGNAL_PIPS).assertIsDisplayed()
    }

    private companion object {
        val CARD_WIDTH: Dp = 72.dp
        const val FOOTPRINT = "footprint"

        val BEARS = CardDisplay(name = "Grizzly Bears", manaCost = "1G", typeLine = "Creature — Bear")
        val FOREST = CardDisplay(name = "Forest", typeLine = "Basic Land — Forest")
    }
}
