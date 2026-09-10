package magefree.feature.game.table

import magefree.designsystem.component.phase.PhaseBarTurn
import magefree.designsystem.component.phase.PhaseRailState
import magefree.designsystem.component.phase.StepIds
import magefree.designsystem.component.phase.standardRailSteps
import magefree.feature.game.board.BoardStops
import magefree.feature.game.board.OWN_MAIN_PHASE_STOPS
import magefree.feature.game.board.TurnSide
import magefree.network.game.GameState
import magefree.network.game.PhaseStep

/*
 * Where the turn is, in the rail's own vocabulary.
 *
 * **The rail shows fewer steps than a turn has**, and that is the rail's decision rather than this
 * projection's: untap and cleanup give nobody priority, and first-strike damage exists only in some
 * turns, so a rail that drew them would either mark a position the game never stops in or change
 * length with the board. This maps the snapshot's step onto the steps the rail *does* draw, and
 * answers null for the rest — a turn passing through untap simply leaves the marker where it was
 * rather than jumping somewhere that is not shown.
 *
 * **The stops are per side, and the rail is why.** Each column is a side, so a mark can say which side
 * it belongs to — which is the thing 0115's single row could not do and the reason it merged them.
 * Which stops are *rules* rather than settings is [lockedRailStops], read out of upstream's own
 * `SkipPrioritySteps.isPhaseStepSet` and this client's [OWN_MAIN_PHASE_STOPS], so the mark and the
 * stop cannot disagree.
 */

/**
 * The phase rail for one snapshot.
 *
 * @param state the server's own game view.
 * @param stops what the player has asked to be stopped at, per side.
 */
fun phaseRailState(
    state: GameState,
    stops: BoardStops = BoardStops.Default,
): PhaseRailState =
    PhaseRailState(
        steps =
            standardRailSteps(
                yours = stops.yours,
                opponents = stops.theirs,
                // **Per side, because the rule is.** Your own main phases are a rule on your own turn
                // and an ordinary choice on somebody else's: a turn you cannot act in is not a turn
                // you are playing, and that says nothing about theirs.
                lockedYours = OWN_MAIN_PHASE_STOPS,
                lockedBoth = COMBAT_RULE_STOPS,
            ),
        currentStepId = state.step.railStepId(),
        // Whose turn it is, from the seat the server marked active rather than from who holds
        // priority: the rail says *whose turn*, and priority moves within a turn several times.
        turn = if (state.isViewersTurn()) PhaseBarTurn.Yours else PhaseBarTurn.Opponents,
    )

/** Which side of the rail a press on [turn]'s column sets. */
fun PhaseBarTurn.asTurnSide(): TurnSide = if (this == PhaseBarTurn.Yours) TurnSide.Yours else TurnSide.Theirs

/**
 * The steps whose stop is a **rule** on both sides.
 *
 * `SkipPrioritySteps.isPhaseStepSet` has seven cases and a `default: return true`, so declare
 * attackers, declare blockers and combat damage are not steps a stop can be lifted from — the server
 * gives priority in all three however the flags are set. That is where the mandatory window after
 * blockers are declared and before damage comes from, and why it needs no condition on there having
 * been an attack: with no attackers those steps do not happen.
 */
internal val COMBAT_RULE_STOPS: Set<String> =
    setOf(StepIds.DECLARE_ATTACKERS, StepIds.DECLARE_BLOCKERS, StepIds.COMBAT_DAMAGE)

private fun GameState.isViewersTurn(): Boolean = activePlayerId != null && activePlayerId == viewerPlayerId

/**
 * The rail's id for a step, or null for a step the rail does not draw.
 *
 * First-strike damage answers with the ordinary combat-damage step deliberately: it is the same
 * position in the turn as far as a player reading the rail is concerned, and the alternative — no
 * marker at all through a step that can decide the game — is worse than one that is a little coarse.
 */
private fun PhaseStep.railStepId(): String? =
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
