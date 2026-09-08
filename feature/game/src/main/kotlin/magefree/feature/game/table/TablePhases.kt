package magefree.feature.game.table

import magefree.designsystem.component.phase.PhaseBarState
import magefree.designsystem.component.phase.PhaseBarTurn
import magefree.designsystem.component.phase.StepIds
import magefree.designsystem.component.phase.standardTurnSteps
import magefree.feature.game.board.BoardStops
import magefree.feature.game.board.OWN_MAIN_PHASE_STOPS
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
 * **The stops are the player's, and the side is the turn.** Upstream keeps a `SkipPrioritySteps` per
 * side — one for your turn, one for an opponent's — and the bar has one row, so it draws the row that
 * applies to the turn being played. Which stops are *rules* rather than settings is [lockedStops],
 * and those are the server's rules, read out of `SkipPrioritySteps.isPhaseStepSet` and
 * `TurnStops.asSteps`, so the mark and the stop cannot disagree.
 */

/**
 * The phase bar for one snapshot.
 *
 * **The bar draws the side whose turn is being played.** Stops are per side — upstream's own model,
 * a `SkipPrioritySteps` for your turn and another for an opponent's — and the bar has one row, so it
 * shows the row that applies right now. Both sides' stops are reachable over one turn cycle without a
 * second control for choosing a side.
 *
 * @param state the server's own game view.
 * @param stops what the player has asked to be stopped at, both sides.
 * @param locked the steps whose stop is a rule rather than a setting, for this side of this turn.
 */
fun phaseBarState(
    state: GameState,
    stops: BoardStops = BoardStops(),
    locked: Set<String> = emptySet(),
): PhaseBarState {
    val isYourTurn = state.activePlayerId != null && state.activePlayerId == state.viewerPlayerId
    return PhaseBarState(
        steps = standardTurnSteps(stops = stops.on(isYourTurn).byStep, locked = locked),
        currentStepId = state.step.barStepId(),
        // Whose turn it is, from the seat the server marked active rather than from who holds
        // priority: the bar says *whose turn*, and priority moves within a turn several times.
        turn =
            if (isYourTurn) {
                PhaseBarTurn.Yours
            } else {
                PhaseBarTurn.Opponents
            },
    )
}

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

/**
 * The steps whose stop is a **rule** for this snapshot, rather than something the player set.
 *
 * **The combat steps, always.** `SkipPrioritySteps.isPhaseStepSet` has seven cases and a
 * `default: return true`, so declare attackers, declare blockers and combat damage are not steps a
 * stop can be lifted from — the server gives priority in all three however the flags are set. That is
 * where the mandatory window after blockers are declared and before damage comes from, and why it
 * needs no condition on there having been an attack: with no attackers those steps do not happen.
 *
 * **Your own main phases**, because a turn you cannot act in is not a turn you are playing. That one
 * is a rule this client enforces, by forcing the flags in [OWN_MAIN_PHASE_STOPS] on the way to the
 * server — so the lock in the bar and the stop the server makes are the same fact stated twice.
 */
fun lockedStops(state: GameState): Set<String> =
    buildSet {
        add(StepIds.DECLARE_ATTACKERS)
        add(StepIds.DECLARE_BLOCKERS)
        add(StepIds.COMBAT_DAMAGE)
        if (state.activePlayerId != null && state.activePlayerId == state.viewerPlayerId) {
            addAll(OWN_MAIN_PHASE_STOPS)
        }
    }
