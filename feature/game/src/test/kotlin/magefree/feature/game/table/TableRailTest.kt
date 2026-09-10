package magefree.feature.game.table

import magefree.designsystem.component.phase.PhaseBarTurn
import magefree.designsystem.component.phase.PhaseStop
import magefree.designsystem.component.phase.StepIds
import magefree.designsystem.component.phase.standardRailSteps
import magefree.feature.game.board.BoardStops
import magefree.feature.game.board.TurnSide
import magefree.network.game.GameState
import magefree.network.game.PhaseStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The turn, in the rail's own vocabulary.
 *
 * The rail draws fewer steps than a turn has, so this projection has to answer for every step the
 * server can name — including the ones it does not draw, where the honest answer is *nowhere*, and a
 * marker left where it was beats one placed somewhere that is not on screen.
 *
 * **And a mark now belongs to a side.** That is 0123's reversal of 0115, and most of what is worth
 * asserting here is on that seam: which column a mark lands in, and which locks apply to which column.
 */
class TableRailTest {
    @Test
    fun `every step the server can send maps onto the rail or onto nothing`() {
        // Exhaustive over the enum rather than over a list written out here, so a step added upstream
        // fails this test instead of quietly landing nowhere.
        val drawn = standardRailSteps().map { it.id }.toSet()

        PhaseStep.entries.forEach { step ->
            val id = phaseRailState(stateAt(step)).currentStepId
            if (id != null) {
                assertTrue("$step mapped to '$id', which the rail does not draw", id in drawn)
            }
        }
    }

    @Test
    fun `the steps nobody receives priority in have no place on the rail`() {
        // Untap and cleanup are not drawn, so a marker there would be a position the game passes
        // through without ever stopping. Unknown is the same answer for a different reason: this
        // build does not recognise the step, and guessing is worse than saying nothing.
        listOf(PhaseStep.Untap, PhaseStep.Cleanup, PhaseStep.Unknown).forEach { step ->
            assertNull("$step should not put a marker on the rail", phaseRailState(stateAt(step)).currentStepId)
        }
    }

    @Test
    fun `first-strike damage shares the combat damage step, because the rail has one`() {
        // The alternative is no marker at all through a step that can decide the game, which is worse
        // than a marker that is a little coarse.
        assertEquals(
            StepIds.COMBAT_DAMAGE,
            phaseRailState(stateAt(PhaseStep.FirstCombatDamage)).currentStepId,
        )
    }

    @Test
    fun `whose turn it is comes from the active seat, and it decides which column is lit`() {
        // Priority moves within a turn several times; the rail says *whose turn*, which does not.
        val yours = stateAt(PhaseStep.PrecombatMain).copy(activePlayerId = "p-you", viewerHasPriority = false)
        val theirs = stateAt(PhaseStep.PrecombatMain).copy(activePlayerId = "p-opp", viewerHasPriority = true)

        assertEquals(PhaseBarTurn.Yours, phaseRailState(yours).turn)
        assertEquals(PhaseBarTurn.Opponents, phaseRailState(theirs).turn)
    }

    @Test
    fun `a game that has not named an active seat belongs to nobody in particular`() {
        // Both null would compare equal, and "it is your turn" is the wrong thing to say about a game
        // that has not started.
        val unnamed = stateAt(PhaseStep.PrecombatMain).copy(activePlayerId = null, viewerPlayerId = null)

        assertEquals(PhaseBarTurn.Opponents, phaseRailState(unnamed).turn)
    }

    @Test
    fun `the combat steps are locked in both columns`() {
        // Not a choice this app makes: `SkipPrioritySteps.isPhaseStepSet` has seven cases and a
        // `default: return true`, so the server always gives priority in declare attackers, declare
        // blockers and combat damage. That is the mandatory window after blockers and before damage,
        // and it needs no condition on there having been an attack — with no attackers those steps do
        // not happen at all.
        val steps = phaseRailState(stateAt(PhaseStep.Upkeep)).steps.associateBy { it.id }

        listOf(StepIds.DECLARE_ATTACKERS, StepIds.DECLARE_BLOCKERS, StepIds.COMBAT_DAMAGE).forEach { id ->
            val step = steps.getValue(id)
            assertEquals("$id always stops on your turn", PhaseStop.Always, step.yours.stop)
            assertEquals("$id always stops on theirs", PhaseStop.Always, step.opponents.stop)
            assertFalse("a rule is not a setting", step.yours.stoppable)
            assertFalse("a rule is not a setting", step.opponents.stoppable)
        }
    }

