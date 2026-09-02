package magefree.designsystem.component.phase

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import magefree.designsystem.theme.MageTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The phase bar.
 *
 * The assertions that matter are about **which steps accept a stop**. Upstream keeps stops for exactly
 * seven steps per side, so a bar that offered one anywhere else would be handing the player a control
 * the server discards — the same defect the table room's deck picker was.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w891dp-h411dp")
class PhaseBarTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private var toggled: PhaseBarStep? = null

    private fun show(state: PhaseBarState) {
        composeTestRule.setContent {
            MageTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    PhaseBar(state = state, onToggleStop = { toggled = it })
                }
            }
        }
    }

    @Test
    fun `the bar shows the turn as a sequence of steps`() {
        show(PhaseBarState(steps = standardTurnSteps(), currentStepId = StepIds.PRECOMBAT_MAIN))

        composeTestRule.onNodeWithTag(PhaseBarTestTags.BAR).assertIsDisplayed()
        listOf("UP", "DR", "M1", "BC", "AT", "BL", "DM", "EC", "M2", "END").forEach { label ->
            composeTestRule.onNodeWithText(label).assertIsDisplayed()
        }
    }

    @Test
    fun `exactly the seven steps upstream accepts a stop for are stoppable`() {
        // Upstream's SkipPrioritySteps covers upkeep, draw, main1, beginning of combat, end of combat,
        // main2 and end step. Offering a stop anywhere else would be a control the server throws away.
        val stoppable = standardTurnSteps().filter { it.stoppable }.map { it.id }

        assertEquals(
            listOf(
                StepIds.UPKEEP,
                StepIds.DRAW,
                StepIds.PRECOMBAT_MAIN,
                StepIds.BEGIN_COMBAT,
                StepIds.END_COMBAT,
                StepIds.POSTCOMBAT_MAIN,
                StepIds.END_TURN,
            ),
            stoppable,
        )
    }

    @Test
    fun `both main phases start stopped, which is upstream's own default`() {
        val stopped = standardTurnSteps().filter { it.stopSet }.map { it.id }

        assertEquals(listOf(StepIds.PRECOMBAT_MAIN, StepIds.POSTCOMBAT_MAIN), stopped)
    }

    @Test
    fun `a step the server accepts no stop for cannot be given one`() {
        // Combat steps are governed by separate flags upstream, not by the per-step set, so a stop
        // marked here would be a lie about what the game will do.
        val attackers = standardTurnSteps(stops = setOf(StepIds.DECLARE_ATTACKERS)).first { it.id == StepIds.DECLARE_ATTACKERS }

        assertTrue("declare attackers must not be stoppable from the bar", !attackers.stoppable)
        assertTrue("a stop asked for on an unstoppable step must not stick", !attackers.stopSet)
    }

    @Test
    fun `tapping a stoppable step raises it`() {
        show(PhaseBarState(steps = standardTurnSteps(), currentStepId = StepIds.UPKEEP))

        composeTestRule.onNodeWithText("M2").performClick()

        assertEquals(StepIds.POSTCOMBAT_MAIN, toggled?.id)
    }

    @Test
    fun `tapping a step the server would ignore raises nothing`() {
        show(PhaseBarState(steps = standardTurnSteps(), currentStepId = StepIds.UPKEEP))

        composeTestRule.onNodeWithText("AT").performClick()

        assertNull("an unstoppable step must not raise a toggle the server would discard", toggled)
    }

    @Test
    fun `the current step is marked`() {
        show(PhaseBarState(steps = standardTurnSteps(), currentStepId = StepIds.DECLARE_BLOCKERS))

        composeTestRule.onNodeWithTag(PhaseBarTestTags.CURRENT, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `a set stop is marked on the step it governs`() {
        // A stop is a standing instruction that silently governs turns the player is not looking at,
        // so it belongs on the step rather than in a settings screen.
        show(PhaseBarState(steps = standardTurnSteps(), currentStepId = StepIds.UPKEEP))

        composeTestRule
            .onNodeWithTag(PhaseBarTestTags.stopTag(StepIds.POSTCOMBAT_MAIN), useUnmergedTree = true)
            .assertIsDisplayed()
    }

    @Test
    fun `a step with no stop carries no marker`() {
        show(
            PhaseBarState(
                steps = standardTurnSteps(stops = emptySet()),
                currentStepId = StepIds.UPKEEP,
            ),
        )

        composeTestRule
            .onNodeWithTag(PhaseBarTestTags.stopTag(StepIds.POSTCOMBAT_MAIN), useUnmergedTree = true)
            .assertDoesNotExist()
    }

    @Test
    fun `the bar renders on either side of the turn`() {
        show(
            PhaseBarState(
                steps = standardTurnSteps(),
                currentStepId = StepIds.UPKEEP,
                turn = PhaseBarTurn.Opponents,
            ),
        )

        composeTestRule.onNodeWithTag(PhaseBarTestTags.BAR).assertIsDisplayed()
        composeTestRule.onNodeWithTag(PhaseBarTestTags.CURRENT, useUnmergedTree = true).assertIsDisplayed()
    }
}
