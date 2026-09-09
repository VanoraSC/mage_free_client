package magefree.feature.game.table

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import magefree.designsystem.card.CardDisplay
import magefree.designsystem.theme.MageTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The opponent's hand, along their own edge.
 *
 * **The property worth holding is that the two kinds of card stay different.** A card you have been
 * shown is face-up and can be read; everything else is a back that says only *there is a card here*,
 * which is precisely what the server sent. A back that could be opened, or a known card drawn as a
 * back, would each be the board claiming something it was not told.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w891dp-h411dp")
class OpponentHandRegionTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val inspected = mutableListOf<String>()

    private fun show(hand: KnownHand) {
        composeTestRule.setContent {
            MageTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    OpponentHandRegion(hand = hand, tileWidth = 60.dp, onInspect = { inspected += it })
                }
            }
        }
    }

    @Test
    fun `a hand you have seen part of shows both halves`() {
        show(KnownHand(cards = listOf(card("thoughtseize")), hidden = 2))

        composeTestRule.onNodeWithTag(OpponentHandTestTags.known("thoughtseize")).assertIsDisplayed()
        composeTestRule.onNodeWithTag(OpponentHandTestTags.hidden(0)).assertIsDisplayed()
        composeTestRule.onNodeWithTag(OpponentHandTestTags.hidden(1)).assertIsDisplayed()
    }

    @Test
    fun `a known card can be read`() {
        show(KnownHand(cards = listOf(card("thoughtseize")), hidden = 0))

        composeTestRule.onNodeWithTag(OpponentHandTestTags.known("thoughtseize")).performClick()

        assertEquals(listOf("thoughtseize"), inspected)
    }

    @Test
    fun `a card back answers nothing, because there is nothing behind it`() {
        show(KnownHand(hidden = 1))

        composeTestRule.onNodeWithTag(OpponentHandTestTags.hidden(0)).performClick()

        assertEquals(emptyList<String>(), inspected)
    }

    @Test
    fun `an empty hand draws nothing at all`() {
        // The board's own rule about regions that hold height.
        show(KnownHand())

        composeTestRule.onNodeWithTag(OpponentHandTestTags.REGION).assertDoesNotExist()
    }

    private fun card(id: String) = TableCard(id = id, card = CardDisplay(name = id))
}
