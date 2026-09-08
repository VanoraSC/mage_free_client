package magefree.feature.game.table

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import magefree.designsystem.card.BoardCardState
import magefree.designsystem.card.CardDisplay
import magefree.designsystem.theme.MageTheme
import magefree.feature.game.board.BoardAction
import magefree.feature.game.board.ControlButton
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The two piles, driven.
 *
 * **What is worth testing here is that the gestures reach the server, and that they are the same
 * message.** The columns themselves are `TargetPiles`' job and are covered there; what only a driven
 * test can prove is that a card in either column can actually be moved, by either gesture, and that
 * neither gesture invented a second verb — because upstream answers a repeated id by taking the
 * choice back, and a client with two verbs would have got exactly one of them wrong.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w891dp-h411dp")
class TargetPilesOverlayTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val moved = mutableListOf<String>()
    private val actions = mutableListOf<BoardAction>()
    private var collapsed = false

    private fun show(piles: TargetPiles = piles()) {
        composeTestRule.setContent {
            MageTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    TargetPilesOverlay(
                        piles = piles,
                        artFor = null,
                        onMove = { moved += it },
                        onAction = { actions += it },
                        onCollapse = { collapsed = true },
                    )
                }
            }
        }
    }

    @Test
    fun `both piles are on screen, with their sizes`() {
        // "Sacrifice all permanents in the pile of their choice" is a question about sizes as much as
        // contents, and a player splitting eight permanents is counting.
        show()

        composeTestRule.onNodeWithTag(TargetPilesTestTags.AVAILABLE).assertIsDisplayed()
        composeTestRule.onNodeWithTag(TargetPilesTestTags.CHOSEN).assertIsDisplayed()
        composeTestRule.onNodeWithTag(TargetPilesTestTags.count(TargetPilesTestTags.AVAILABLE)).assertIsDisplayed()
        composeTestRule.onNodeWithTag(TargetPilesTestTags.count(TargetPilesTestTags.CHOSEN)).assertIsDisplayed()
    }

    @Test
    fun `tapping a card in either column moves it, with the same message`() {
        // One `chooseTarget` either way. Upstream removes a target sent a second time, so which
        // direction the card went is the server's own answer and never a client decision.
        show()

        composeTestRule.onNodeWithTag(TargetPilesTestTags.card("swamp")).performClick()
        composeTestRule.onNodeWithTag(TargetPilesTestTags.card("forest")).performClick()

        assertEquals(listOf("swamp", "forest"), moved)
    }

    @Test
    fun `dragging a card onto the other column moves it`() {
        // The gesture the piles suggest. It ends over the other column, which is what makes it a move
        // rather than a card being nudged.
        show()

        // One continuous gesture: hold past the long-press threshold, then move. Two separate
        // `performTouchInput` blocks would be two gestures and the drag would never begin.
        composeTestRule.onNodeWithTag(TargetPilesTestTags.card("swamp")).performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + LONG_PRESS_MARGIN_MS)
            repeat(DRAG_STEPS) {
                advanceEventTime(DRAG_STEP_MS)
                moveBy(Offset(x = DRAG_STEP_PX, y = 0f))
            }
            up()
        }
        composeTestRule.waitForIdle()

        assertEquals("the drag must send the same single move a tap does", listOf("swamp"), moved)
    }

    @Test
    fun `the prompt's own buttons are on the overlay and still answer it`() {
        // A candidate the board cannot draw — a player, above all — arrives as one of these. Moving
        // the question here without them would leave some prompts unanswerable.
        show()

        composeTestRule.onNodeWithTag(TargetPilesTestTags.button("Done")).performClick()

        assertEquals(listOf(BoardAction.FinishTargeting), actions)
    }

    @Test
    fun `the board can be read underneath without answering the question`() {
        show()

        composeTestRule.onNodeWithTag(TargetPilesTestTags.COLLAPSE).performClick()

        assertEquals(true, collapsed)
        assertEquals("collapsing is a look, not an answer", emptyList<String>(), moved)
        assertEquals(emptyList<BoardAction>(), actions)
    }

    private fun piles() =
        TargetPiles(
            message = "Select permanents to put in the first pile",
            available = listOf(pileCard("swamp", "Swamp")),
            chosen = listOf(pileCard("forest", "Forest")),
            buttons = listOf(ControlButton(label = "Done", action = BoardAction.FinishTargeting, isPrimary = true)),
        )

    private fun pileCard(
        id: String,
        name: String,
    ) = PileCard(id = id, state = BoardCardState(card = CardDisplay(name = name)), art = null)

    private companion object {
        /** Comfortably past the long-press threshold, so the drag is certain to have begun. */
        const val LONG_PRESS_MARGIN_MS = 200L

        /** Enough steps, far enough, to carry the card clear across into the other column. */
        const val DRAG_STEPS = 10
        const val DRAG_STEP_PX = 60f
        const val DRAG_STEP_MS = 16L
    }
}
