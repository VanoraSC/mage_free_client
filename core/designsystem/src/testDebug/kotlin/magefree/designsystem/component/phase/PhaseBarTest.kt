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
    fun `the bar starts with no stops, because the stops belong to the player`() {
        // It used to default both main phases on, mirroring upstream's own defaults. The caller passes
        // what the player has set now, and which stops are *rules* is the caller's question too — see
        // `locked`, which is how the mains come back on the player's own turn.
        assertEquals(emptyList<String>(), standardTurnSteps().filter { it.stop != PhaseStop.None }.map { it.id })
    }

    @Test
    fun `a locked step stops and cannot be pressed`() {
        // A stop that is a rule rather than a setting: drawn as always stopping, and inert. Your own
        // main phases are the case — a turn you cannot act in is not a turn you are playing.
        val main = standardTurnSteps(locked = setOf(StepIds.PRECOMBAT_MAIN)).first { it.id == StepIds.PRECOMBAT_MAIN }

        assertEquals(PhaseStop.Always, main.stop)
        assertTrue("a rule is not a control", !main.stoppable)
    }

    @Test
    fun `a step the server accepts no stop for cannot be given one`() {
        // Combat steps are governed by separate flags upstream, not by the per-step set, so a stop
        // marked here would be a lie about what the game will do.
        val attackers =
            standardTurnSteps(stops = mapOf(StepIds.DECLARE_ATTACKERS to PhaseStop.Always))
                .first { it.id == StepIds.DECLARE_ATTACKERS }

        assertTrue("declare attackers must not be stoppable from the bar", !attackers.stoppable)
        assertEquals("a stop asked for on an unstoppable step must not stick", PhaseStop.None, attackers.stop)
    }

    @Test
    fun `the two kinds of stop are told apart`() {
        // One that fires once and clears itself is a different promise from one that fires every turn,
        // and a player setting them a step apart has to see which they set.
        show(
            PhaseBarState(
                steps =
                    standardTurnSteps(
                        stops = mapOf(StepIds.UPKEEP to PhaseStop.Once, StepIds.DRAW to PhaseStop.Always),
                    ),
                currentStepId = StepIds.END_TURN,
            ),
        )

        composeTestRule
            .onNodeWithTag(PhaseBarTestTags.onceStopTag(StepIds.UPKEEP), useUnmergedTree = true)
            .assertExists()
        composeTestRule
            .onNodeWithTag(PhaseBarTestTags.stopTag(StepIds.DRAW), useUnmergedTree = true)
            .assertExists()
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
        show(
            PhaseBarState(
                steps = standardTurnSteps(stops = mapOf(StepIds.POSTCOMBAT_MAIN to PhaseStop.Always)),
                currentStepId = StepIds.UPKEEP,
            ),
        )

        composeTestRule
            .onNodeWithTag(PhaseBarTestTags.stopTag(StepIds.POSTCOMBAT_MAIN), useUnmergedTree = true)
            .assertIsDisplayed()
    }

    @Test
    fun `a step with no stop carries no marker`() {
        show(
            PhaseBarState(
                steps = standardTurnSteps(),
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
