package magefree.feature.game.board

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import magefree.designsystem.component.phase.PhaseStop
import magefree.designsystem.component.phase.StepIds
import magefree.network.game.GameState
import magefree.network.game.PhaseStep
import magefree.network.game.PriorityStopSteps

/*
 * Which priority windows the player wants to be asked about.
 *
 * **A stop is about a step *and* a side, and the rail is what made that sayable.** Upstream has kept
 * two `SkipPrioritySteps` all along — one for your turn, one for an opponent's — and 0115 merged them
 * because the horizontal bar had one row and *"a row cannot say which side a mark belongs to"*: a mark
 * pressed on your own turn silently set only your side, and nothing on screen explained why the
 * opponent's upkeep went past. Merging was the only honest thing one row could do.
 *
 * 0123's rail is two columns and each column **is** a side, so the distinction it could not draw is
 * now the shape of the control. The extra mapping goes away and the client is back in step with the
 * server it is talking to.
 *
 * **The two marks answer *how long*.** Blue stops at the next occurrence of that step on that side,
 * and then clears itself. Red stops every time.
 *
 * **The end step starts marked, on both sides.** Upstream defaults `endOfTurn` to false; this client
 * defaults it to [PhaseStop.Always], because it is the window a player most often wants, most often
 * forgets to ask for, and loses games to. A default and not a rule — it is an ordinary mark and one
 * press takes it off, which is what separates it from the two main phases in [OWN_MAIN_PHASE_STOPS].
 *
 * **The skipping is the server's, and that is not a detail.** `HumanPlayer.priority()` calls
 * `checkPassStep`, which reads the player's own `UserSkipPrioritySteps` and passes *without ever
 * sending the client a prompt* when the step is not set — and only when the stack is empty, which is
 * where "auto-pass only with an empty stack" comes from. So a stop is not a decision this app makes
 * about a question it was asked; it is the thing that decides whether the question arrives at all.
 * These are sent upstream (`GameClient.setPriorityStops`) and the server does the rest.
 *
 * **The one-shot is the phase bar's own — see [PhaseStop].** Upstream's stop is a boolean. *Stop the
 * next time this comes round, then forget it* is a thing a player wants constantly — "let me see the
 * next end step" — and has no upstream equivalent, so it is sent as an ordinary stop on both sides and
 * taken back once the window it asked for has arrived.
 */

/**
 * Whose turn a stop applies to.
 *
 * Upstream's own division — `UserSkipPrioritySteps` holds one `SkipPrioritySteps` per side — named
 * from the player's point of view rather than the server's, because that is the point of view the rail
 * is read from.
 */
enum class TurnSide {
    /** The viewer's own turn. */
    Yours,

    /** Anybody else's. Upstream has one setting for all of them, and so does this. */
    Theirs,
}

/**
 * What the player has asked to be stopped at, by side and by the phase rail's own step ids.
 *
 * Two sets, mirroring upstream. Where the sides differ by *rule* rather than by setting is
 * [OWN_MAIN_PHASE_STOPS], which is applied on the way to the server rather than kept here — a rule is
 * not a mark the player made.
 */
