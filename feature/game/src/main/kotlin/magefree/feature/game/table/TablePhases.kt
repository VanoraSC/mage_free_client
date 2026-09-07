package magefree.feature.game.table

import magefree.designsystem.component.phase.PhaseBarState
import magefree.designsystem.component.phase.PhaseBarTurn
import magefree.designsystem.component.phase.StepIds
import magefree.designsystem.component.phase.standardTurnSteps
import magefree.network.game.GameState
import magefree.network.game.PhaseStep

/*
 * Where the turn is, in the bar's own vocabulary.
 *
 * **The bar shows fewer steps than a turn has**, and that is the bar's decision rather than this
 * projection's: untap and cleanup give nobody priority, and first-strike damage exists only in some
 * turns, so a bar that drew them would either mark a position the game never stops in or change
 * length with the board. This maps the snapshot's step onto the steps the bar *does* draw, and
 * answers null for the rest — a turn passing through untap simply leaves the marker where it was
 * rather than jumping somewhere that is not shown.
 *
 * **The stops are upstream's own default and are not toggled here.** Nothing in this app yet acts on
 * a stop: `ManualPassPolicy` never passes on its own, so every step is one the player is asked at. A
 * toggle that changed a picture and nothing else would be a control that lies, so 0112 draws the bar
 * and leaves the toggling to the story that gives `PassPolicy` something to read.
 */

/**
 * The phase bar for one snapshot.
 *
 * @param state the server's own game view.
 */
fun phaseBarState(state: GameState): PhaseBarState =
    PhaseBarState(
        steps = standardTurnSteps(),
        currentStepId = state.step.barStepId(),
        // Whose turn it is, from the seat the server marked active rather than from who holds
        // priority: the bar says *whose turn*, and priority moves within a turn several times.
        turn =
            if (state.activePlayerId != null && state.activePlayerId == state.viewerPlayerId) {
                PhaseBarTurn.Yours
            } else {
                PhaseBarTurn.Opponents
            },
    )

/**
 * The bar's id for a step, or null for a step the bar does not draw.
 *
 * First-strike damage answers with the ordinary combat-damage step deliberately: it is the same
 * position in the turn as far as a player reading the bar is concerned, and the alternative — no
 * marker at all through a step that can decide the game — is worse than one that is a little coarse.
 */
private fun PhaseStep.barStepId(): String? =
    when (this) {
        PhaseStep.Upkeep -> StepIds.UPKEEP
        PhaseStep.Draw -> StepIds.DRAW
        PhaseStep.PrecombatMain -> StepIds.PRECOMBAT_MAIN
        PhaseStep.BeginCombat -> StepIds.BEGIN_COMBAT
        PhaseStep.DeclareAttackers -> StepIds.DECLARE_ATTACKERS
        PhaseStep.DeclareBlockers -> StepIds.DECLARE_BLOCKERS
        PhaseStep.FirstCombatDamage, PhaseStep.CombatDamage -> StepIds.COMBAT_DAMAGE
        PhaseStep.EndCombat -> StepIds.END_COMBAT
        PhaseStep.PostcombatMain -> StepIds.POSTCOMBAT_MAIN
        PhaseStep.EndTurn -> StepIds.END_TURN
        PhaseStep.Untap, PhaseStep.Cleanup, PhaseStep.Unknown -> null
    }