    @Test
    fun `your own main phases are locked in your column and free in theirs`() {
        // A turn you cannot act in is not a turn you are playing — but that is only true of *your*
        // turn, and watching an opponent's main phase is exactly the thing a player turns off.
        //
        // **The rail makes this simpler than the bar could.** The bar had to read the snapshot to know
        // whether the lock applied right now, because its one row stood for whichever turn was being
        // played. A column *is* a side, so the lock belongs to the column and the turn in progress has
        // nothing to do with it.
        val steps = phaseRailState(stateAt(PhaseStep.PrecombatMain)).steps.associateBy { it.id }

        listOf(StepIds.PRECOMBAT_MAIN, StepIds.POSTCOMBAT_MAIN).forEach { id ->
            assertEquals(PhaseStop.Always, steps.getValue(id).yours.stop)
            assertFalse("a rule is not a setting", steps.getValue(id).yours.stoppable)
            assertTrue("an opponent's main phase is a choice", steps.getValue(id).opponents.stoppable)
        }
    }

    @Test
    fun `the locks do not move when the turn does`() {
        // The other half of the same point, stated as the property that would have failed before: the
        // rail drawn on an opponent's turn carries exactly the same locks.
        val onYours = phaseRailState(stateAt(PhaseStep.PrecombatMain)).steps.associateBy { it.id }
        val onTheirs =
            phaseRailState(stateAt(PhaseStep.PrecombatMain).copy(activePlayerId = "p-opp"))
                .steps
                .associateBy { it.id }

        assertEquals(onYours.getValue(StepIds.PRECOMBAT_MAIN), onTheirs.getValue(StepIds.PRECOMBAT_MAIN))
    }

    @Test
    fun `a mark lands in the column of the side it was set for, and nowhere else`() {
        // **0115 merged the sides and this un-merges them.** A mark on your own end step says nothing
        // about theirs; that distinction is the whole reason the rail has two columns, and the reason
        // upstream's two `SkipPrioritySteps` stop being sent the same thing twice.
        val stops = BoardStops().withMode(TurnSide.Yours, StepIds.END_TURN, PhaseStop.Always)

        val steps = phaseRailState(stateAt(PhaseStep.EndTurn), stops = stops).steps.associateBy { it.id }

        assertEquals(PhaseStop.Always, steps.getValue(StepIds.END_TURN).yours.stop)
        assertEquals(PhaseStop.None, steps.getValue(StepIds.END_TURN).opponents.stop)
    }

    @Test
    fun `an unlocked step shows what the player set, in either column`() {
        val stops =
            BoardStops()
                .withMode(TurnSide.Theirs, StepIds.UPKEEP, PhaseStop.Once)
                .withMode(TurnSide.Yours, StepIds.DRAW, PhaseStop.Always)

        val steps = phaseRailState(stateAt(PhaseStep.Upkeep), stops = stops).steps.associateBy { it.id }

        assertEquals(PhaseStop.Once, steps.getValue(StepIds.UPKEEP).opponents.stop)
        assertTrue(steps.getValue(StepIds.UPKEEP).opponents.stoppable)
        assertEquals(PhaseStop.Always, steps.getValue(StepIds.DRAW).yours.stop)
        assertTrue(steps.getValue(StepIds.DRAW).yours.stoppable)
    }

    @Test
    fun `the end step starts marked on both columns, and is pressable`() {
        // 0123's one default, and the line between a default and a rule: it starts on, and it comes
        // off. The main phases start on and stay on, which is what makes them a rule.
        val steps = phaseRailState(stateAt(PhaseStep.Upkeep)).steps.associateBy { it.id }
        val end = steps.getValue(StepIds.END_TURN)

        assertEquals(PhaseStop.Always, end.yours.stop)
        assertEquals(PhaseStop.Always, end.opponents.stop)
        assertTrue("a default the player cannot remove is a rule wearing a default's clothes", end.yours.stoppable)
        assertTrue(end.opponents.stoppable)
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