data class BoardStops(
    val yours: Map<String, PhaseStop> = emptyMap(),
    val theirs: Map<String, PhaseStop> = emptyMap(),
    /**
     * **Full Control** — whether priority comes back to the player after they put something on the
     * stack.
     *
     * Off, the server passes for them the moment a spell or a non-mana activated ability lands: upstream's
     * own `UserData.passPriorityCast` and `passPriorityActivation`, which `HumanPlayer.priority()` checks
     * before anything else. On is how the board played before this existed — every cast hands priority
     * back, so a player can respond to their own spell.
     *
     * A pinned mode rather than a held key, because a phone has no `Ctrl` (§3.2). It asks for *more*
     * decisions, not fewer. Kept with the stops because it is one: it decides whether a priority window
     * arrives at all, and it reaches the server the same way.
     */
    val fullControl: Boolean = false,
) {
    fun modeAt(
        side: TurnSide,
        stepId: String,
    ): PhaseStop = sideMap(side)[stepId] ?: PhaseStop.None

    fun pressed(
        side: TurnSide,
        stepId: String,
    ): BoardStops = withMode(side, stepId, modeAt(side, stepId).next())

    fun withMode(
        side: TurnSide,
        stepId: String,
        mode: PhaseStop,
    ): BoardStops {
        // `None` is the absence of a stop rather than a stop that is off, so it is removed. It keeps
        // each map to what the player has actually asked for, which is what a future persisted form
        // wants to write.
        val updated = if (mode == PhaseStop.None) sideMap(side) - stepId else sideMap(side) + (stepId to mode)
        return when (side) {
            TurnSide.Yours -> copy(yours = updated)
            TurnSide.Theirs -> copy(theirs = updated)
        }
    }

    internal fun sideMap(side: TurnSide): Map<String, PhaseStop> =
        when (side) {
            TurnSide.Yours -> yours
            TurnSide.Theirs -> theirs
        }

    companion object {
        /**
         * What a player starts with: the end step marked on **both** turns.
         *
         * It is the window most often wanted and most often forgotten — the last chance to act before
         * a turn is over, on either side of the table. Upstream leaves it off; this turns it on and
         * leaves it pressable, so it is a default rather than a rule.
         */
        val Default: BoardStops =
            BoardStops(
                yours = mapOf(StepIds.END_TURN to PhaseStop.Always),
                theirs = mapOf(StepIds.END_TURN to PhaseStop.Always),
            )
    }
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
    private val _stops = MutableStateFlow(BoardStops.Default)

    val stops: StateFlow<BoardStops> = _stops.asStateFlow()

    /** Cycles the stop at [stepId] on [side]: none → once → always → none. */
    fun press(
        side: TurnSide,
        stepId: String,
    ) {
        _stops.update { it.pressed(side, stepId) }
    }

    /** Turns Full Control on or off — see [BoardStops.fullControl]. */
    fun setFullControl(on: Boolean) {
        _stops.update { it.copy(fullControl = on) }
    }

    /**
     * Clears a one-shot stop that has just fired.
     *
     * Called when a priority prompt arrives in the step it was set for — the proof that the server
     * honoured it — and once per prompt *instance*, so a re-emission of the same question cannot
     * spend the same stop twice. See [GameBoardViewModel]'s `policyAskedFor`.
     *
     * **Only the side it was asked for.** A one-shot on your own upkeep is not spent by an opponent's
     * upkeep arriving first — the two are different marks now, and the window that arrives has to be
     * the window that was asked for.
     */
    fun consumeOnce(
        side: TurnSide,
        stepId: String,
    ) {
        _stops.update { current ->
            if (current.modeAt(side, stepId) == PhaseStop.Once) {
                current.withMode(side, stepId, PhaseStop.None)
            } else {
                current
            }
        }
    }
}

/**
 * Whose turn this snapshot is in.
 *
 * From the seat the server marked **active**, not from who holds priority: priority moves within a
 * turn several times and a stop is about the turn. A snapshot with no active seat — before a game
 * starts, or between them — answers [TurnSide.Theirs], which is the safe way round: it spends no
 * one-shot the player set for their own turn.
 */
internal fun GameState.turnSide(): TurnSide =
    if (activePlayerId != null && activePlayerId == viewerPlayerId) TurnSide.Yours else TurnSide.Theirs

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
 * Called once per side, each with **that side's** marks. 0115 sent the same set twice because the bar
 * could not tell them apart; the rail can, so this reads the side it is asked for and the extra
 * mapping is gone.
 *
 * `Once` and `Always` both mean *stop* to the server: it has no notion of a one-shot, and the
 * difference is kept here by clearing the stop after it has fired. That is the one piece of this the
 * client owns, and it owns it because upstream has nothing to translate it to.
 *
 * @param forced steps that stop whether or not the player asked — [OWN_MAIN_PHASE_STOPS] on your own
 *   turn. Sending them is what keeps the rule true on the server rather than only in the rail: without
 *   it, a player who has never pressed M1 would have `main1 = false` sent for their own turn and the
 *   server would skip the one window the whole turn is for.
 */
internal fun BoardStops.asSteps(
    side: TurnSide,
    forced: Set<String> = emptySet(),
): PriorityStopSteps =
    PriorityStopSteps(
        upkeep = stopsAt(side, StepIds.UPKEEP, forced),
        draw = stopsAt(side, StepIds.DRAW, forced),
        main1 = stopsAt(side, StepIds.PRECOMBAT_MAIN, forced),
        beforeCombat = stopsAt(side, StepIds.BEGIN_COMBAT, forced),
        endOfCombat = stopsAt(side, StepIds.END_COMBAT, forced),
        main2 = stopsAt(side, StepIds.POSTCOMBAT_MAIN, forced),
        endOfTurn = stopsAt(side, StepIds.END_TURN, forced),
    )

private fun BoardStops.stopsAt(
    side: TurnSide,
    stepId: String,
    forced: Set<String>,
): Boolean = stepId in forced || modeAt(side, stepId) != PhaseStop.None

/**
 * The stops a player may not press away on their **own** turn.
 *
 * A turn you cannot act in is not a turn you are playing, so both main phases stop — which is also
 * upstream's own default for `SkipPrioritySteps` (`main1` and `main2` start `true`). Stated once, and
 * used both to draw the bar's locked marks and to force the flags actually sent.
 */
internal val OWN_MAIN_PHASE_STOPS: Set<String> = setOf(StepIds.PRECOMBAT_MAIN, StepIds.POSTCOMBAT_MAIN)
