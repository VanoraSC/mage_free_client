package magefree.designsystem.card

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import magefree.designsystem.theme.MageTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

/**
 * A card, read properly.
 *
 * The assertion that carries its weight is the **shape**. Everything else here is content that a
 * screenshot would show; a card stretched by a few per cent is not obviously wrong in a screenshot and
 * is obviously wrong to anyone who plays Magic, and it is the exact thing that happens the moment
 * somebody sizes the card by its width instead of its height.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w891dp-h411dp")
class CardPreviewTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private var dismissed = 0
    private var acted = 0

    private fun show(state: CardPreviewState) {
        composeTestRule.setContent {
            MageTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    CardPreview(state = state, onDismiss = { dismissed += 1 })
                }
            }
        }
    }

    @Test
    fun `the card keeps its proportions`() {
        show(bears())

        val node = composeTestRule.onNodeWithTag(CardPreviewTestTags.CARD).fetchSemanticsNode()
        val ratio = node.size.width.toFloat() / node.size.height.toFloat()

        assertTrue(
            "the card measured ${node.size.width}x${node.size.height}, a ratio of $ratio",
            abs(ratio - CARD_ASPECT_RATIO) < 0.02f,
        )
    }

    @Test
    fun `the card takes most of the height, leaving the board visible around it`() {
        show(bears())

        val node = composeTestRule.onNodeWithTag(CardPreviewTestTags.CARD).fetchSemanticsNode()
        val share = node.size.height.toFloat() / SCREEN_HEIGHT_PX

        assertTrue("the card took $share of the height", share in 0.6f..0.85f)
    }

    @Test
    fun `the panel carries every field, top down`() {
        show(bears())

        composeTestRule.onNodeWithText("Grizzly Bears").assertIsDisplayed()
        // The cost keeps the server's own token as its alternate text, so it still reads as sent.
        composeTestRule.onNodeWithText("{1}{G}").assertIsDisplayed()
        composeTestRule.onNodeWithTag(CardPreviewTestTags.ABILITIES).assertIsDisplayed()
        composeTestRule.onNodeWithTag(CardPreviewTestTags.POWER_TOUGHNESS).assertIsDisplayed()
        composeTestRule.onNodeWithTag(CardPreviewTestTags.ORACLE).assertIsDisplayed()
    }

    @Test
    fun `a non-creature shows no power and toughness at all`() {
        // Rather than an empty row or a dash: a card with no size does not have a size, and printing
        // one would be the board making something up.
        show(bears().copy(power = null, toughness = null))

        composeTestRule.onNodeWithTag(CardPreviewTestTags.POWER_TOUGHNESS).assertDoesNotExist()
    }

    @Test
    fun `the action appears only when there is one, and reports it`() {
        show(bears().copy(action = CardPreviewAction(label = "Cast") { acted += 1 }))

        composeTestRule.onNodeWithTag(CardPreviewTestTags.ACTION).performClick()

        assertEquals(1, acted)
    }

    @Test
    fun `a card with no action offers no button`() {
        show(bears())

        composeTestRule.onNodeWithTag(CardPreviewTestTags.ACTION).assertDoesNotExist()
    }

    @Test
    fun `the scrim and the card dismiss, and the panel does not`() {
        // The panel is where the buttons are, so a press that lands on its background — a finger
        // reaching for Cast and missing by a few dp — must not close the card being read. Without the
        // panel swallowing that press it falls through to the scrim and does exactly that.
        show(bears())

        composeTestRule.onNodeWithTag(CardPreviewTestTags.PANEL).performClick()
        assertEquals("pressing the panel should not close it", 0, dismissed)

        // The top-left corner of the screen is scrim: the card and panel are centred.
        composeTestRule.onNodeWithTag(CardPreviewTestTags.SCRIM).performTouchInput { click(topLeft) }
        assertEquals(1, dismissed)

        composeTestRule.onNodeWithTag(CardPreviewTestTags.CARD).performClick()
        assertEquals("pressing the card puts it down", 2, dismissed)
    }
}

private const val SCREEN_HEIGHT_PX = 411f

private fun bears() =
    CardPreviewState(
        card =
            CardDisplay(
                name = "Grizzly Bears",
                manaCost = "{1}{G}",
                typeLine = "Creature — Bear",
            ),
        power = "2",
        toughness = "2",
        abilities = listOf("{T}: Add {G}."),
        oracleText = "A bear of considerable size.",
    )
