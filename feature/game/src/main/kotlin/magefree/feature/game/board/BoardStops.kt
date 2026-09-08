package magefree.feature.game.board

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import magefree.designsystem.component.phase.PhaseStop
import magefree.designsystem.component.phase.StepIds
import magefree.network.game.PhaseStep
import magefree.network.game.PriorityStopSteps

/*
 * Which priority windows the player wants to be asked about.
 *
 * **The model is upstream's.** `UserSkipPrioritySteps` holds two `SkipPrioritySteps` — one for your
 * turn and one for an opponent's — each a set of seven booleans over upkeep, draw, main 1, before
 * combat, end of combat, main 2 and end of turn. Those are exactly the seven the phase bar marks
 * stoppable, and the split by side is what lets a player care about their opponent's end step without
 * caring about their own.
 *
 * **The skipping is the server's, and that is not a detail.** `HumanPlayer.priority()` calls
 * `checkPassStep`, which reads the player's own `UserSkipPrioritySteps` and passes *without ever
 * sending the client a prompt* when the step is not set — and only when the stack is empty, which is
 * where "auto-pass only with an empty stack" comes from. So a stop is not a decision this app makes
 * about a question it was asked; it is the thing that decides whether the question arrives at all.
 * These are sent upstream (`GameClient.setPriorityStops`) and the server does the rest.
 *
 * **The third state is the phase bar's own — see [PhaseStop].** Upstream's stop is a boolean. *Stop
 * the next time this comes round, then forget it* is a thing a player wants constantly — "let me see
 * their end step this turn" — and has no upstream equivalent, so it is sent as an ordinary stop and
 * taken back once the window it asked for has arrived.
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
 * A single instance in the DI graph. The ViewModel publishes every change twice — to the phase bar,
 * so the mark appears without waiting for a snapshot, and to the server, which is where the stop
 * actually takes effect — so what is drawn and what is enforced cannot drift apart.
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
     * Called when a priority prompt arrives in the step it was set for — the proof that the server
     * honoured it — and once per prompt *instance*, so a re-emission of the same question cannot
     * spend the same stop twice. See [GameBoardViewModel]'s `policyAskedFor`.
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

/**
 * One side's stops as the network layer's own type — a field-for-field mirror of upstream's
 * `SkipPrioritySteps`, which is what the server ultimately reads.
 *
 * `Once` and `Always` both mean *stop* to the server: it has no notion of a one-shot, and the
 * difference is kept here by clearing the stop after it has fired. That is the one piece of this the
 * client owns, and it owns it because upstream has nothing to translate it to.
 *
 * @param forced steps that stop whether or not the player asked — [OWN_MAIN_PHASE_STOPS] on your own
 *   turn. Sending them is what keeps the rule true on the server rather than only in the bar: without
 *   it, a player who has never pressed M1 would have `main1 = false` sent for their own turn and the
 *   server would skip the one window the whole turn is for.
 */
internal fun TurnStops.asSteps(forced: Set<String> = emptySet()): PriorityStopSteps =
    PriorityStopSteps(
        upkeep = stopsAt(StepIds.UPKEEP, forced),
        draw = stopsAt(StepIds.DRAW, forced),
        main1 = stopsAt(StepIds.PRECOMBAT_MAIN, forced),
        beforeCombat = stopsAt(StepIds.BEGIN_COMBAT, forced),
        endOfCombat = stopsAt(StepIds.END_COMBAT, forced),
        main2 = stopsAt(StepIds.POSTCOMBAT_MAIN, forced),
        endOfTurn = stopsAt(StepIds.END_TURN, forced),
    )

private fun TurnStops.stopsAt(
    stepId: String,
    forced: Set<String>,
): Boolean = stepId in forced || modeAt(stepId) != PhaseStop.None

/**
 * The stops a player may not press away on their **own** turn.
 *
 * A turn you cannot act in is not a turn you are playing, so both main phases stop — which is also
 * upstream's own default for `SkipPrioritySteps` (`main1` and `main2` start `true`). Stated once, and
 * used both to draw the bar's locked marks and to force the flags actually sent.
 */
internal val OWN_MAIN_PHASE_STOPS: Set<String> = setOf(StepIds.PRECOMBAT_MAIN, StepIds.POSTCOMBAT_MAIN)
