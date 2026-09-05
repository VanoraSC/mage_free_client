package magefree.app.catalog

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import magefree.designsystem.card.CardPreviewTestTags
import magefree.designsystem.theme.MageTheme
import magefree.feature.game.table.CAST_LABEL
import magefree.feature.game.table.HandTestTags
import magefree.feature.game.table.PLAY_LABEL
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The mock is wired, end to end.
 *
 * Written for the reason the battlefield preview's test was: the hand was built, tested and wired into
 * the board while the screen that shows it passed it nothing, and every component test passed. These
 * assertions are deliberately shallow — press a card, see a preview; press Play, see it reported — and
 * they cover the joins that component tests cannot see.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w891dp-h411dp")
class CastMockScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun show() {
        composeTestRule.setContent {
            MageTheme { CastMockScreen(onExit = {}) }
        }
    }

    @Test
    fun `pressing a hand card opens it`() {
        show()

        composeTestRule.onNodeWithTag(HandTestTags.card("mock-elves")).performClick()

        composeTestRule.onNodeWithTag(CardPreviewTestTags.CARD).assertIsDisplayed()
        composeTestRule.onNodeWithText("Llanowar Elves").assertIsDisplayed()
    }

    @Test
    fun `a land offers Play and a spell offers Cast`() {
        show()

        composeTestRule.onNodeWithTag(HandTestTags.card("mock-forest")).performClick()
        composeTestRule.onNodeWithText(PLAY_LABEL).assertIsDisplayed()

        // Top-left rather than the centre: the scrim fills the screen, so its centre is behind the
        // card and the panel, and a press there is caught by them instead.
        composeTestRule.onNodeWithTag(CardPreviewTestTags.SCRIM).performTouchInput { click(topLeft) }
        composeTestRule.onNodeWithTag(HandTestTags.card("mock-elves")).performClick()
        composeTestRule.onNodeWithText(CAST_LABEL).assertIsDisplayed()
    }

    @Test
    fun `a card the server has not offered opens with nothing to press`() {
        show()

        composeTestRule.onNodeWithTag(HandTestTags.card("mock-dragon")).performClick()

        composeTestRule.onNodeWithTag(CardPreviewTestTags.CARD).assertIsDisplayed()
        composeTestRule.onNodeWithTag(CardPreviewTestTags.ACTION).assertDoesNotExist()
    }

    @Test
    fun `acting closes the card and reports what it would have submitted`() {
        show()

        composeTestRule.onNodeWithTag(HandTestTags.card("mock-forest")).performClick()
        composeTestRule.onNodeWithTag(CardPreviewTestTags.ACTION).performClick()

        composeTestRule.onNodeWithTag(CardPreviewTestTags.CARD).assertDoesNotExist()
        composeTestRule.onNodeWithText("$PLAY_LABEL mock-forest").assertIsDisplayed()
    }

    @Test
    fun `pressing the scrim closes it without acting`() {
        show()

        composeTestRule.onNodeWithTag(HandTestTags.card("mock-forest")).performClick()
        // Top-left rather than the centre: the scrim fills the screen, so its centre is behind the
        // card and the panel, and a press there is caught by them instead.
        composeTestRule.onNodeWithTag(CardPreviewTestTags.SCRIM).performTouchInput { click(topLeft) }

        composeTestRule.onNodeWithTag(CardPreviewTestTags.CARD).assertDoesNotExist()
        composeTestRule.onNodeWithText("$PLAY_LABEL mock-forest").assertDoesNotExist()
    }
}
