package magefree.designsystem.component.prompt

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import magefree.designsystem.theme.MageTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Prompt, in its three states.
 *
 * The assertion that matters is the **size budget**. "Never blocks the board" is the requirement the
 * whole component is designed around, and it is the one that would quietly stop being true — a longer
 * headline here, an extra action there, and a prompt that is supposed to be leaving the board alone is
 * sitting on top of the permanents it is asking about. So it is measured against content chosen to
 * break it, not against a comfortable example.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w891dp-h411dp")
class PromptTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private var chosen: PromptAction? = null

    private fun show(state: PromptState) {
        composeTestRule.setContent {
            MageTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    Prompt(state = state, onAction = { chosen = it })
                }
            }
        }
    }

    @Test
    fun `idle carries the phase and whose priority it is`() {
        // Never empty: these are the two facts a player checks constantly, and an empty Idle turns
        // every phase question into a hunt somewhere else on screen.
        show(PromptState.Idle(phase = "Main phase 1", priority = "Your priority"))

        composeTestRule.onNodeWithTag(PromptTestTags.IDLE).assertIsDisplayed()
        composeTestRule.onNodeWithText("Main phase 1").assertIsDisplayed()
        composeTestRule.onNodeWithText("Your priority").assertIsDisplayed()
    }

    @Test
    fun `asking states the question in the server's own words`() {
        show(
            PromptState.Asking(
                question = "Do you want to mulligan?",
                actions = listOf(PromptAction("Mulligan", PromptEmphasis.Primary), PromptAction("Keep")),
            ),
        )

        composeTestRule.onNodeWithTag(PromptTestTags.ASKING).assertIsDisplayed()
        composeTestRule.onNodeWithText("Do you want to mulligan?").assertIsDisplayed()
    }

    @Test
    fun `the server's own button wording is what the player sees`() {
        // Upstream sends "Mulligan"/"Keep" rather than yes/no for this one, and its phrasing is part
        // of the question. A component that hard-coded Yes and No would be answering a different one.
        show(
            PromptState.Asking(
                question = "Do you want to mulligan?",
                actions = listOf(PromptAction("Mulligan", PromptEmphasis.Primary), PromptAction("Keep")),
            ),
        )

        composeTestRule.onNodeWithText("Mulligan").assertIsDisplayed()
        composeTestRule.onNodeWithText("Keep").assertIsDisplayed()
        composeTestRule.onNodeWithText("Yes").assertDoesNotExist()
        composeTestRule.onNodeWithText("No").assertDoesNotExist()
    }

    @Test
    fun `an action raises itself, so a prompt cannot answer the wrong question`() {
        val keep = PromptAction("Keep")
        show(
            PromptState.Asking(
                question = "Do you want to mulligan?",
                actions = listOf(PromptAction("Mulligan", PromptEmphasis.Primary), keep),
            ),
        )

        composeTestRule.onNodeWithText("Keep").performClick()

        assertEquals(keep, chosen)
    }

    @Test
    fun `cancel is absent when declining is not real`() {
        // Some prompts cannot be declined, and a Cancel the server discards is worse than none.
        show(
            PromptState.Asking(
                question = "Choose a colour",
                actions = listOf(PromptAction("White"), PromptAction("Blue")),
            ),
        )

        composeTestRule.onNodeWithText("Cancel").assertDoesNotExist()
    }

    @Test
    fun `board-interactive shows what is being chosen and how far through it is`() {
        show(
            PromptState.BoardInteractive(
                headline = "Choose targets",
                progress = "2 of 3 targets",
                actions = listOf(PromptAction("Done", PromptEmphasis.Primary)),
            ),
        )

        composeTestRule.onNodeWithTag(PromptTestTags.BOARD_INTERACTIVE).assertIsDisplayed()
        composeTestRule.onNodeWithTag(PromptTestTags.PROGRESS).assertIsDisplayed()
        composeTestRule.onNodeWithText("2 of 3 targets").assertIsDisplayed()
    }

    @Test
    fun `board-interactive stays inside its budget however hostile the content`() {
        // Chosen to break it: a headline and a progress line far longer than anything real, and more
        // actions than a prompt should offer. If any of that can push the Prompt over its budget, the
        // board is being covered by the thing that promised not to.
        show(
            PromptState.BoardInteractive(
                headline =
                    "Choose up to three target creatures an opponent controls that entered the " +
                        "battlefield this turn and are not enchanted by an Aura you control",
                progress = "2 of 3 targets chosen, 1 remaining, 4 legal candidates on the battlefield",
                actions =
                    listOf(
                        PromptAction("Confirm the selection", PromptEmphasis.Primary),
                        PromptAction("Cancel this choice", PromptEmphasis.Cancel),
                        PromptAction("Something else entirely"),
                    ),
            ),
        )

        val density = Density(composeTestRule.density.density)
        val heightDp =
            with(density) {
                composeTestRule
                    .onNodeWithTag(PromptTestTags.PROMPT)
                    .fetchSemanticsNode()
                    .size.height
                    .toDp()
            }

        assertTrue(
            "the board-interactive prompt grew to $heightDp, past its $BoardInteractiveMaxHeight budget — " +
                "it is covering the board it is asking about",
            heightDp <= BoardInteractiveMaxHeight + 1.dp,
        )
    }

    @Test
    fun `the asking state is allowed to be larger, because nothing is being touched`() {
        // The budget is board-interactive's alone. Asking can afford room: the player is answering
        // here rather than on the board, so covering the board costs nothing.
        show(
            PromptState.Asking(
                question = "Distribute 5 damage among the blocking creatures",
                detail = "Each blocker must be assigned at least 1 damage before any excess is assigned.",
                actions = listOf(PromptAction("Confirm", PromptEmphasis.Primary), PromptAction("Cancel", PromptEmphasis.Cancel)),
            ),
        )

        val density = Density(composeTestRule.density.density)
        val heightDp =
            with(density) {
                composeTestRule
                    .onNodeWithTag(PromptTestTags.PROMPT)
                    .fetchSemanticsNode()
                    .size.height
                    .toDp()
            }

        assertTrue("the asking state rendered nothing", heightDp > 0.dp)
    }

    @Test
    fun `only one state is ever on screen`() {
        show(PromptState.Idle(phase = "Upkeep"))

        composeTestRule.onNodeWithTag(PromptTestTags.IDLE).assertIsDisplayed()
        composeTestRule.onNodeWithTag(PromptTestTags.ASKING).assertDoesNotExist()
        composeTestRule.onNodeWithTag(PromptTestTags.BOARD_INTERACTIVE).assertDoesNotExist()
    }
}
