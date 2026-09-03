package magefree.feature.game.cast

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import magefree.designsystem.component.prompt.AmountPickerTestTags
import magefree.designsystem.theme.MageTheme
import magefree.network.game.GamePrompt
import magefree.network.game.PromptOptions
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The cast surface.
 *
 * The assertion that matters is that **a way out appears only where the server accepts one**. Offering
 * it elsewhere leaves the player pressing a button that does nothing while the game waits on them —
 * and the prompt where that is most tempting, and most wrong, is the one asking for X.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w891dp-h411dp")
class CastPromptSurfaceTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val events = mutableListOf<CastPromptEvent>()

    private fun show(prompt: GamePrompt) {
        composeTestRule.setContent {
            MageTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    CastPrompt(prompt = prompt, onEvent = { events += it })
                }
            }
        }
    }

    @Test
    fun `a mana prompt shows the way out, and taking it reports one`() {
        show(GamePrompt.PlayMana(message = "Pay {2}{R}"))

        composeTestRule.onNodeWithText("Pay {2}{R}").assertIsDisplayed()
        composeTestRule.onNodeWithText(CANCEL_PAYMENT).performClick()

        assertEquals(listOf(CastPromptEvent.Exit), events)
    }

    @Test
    fun `an amount prompt offers no way out, and says why instead`() {
        // The one point of no return in a cast. A cancel here would be a control the server discards;
        // silence would be a mystery. The explanation is the affordance.
        show(GamePrompt.GetAmount(message = "Announce the value for {X}", min = 0, max = 5))

        composeTestRule.onNodeWithText(CANCEL_PAYMENT).assertDoesNotExist()
        composeTestRule.onNodeWithText(DONE_CHOOSING).assertDoesNotExist()
        composeTestRule.onNodeWithText(AMOUNT_IS_FINAL).assertIsDisplayed()
    }

    @Test
    fun `an amount is picked within the server's bounds and announced`() {
        show(GamePrompt.GetAmount(message = "Announce the value for {X}", min = 2, max = 4))

        // Seeded at the minimum: the least committal answer, and there is no way out to fall back on.
        composeTestRule.onNodeWithTag(AmountPickerTestTags.VALUE, useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("2").assertIsDisplayed()

        composeTestRule.onNodeWithTag(AmountPickerTestTags.MORE, useUnmergedTree = true).performClick()
        composeTestRule.onNodeWithText(ANNOUNCE).performClick()

        assertEquals(listOf(CastPromptEvent.Amount(3)), events)
    }

    @Test
    fun `the picker cannot produce a value the server would refuse`() {
        // Upstream re-asks on an out-of-range answer, so a control that could produce one would cost
        // the player a round trip for nothing.
        show(GamePrompt.GetAmount(message = "Announce the value for {X}", min = 0, max = 1))

        repeat(4) { composeTestRule.onNodeWithTag(AmountPickerTestTags.MORE, useUnmergedTree = true).performClick() }
        composeTestRule.onNodeWithText(ANNOUNCE).performClick()

        assertEquals(listOf(CastPromptEvent.Amount(1)), events)
    }

    @Test
    fun `an optional cost is answered in the server's own words`() {
        // Upstream phrases these itself, and the phrasing is part of the question — a Yes/No pair
        // would be answering a differently-worded one.
        show(
            GamePrompt.Ask(
                message = "Pay the kicker cost?",
                options =
                    PromptOptions(
                        text =
                            mapOf(
                                PromptOptions.LEFT_BUTTON_TEXT to "Kick it",
                                PromptOptions.RIGHT_BUTTON_TEXT to "Just the spell",
                            ),
                    ),
            ),
        )

        composeTestRule.onNodeWithText("Kick it").assertIsDisplayed()
        composeTestRule.onNodeWithText("Just the spell").performClick()

        assertEquals(
            "the negative answer must not read as the affirmative — that pays a cost the player declined",
            listOf(CastPromptEvent.Answer(affirmative = false)),
            events,
        )
    }

    @Test
    fun `a special mana action is offered while the server offers it`() {
        show(
            GamePrompt.PlayMana(
                message = "Pay {3}",
                options = PromptOptions(text = mapOf(PromptOptions.SPECIAL_BUTTON to "Convoke")),
            ),
        )

        composeTestRule.onNodeWithText("Convoke").performClick()

        assertEquals(listOf(CastPromptEvent.Special), events)
    }

    @Test
    fun `a required target offers nothing to press instead`() {
        show(GamePrompt.Target(message = "Choose a target", isRequired = true))

        composeTestRule.onNodeWithText("Choose a target").assertIsDisplayed()
        composeTestRule.onNodeWithText(DONE_CHOOSING).assertDoesNotExist()
        composeTestRule.onNodeWithText(CANCEL_PAYMENT).assertDoesNotExist()
    }

    @Test
    fun `an optional target is finished rather than cancelled`() {
        show(GamePrompt.Target(message = "Choose up to two targets", isRequired = false))

        composeTestRule.onNodeWithText(DONE_CHOOSING).performClick()

        assertEquals(listOf(CastPromptEvent.Exit), events)
    }
}
