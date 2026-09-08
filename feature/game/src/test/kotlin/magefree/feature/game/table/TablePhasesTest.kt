package magefree.feature.game.table

import magefree.designsystem.component.phase.PhaseBarTurn
import magefree.designsystem.component.phase.PhaseStop
import magefree.designsystem.component.phase.StepIds
import magefree.designsystem.component.phase.standardTurnSteps
import magefree.feature.game.board.BoardStops
import magefree.feature.game.board.TurnStops
import magefree.network.game.GameState
import magefree.network.game.PhaseStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The turn, in the phase bar's own vocabulary.
 *
 * The bar draws fewer steps than a turn has, so this projection has to answer for every step the
 * server can name — including the ones it does not draw, where the honest answer is *nowhere*, and a
 * marker left where it was beats one placed somewhere that is not on screen.
 */
class TablePhasesTest {
    @Test
    fun `every step the server can send maps onto the bar or onto nothing`() {
        // Exhaustive over the enum rather than over a list written out here, so a step added upstream
        // fails this test instead of quietly landing nowhere.
        val drawn = standardTurnSteps().map { it.id }.toSet()

        PhaseStep.entries.forEach { step ->
            val id = phaseBarState(stateAt(step)).currentStepId
            if (id != null) {
                assertTrue("$step mapped to '$id', which the bar does not draw", id in drawn)
            }
        }
    }

    @Test
    fun `the steps nobody receives priority in have no place on the bar`() {
        // Untap and cleanup are not drawn, so a marker there would be a position the game passes
        // through without ever stopping. Unknown is the same answer for a different reason: this
        // build does not recognise the step, and guessing is worse than saying nothing.
        listOf(PhaseStep.Untap, PhaseStep.Cleanup, PhaseStep.Unknown).forEach { step ->
            assertNull("$step should not put a marker on the bar", phaseBarState(stateAt(step)).currentStepId)
        }
    }

    @Test
    fun `first-strike damage shares the combat damage step, because the bar has one`() {
        // The alternative is no marker at all through a step that can decide the game, which is worse
        // than a marker that is a little coarse.
        assertEquals(
            StepIds.COMBAT_DAMAGE,
            phaseBarState(stateAt(PhaseStep.FirstCombatDamage)).currentStepId,
        )
    }

    @Test
    fun `whose turn it is comes from the active seat, not from who holds priority`() {
        // Priority moves within a turn several times; the bar says *whose turn*, which does not.
        val yours = stateAt(PhaseStep.PrecombatMain).copy(activePlayerId = "p-you", viewerHasPriority = false)
        val theirs = stateAt(PhaseStep.PrecombatMain).copy(activePlayerId = "p-opp", viewerHasPriority = true)

        assertEquals(PhaseBarTurn.Yours, phaseBarState(yours).turn)
        assertEquals(PhaseBarTurn.Opponents, phaseBarState(theirs).turn)
    }

    @Test
    fun `a game that has not named an active seat belongs to nobody in particular`() {
        // Both null would compare equal, and "it is your turn" is the wrong thing to say about a game
        // that has not started.
        val unnamed = stateAt(PhaseStep.PrecombatMain).copy(activePlayerId = null, viewerPlayerId = null)

        assertEquals(PhaseBarTurn.Opponents, phaseBarState(unnamed).turn)
    }

    @Test
    fun `the combat steps are locked stops on either side of the turn`() {
        // Not a choice this app makes: `SkipPrioritySteps.isPhaseStepSet` has seven cases and a
        // `default: return true`, so the server always gives priority in declare attackers, declare
        // blockers and combat damage. That is the mandatory window after blockers and before damage,
        // and it needs no condition on there having been an attack — with no attackers those steps do
        // not happen at all.
        val theirTurn = stateAt(PhaseStep.Upkeep).copy(activePlayerId = "p-opp")

        listOf(stateAt(PhaseStep.Upkeep), theirTurn).forEach { state ->
            val locked = lockedStops(state)
            assertTrue("declare attackers always stops", StepIds.DECLARE_ATTACKERS in locked)
            assertTrue("declare blockers always stops", StepIds.DECLARE_BLOCKERS in locked)
            assertTrue("combat damage always stops", StepIds.COMBAT_DAMAGE in locked)
        }
    }

    @Test
    fun `your own main phases are locked, and an opponent's are not`() {
        // A turn you cannot act in is not a turn you are playing — but that is only true of *your*
        // turn. Watching an opponent's main phase is exactly the thing a player turns off.
        val yours = lockedStops(stateAt(PhaseStep.PrecombatMain))
        val theirs = lockedStops(stateAt(PhaseStep.PrecombatMain).copy(activePlayerId = "p-opp"))

        assertTrue(StepIds.PRECOMBAT_MAIN in yours)
        assertTrue(StepIds.POSTCOMBAT_MAIN in yours)
        assertFalse(StepIds.PRECOMBAT_MAIN in theirs)
        assertFalse(StepIds.POSTCOMBAT_MAIN in theirs)
    }

    @Test
    fun `a locked step is drawn as a standing stop the player cannot press away`() {
        // The bar and the server have to agree: a step the server will always stop at must not offer a
        // control that says otherwise.
        val steps =
            phaseBarState(
                stateAt(PhaseStep.PrecombatMain),
                locked = lockedStops(stateAt(PhaseStep.PrecombatMain)),
            ).steps.associateBy { it.id }

        val main1 = steps.getValue(StepIds.PRECOMBAT_MAIN)
        assertEquals(PhaseStop.Always, main1.stop)
        assertFalse("a rule is not a setting", main1.stoppable)
        assertEquals(PhaseStop.Always, steps.getValue(StepIds.DECLARE_BLOCKERS).stop)
        assertFalse(steps.getValue(StepIds.DECLARE_BLOCKERS).stoppable)
    }

    @Test
    fun `an unlocked step still shows what the player set`() {
        val stops = BoardStops(yours = TurnStops(mapOf(StepIds.UPKEEP to PhaseStop.Once)))

        val steps =
            phaseBarState(
                stateAt(PhaseStep.Upkeep),
                stops = stops,
                locked = lockedStops(stateAt(PhaseStep.Upkeep)),
            ).steps.associateBy { it.id }

        assertEquals(PhaseStop.Once, steps.getValue(StepIds.UPKEEP).stop)
        assertTrue(steps.getValue(StepIds.UPKEEP).stoppable)
    }

    private fun stateAt(step: PhaseStep) =
        GameState(
            gameId = "g-1",
            step = step,
            activePlayerId = "p-you",
            viewerPlayerId = "p-you",
            hasSnapshot = true,
        )
}
