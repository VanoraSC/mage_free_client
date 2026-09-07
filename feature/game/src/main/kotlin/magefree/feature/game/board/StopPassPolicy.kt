package magefree.feature.game.board

import magefree.designsystem.component.phase.PhaseStop
import magefree.network.game.GamePrompt
import magefree.network.game.GameState
import magefree.network.game.PhaseStep

/**
 * The policy that answers priority windows the player has not asked to see.
 *
 * **One rule, and two exceptions that are rules rather than settings.**
 *
 * The rule: with nothing on the stack and no stop set for the step being played on the side being
 * played, the app passes. That is the same thing upstream's server does from its own copy of the
 * player's steps, decided here instead because there is no message for sending it ours.
 *
 * **Never with anything on the stack**, which is upstream's `stopOnStackNewObjects` seen from the
 * other side. Something on the stack is something to respond to, and it is the state a player most
 * needs to be asked about.
 *
 * **Never a declaration.** A `Select` carrying `possibleAttackers` or `possibleBlockers` is a
 * declaration, not a priority window: it is closed with the server's own done arm and a pass would be
 * answering a different question. `CombatRole.of` is the same test `controlsFor` uses, so the two can
 * never disagree about which kind of `Select` this is.
 */
class StopPassPolicy(
    private val stops: StopStore,
) : PassPolicy {
    override fun decide(state: GameState): PassDecision {
        val prompt = state.prompt as? GamePrompt.Select ?: return PassDecision.AskThePlayer
        if (CombatRole.of(prompt.options) != null) return PassDecision.AskThePlayer
        if (state.stack.isNotEmpty()) return PassDecision.AskThePlayer

        val isYourTurn = state.activePlayerId != null && state.activePlayerId == state.viewerPlayerId
        if (mustStop(state, isYourTurn)) return PassDecision.AskThePlayer

        // A step the bar draws no stop control for and no rule names is one nobody wants to be asked
        // about — an opponent's declare-attackers window with nothing on the stack, say. Passing is the
        // whole point of the story.
        val stepId = state.step.stoppableId() ?: return PassDecision.PassImmediately

        return when (
            stops.stops.value
                .on(isYourTurn)
                .modeAt(stepId)
        ) {
            PhaseStop.None -> PassDecision.PassImmediately
            PhaseStop.Always -> PassDecision.AskThePlayer
            // A one-shot fires now and is gone. Safe to consume here because the ViewModel asks this
            // exactly once per prompt *instance*, so a re-emission of the same question cannot spend
            // the same stop twice.
            PhaseStop.Once -> {
                stops.consumeOnce(isYourTurn, stepId)
                PassDecision.AskThePlayer
            }
        }
    }

    /**
     * The two stops a player may not turn off.
     *
     * **Your own main phases.** A turn you cannot act in is not a turn you are playing. Upstream
     * defaults both to true and allows them off; this does not, because the failure is silent and
     * total — a player who turned them off once would never take another turn.
     *
     * **After blockers are declared, before combat damage, when there was an attack.** This is the
     * combat-trick window, and it is the one priority window whose absence loses games rather than
     * wasting time. It is conditional on there having *been* an attack: with no combat there is
     * nothing to respond to, and stopping anyway would be exactly the pointless question this policy
     * exists to remove. "Was there an attack" is `GameState.combat` being non-empty — the server's own
     * assignment, never a guess.
     */
    private fun mustStop(
        state: GameState,
        isYourTurn: Boolean,
    ): Boolean =
        when (state.step) {
            PhaseStep.PrecombatMain, PhaseStep.PostcombatMain -> isYourTurn
            PhaseStep.DeclareBlockers -> state.combat.isNotEmpty()
            else -> false
        }
}
