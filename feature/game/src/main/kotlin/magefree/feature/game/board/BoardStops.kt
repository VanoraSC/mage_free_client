package magefree.feature.game.board

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import magefree.designsystem.component.phase.PhaseStop
import magefree.designsystem.component.phase.StepIds
import magefree.network.game.PhaseStep

/*
 * Which priority windows the player wants to be asked about.
 *
 * **The model is upstream's.** `UserSkipPrioritySteps` holds two `SkipPrioritySteps` — one for your
 * turn and one for an opponent's — each a set of seven booleans over upkeep, draw, main 1, before
 * combat, end of combat, main 2 and end of turn. Those are exactly the seven the phase bar marks
 * stoppable, and the split by side is what lets a player care about their opponent's end step without
 * caring about their own.
 *
 * **The third state is the phase bar's own — see [PhaseStop].** Upstream's stop is a boolean. *Stop
 * the next time this comes round,
 * then forget it* is a thing a player wants constantly — "let me see their end step this turn" — and
 * has no upstream equivalent, so it is a client-side convenience rather than a translation.
 *
 * **Where the skipping happens is the other difference.** Upstream's server reads the user's steps and
 * skips before it ever asks; there is no message for setting that user data, so this app decides for
 * itself and sends the pass. The semantics are upstream's and only the decision point is ours.
 */

/** One side of the turn's stops, by the phase bar's own step ids. */
data class TurnStops(
    val byStep: Map<String, PhaseStop> = emptyMap(),
) {
    fun modeAt(stepId: String): PhaseStop = byStep[stepId] ?: PhaseStop.None

    fun pressed(stepId: String): TurnStops = withMode(stepId, modeAt(stepId).next())

    fun withMode(
        stepId: String,
        mode: PhaseStop,
    ): TurnStops =
        TurnStops(
            // `None` is the absence of a stop rather than a stop that is off, so it is removed. It
            // keeps the map to what the player has actually asked for, which is what a future
            // persisted form wants to write.
            byStep = if (mode == PhaseStop.None) byStep - stepId else byStep + (stepId to mode),
        )
}

/**
 * Both sides' stops.
 *
 * @property yours what to stop for while it is the viewer's own turn.
 * @property theirs what to stop for while it is anyone else's.
 */
data class BoardStops(
    val yours: TurnStops = TurnStops(),
    val theirs: TurnStops = TurnStops(),
) {
    fun on(isYourTurn: Boolean): TurnStops = if (isYourTurn) yours else theirs

    fun pressed(
        isYourTurn: Boolean,
        stepId: String,
    ): BoardStops = if (isYourTurn) copy(yours = yours.pressed(stepId)) else copy(theirs = theirs.pressed(stepId))

    fun withMode(
        isYourTurn: Boolean,
        stepId: String,
        mode: PhaseStop,
    ): BoardStops = if (isYourTurn) copy(yours = yours.withMode(stepId, mode)) else copy(theirs = theirs.withMode(stepId, mode))
}

/**
 * The stops, held for the session rather than for the game.
 *
 * A player sets stops once and plays a match with them, and a game ending is not a reason to forget
 * what they asked for — the board is rebuilt between games of a match, and stops that died with it
 * would have to be set again every game.
 *
 * A single instance in the DI graph, read by [StopPassPolicy] and published to the phase bar by the
 * ViewModel, so the two can never disagree about what is set.
 */
class StopStore {
    private val _stops = MutableStateFlow(BoardStops())

    val stops: StateFlow<BoardStops> = _stops.asStateFlow()

    /** Cycles the stop at [stepId] on the side being played. */
    fun press(
        isYourTurn: Boolean,
        stepId: String,
    ) {
        _stops.update { it.pressed(isYourTurn, stepId) }
    }

    /**
     * Clears a one-shot stop that has just fired.
     *
     * Called by the policy at the moment it decides to stop, which is safe because the policy is asked
     * exactly once per prompt instance — see [GameBoardViewModel]'s `policyAskedFor`.
     */
    fun consumeOnce(
        isYourTurn: Boolean,
        stepId: String,
    ) {
        _stops.update { current ->
            if (current.on(isYourTurn).modeAt(stepId) == PhaseStop.Once) {
                current.withMode(isYourTurn, stepId, PhaseStop.None)
            } else {
                current
            }
        }
    }
}

/**
 * The phase bar's id for a step, or `null` for one the bar does not draw a stop control for.
 *
 * The seven are upstream's own stoppable set. The rest are either steps nobody receives priority in
 * (untap, cleanup) or steps answered by a declaration rather than by a pass — and combat damage,
 * which has its own rule.
 */
internal fun PhaseStep.stoppableId(): String? =
    when (this) {
        PhaseStep.Upkeep -> StepIds.UPKEEP
        PhaseStep.Draw -> StepIds.DRAW
        PhaseStep.PrecombatMain -> StepIds.PRECOMBAT_MAIN
        PhaseStep.BeginCombat -> StepIds.BEGIN_COMBAT
        PhaseStep.EndCombat -> StepIds.END_COMBAT
        PhaseStep.PostcombatMain -> StepIds.POSTCOMBAT_MAIN
        PhaseStep.EndTurn -> StepIds.END_TURN
        else -> null
    }
