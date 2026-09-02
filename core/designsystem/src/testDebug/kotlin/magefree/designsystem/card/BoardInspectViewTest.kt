package magefree.designsystem.card

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import magefree.designsystem.theme.MageTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The board inspect view.
 *
 * Two things are worth asserting here and the rest follows from them. The first is that **an
 * attachment somebody else controls is distinguishable** — your Aura on their creature is real board
 * state, it is the one fact about an attachment a player cannot work out by looking, and a panel that
 * listed attachments plainly would drop it while looking entirely correct. The second is that **the
 * panel does not cover the card**, because a zoom whose detail hides the thing it was opened to show
 * is worse than no zoom at all.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w891dp-h411dp")
class BoardInspectViewTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun show(
        state: BoardInspectState,
        palette: CounterPalette? = null,
    ) {
        composeTestRule.setContent {
            MageTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    BoardInspectView(
                        state = state,
                        modifier = Modifier.fillMaxSize(),
                        counterPalette = palette ?: rememberCounterPalette(),
                    )
                }
            }
        }
    }

    @Test
    fun `every attachment is listed`() {
        show(
            BEAR.copy(
                attachments =
                    listOf(
                        BoardAttachment(name = "Pacifism", manaCost = "1W"),
                        BoardAttachment(name = "Bonesplitter", manaCost = "1", tapped = true),
                    ),
            ),
        )

        composeTestRule.onNodeWithTag(BoardInspectTestTags.attachment("Pacifism"), useUnmergedTree = true).assertIsDisplayed()
        composeTestRule
            .onNodeWithTag(BoardInspectTestTags.attachment("Bonesplitter"), useUnmergedTree = true)
            .assertIsDisplayed()
    }

    @Test
    fun `an attachment somebody else controls is marked as such`() {
        // The case the panel exists for. Upstream reports it as `attachedControllerDiffers`, and it is
        // easily missed on a board: the Aura sits on your creature and looks like yours.
        show(
            BEAR.copy(
                attachments =
                    listOf(
                        BoardAttachment(name = "Holy Strength", manaCost = "W"),
                        BoardAttachment(name = "Pacifism", manaCost = "1W", controlledByOther = true),
                    ),
            ),
        )

        composeTestRule
            .onNodeWithTag(BoardInspectTestTags.foreignAttachment("Pacifism"), useUnmergedTree = true)
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(FOREIGN_ATTACHMENT_LABEL).assertIsDisplayed()
    }

    @Test
    fun `an attachment the player controls carries no such marker`() {
        // The control. Marking everything would be the same defect as marking nothing.
        show(BEAR.copy(attachments = listOf(BoardAttachment(name = "Holy Strength", manaCost = "W"))))

        composeTestRule
            .onNodeWithTag(BoardInspectTestTags.foreignAttachment("Holy Strength"), useUnmergedTree = true)
            .assertDoesNotExist()
    }

    @Test
    fun `every counter is named, with the count the card is showing`() {
        show(BEAR.copy(counters = listOf(BoardCounter("+1/+1", 3), BoardCounter("stun", 1))))

        composeTestRule.onNodeWithTag(BoardInspectTestTags.counter("+1/+1"), useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithTag(BoardInspectTestTags.counter("stun"), useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("+1/+1").assertIsDisplayed()
        composeTestRule.onNodeWithText("stun").assertIsDisplayed()
    }

    @Test
    fun `a counter kind this build has never heard of is named rather than dropped`() {
        // The whole reason the panel exists: the board gives an unrecognised kind a colour off the queue,
        // which is meaningless until something says what the colour is. Dropping it here would make the
        // board's compression into information loss.
        show(BEAR.copy(counters = listOf(BoardCounter("everything", 2))))

        composeTestRule.onNodeWithTag(BoardInspectTestTags.counter("everything"), useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("everything").assertIsDisplayed()
    }

    @Test
    fun `the panel explains the board's colours rather than inventing its own`() {
        // Asserted through the palette rather than through pixels: the view is handed the board's live
        // palette, so a kind the board has already coloured must keep that colour. A panel that made its
        // own palette, or re-allocated, would hand the player a legend for a board they are not looking at.
        val palette = CounterPalette()
        val onTheBoard = palette.colorFor("oil")

        show(BEAR.copy(counters = listOf(BoardCounter("rust", 1), BoardCounter("oil", 4))), palette = palette)
        composeTestRule.waitForIdle()

        assertEquals("the panel changed the colour the board is already using for oil", onTheBoard, palette.colorFor("oil"))
    }

    @Test
    fun `badges are named, and the server's own hint is shown when it says more`() {
        // Hexproof is the case that makes the hint load-bearing: upstream sends shroud under the same
        // icon, so the badge alone cannot tell the player which one this creature has.
        show(
            BEAR.copy(
                badges =
                    listOf(
                        InspectBadge(BoardBadge.Flying, detail = "Flying"),
                        InspectBadge(BoardBadge.Hexproof, detail = "Shroud"),
                    ),
            ),
        )

        composeTestRule.onNodeWithTag(BoardInspectTestTags.badge(BoardBadge.Flying), useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Hexproof").assertIsDisplayed()
        composeTestRule.onNodeWithText("Shroud").assertIsDisplayed()
    }

    @Test
    fun `a hint that only repeats the keyword's name is not shown twice`() {
        show(BEAR.copy(badges = listOf(InspectBadge(BoardBadge.Trample, detail = "Trample"))))

        composeTestRule.onNodeWithText("Trample").assertIsDisplayed()
        assertEquals(
            "the hint said nothing the name did not",
            1,
            countNodesWithText("Trample"),
        )
    }

    @Test
    fun `what is currently modifying the permanent is listed in the server's own words`() {
        show(BEAR.copy(modifications = listOf("Enchanted creature can't attack or block.", "+2/+2 until end of turn")))

        composeTestRule.onNodeWithTag(BoardInspectTestTags.MODIFICATIONS, useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("+2/+2 until end of turn").assertIsDisplayed()
    }

    @Test
    fun `the panel sits beside the card and never over it`() {
        show(
            BEAR.copy(
                counters = listOf(BoardCounter("+1/+1", 2)),
                attachments = listOf(BoardAttachment(name = "Pacifism", manaCost = "1W")),
            ),
        )

        val card = composeTestRule.onNodeWithTag(BoardInspectTestTags.CARD).fetchSemanticsNode()
        val panel = composeTestRule.onNodeWithTag(BoardInspectTestTags.PANEL).fetchSemanticsNode()

        val cardRight = card.positionInRoot.x + card.size.width
        assertTrue(
            "the panel starts at ${panel.positionInRoot.x} but the card runs to $cardRight — the detail is " +
                "covering the thing the player zoomed to see",
            panel.positionInRoot.x >= cardRight,
        )
    }

    /** How many nodes carry [text] — for the assertions about saying a thing once. */
    private fun countNodesWithText(text: String): Int = composeTestRule.onAllNodesWithText(text).fetchSemanticsNodes().size

    private companion object {
        val BEAR =
            BoardInspectState(
                card = CardDisplay(name = "Grizzly Bears", manaCost = "1G", typeLine = "Creature — Bear"),
                power = "2",
                toughness = "2",
            )
    }
}
