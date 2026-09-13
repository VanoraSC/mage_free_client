package magefree.feature.game.table

import android.app.Application
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import magefree.designsystem.card.BoardCardState
import magefree.designsystem.card.CardDisplay
import magefree.designsystem.card.rememberCounterPalette
import magefree.designsystem.theme.MageTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A flight that starts has to also *finish*.
 *
 * The stack holds an arriving card's place without drawing it, and the only thing that gives the card
 * back is the flight reporting that it landed. So a flight that never completes is not a missing
 * animation — it is a permanent hole in the stack where the card should be, which is worse than never
 * having animated at all.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w891dp-h411dp")
class CardFlightLandingTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val landed = mutableListOf<String>()

    @Test
    fun `a flight moves off its origin and reports that it landed`() {
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            MageTheme {
                CardFlightOverlay(
                    flights = listOf(CardFlight(id = "s1", state = entry().state, art = null, from = from(), to = to())),
                    palette = rememberCounterPalette(),
                    artFor = null,
                    onLanded = { landed += it },
                )
            }
        }

        composeTestRule.mainClock.advanceTimeBy(16)
        val start = boundsOfFlight()

        composeTestRule.mainClock.advanceTimeBy(1_000)
        composeTestRule.waitForIdle()

        assertTrue("the card never moved from $start", boundsOfFlight() != start)
        assertEquals("it never reported landing", listOf("s1"), landed)
    }

    @Test
    fun `a revealed card is held where it started before it travels, and still lands`() {
        // A discard is shown where it left the hand before it goes to the graveyard.
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            MageTheme {
                CardFlightOverlay(
                    flights = listOf(CardFlight(id = "s1", state = entry().state, art = null, from = from(), to = to(), holdMillis = 500)),
                    palette = rememberCounterPalette(),
                    artFor = null,
                    onLanded = { landed += it },
                )
            }
        }

        composeTestRule.mainClock.advanceTimeBy(16)
        val start = boundsOfFlight().center
        composeTestRule.mainClock.advanceTimeBy(400)

        assertEquals("it is still being shown where it left", start, boundsOfFlight().center)
        assertTrue("and has not landed", landed.isEmpty())

        composeTestRule.mainClock.advanceTimeBy(1_000)
        composeTestRule.waitForIdle()

        assertEquals("then it travels and lands", listOf("s1"), landed)
    }

    private fun boundsOfFlight() =
        composeTestRule
            .onNodeWithTag(CardFlightTestTags.card("s1"))
            .fetchSemanticsNode()
            .boundsInRoot

    private fun from() = Rect(left = 10f, top = 300f, right = 70f, bottom = 360f)

    private fun to() = Rect(left = 400f, top = 40f, right = 520f, bottom = 160f)

    private fun entry() = TableStackObject(id = "s1", state = BoardCardState(card = CardDisplay(name = "Something")))
}
