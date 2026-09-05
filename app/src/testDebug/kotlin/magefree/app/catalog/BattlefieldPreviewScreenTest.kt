package magefree.app.catalog

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import magefree.designsystem.theme.MageTheme
import magefree.feature.game.table.HandTestTags
import magefree.feature.game.table.handCards
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The preview surface actually shows what it is there to show.
 *
 * **This exists because of a defect it would have caught.** The hand was built, tested and wired into
 * the board, and every one of those tests passed — while the preview screen quietly never passed a
 * hand to it, so the one surface anybody looks at showed none. The component tests all pointed at the
 * component; nothing pointed at the assembly.
 *
 * So the assertion here is deliberately shallow and deliberately end-to-end: for each fixture board
 * that has a hand, the preview screen draws it. It is not testing the hand — that is done properly
 * next door — it is testing that the wire is connected.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w891dp-h411dp")
class BattlefieldPreviewScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun show() {
        composeTestRule.setContent {
            MageTheme { BattlefieldPreviewScreen(onExit = {}) }
        }
    }

    @Test
    fun `every fixture board with a hand draws one`() {
        show()

        Boards.forEachIndexed { index, board ->
            val expected = handCards(board.state)
            if (expected.isNotEmpty()) {
                composeTestRule
                    .onNodeWithTag(HandTestTags.card(expected.first().id))
                    .assertIsDisplayed()
            }
            // The label doubles as the cycle control, so pressing it moves to the next board.
            if (index < Boards.lastIndex) {
                composeTestRule.onNodeWithText(board.label).performClick()
            }
        }
    }

    @Test
    fun `at least one fixture board has a hand large enough to overlap`() {
        // The hand's whole rule is what happens when it outgrows its width, and a preview that only
        // ever held five cards could not show it. Losing that board to a tidy-up would take the rule's
        // only eyes-on with it, silently.
        val largest = Boards.maxOf { handCards(it.state).size }

        assert(largest > 7) { "the largest fixture hand is $largest, which fits without overlapping" }
    }
}
