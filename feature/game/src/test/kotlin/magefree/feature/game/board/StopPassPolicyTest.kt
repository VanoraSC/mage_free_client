package magefree.feature.game.board

import magefree.designsystem.component.phase.PhaseStop
import magefree.designsystem.component.phase.StepIds
import magefree.network.game.CombatGroup
import magefree.network.game.GameCard
import magefree.network.game.GamePlayer
import magefree.network.game.GamePrompt
import magefree.network.game.GameState
import magefree.network.game.PhaseStep
import magefree.network.game.PromptOptions
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * When the app answers a priority window on the player's behalf.
 *
 * Every test here is a way the policy could take a decision away from the player that they wanted, or
 * ask them one they did not — which are the only two ways an auto-pass can be wrong. The two stops
 * that are rules rather than settings get a test each in both directions, because a rule that fires
 * when it should not is as bad as one that does not fire when it should.
 */
class StopPassPolicyTest {
    private val stops = StopStore()
    private val policy = StopPassPolicy(stops)

    @Test
    fun `with no stop and nothing on the stack, it passes`() {
        assertEquals(PassDecision.PassImmediately, policy.decide(priorityAt(PhaseStep.Upkeep)))
    }

    @Test
    fun `a standing stop asks, every time`() {
        stops.press(isYourTurn = false, stepId = StepIds.UPKEEP) // once
        stops.press(isYourTurn = false, stepId = StepIds.UPKEEP) // always

        assertEquals(PassDecision.AskThePlayer, policy.decide(priorityAt(PhaseStep.Upkeep)))
        assertEquals(PassDecision.AskThePlayer, policy.decide(priorityAt(PhaseStep.Upkeep)))
    }

    @Test
    fun `a one-shot stop asks once and then clears itself`() {
        stops.press(isYourTurn = false, stepId = StepIds.END_TURN)

        assertEquals(PassDecision.AskThePlayer, policy.decide(priorityAt(PhaseStep.EndTurn)))
        assertEquals(
            PhaseStop.None,
            stops.stops.value.theirs
                .modeAt(StepIds.END_TURN),
        )
        assertEquals(PassDecision.PassImmediately, policy.decide(priorityAt(PhaseStep.EndTurn)))
    }

    @Test
    fun `a stop set for one side does not fire on the other`() {
        stops.press(isYourTurn = true, stepId = StepIds.UPKEEP)
        stops.press(isYourTurn = true, stepId = StepIds.UPKEEP)

        assertEquals(PassDecision.PassImmediately, policy.decide(priorityAt(PhaseStep.Upkeep, yourTurn = false)))
        assertEquals(PassDecision.AskThePlayer, policy.decide(priorityAt(PhaseStep.Upkeep, yourTurn = true)))
    }

    @Test
    fun `nothing is passed while anything is on the stack`() {
        // Upstream's `stopOnStackNewObjects`, seen from the other side: something on the stack is
        // something to respond to, and it is the state a player most needs to be asked about.
        val casting = priorityAt(PhaseStep.Upkeep).copy(stack = listOf(GameCard(id = "s-1", name = "Giant Growth")))

        assertEquals(PassDecision.AskThePlayer, policy.decide(casting))
    }

    @Test
    fun `your own main phases always ask, whatever the stops say`() {
        // A turn you cannot act in is not a turn you are playing.
        assertEquals(PassDecision.AskThePlayer, policy.decide(priorityAt(PhaseStep.PrecombatMain, yourTurn = true)))
        assertEquals(PassDecision.AskThePlayer, policy.decide(priorityAt(PhaseStep.PostcombatMain, yourTurn = true)))
    }

    @Test
    fun `an opponent's main phases do not`() {
        // The other direction of the same rule: it is about *your* turn, not about main phases.
        assertEquals(PassDecision.PassImmediately, policy.decide(priorityAt(PhaseStep.PrecombatMain, yourTurn = false)))
    }

    @Test
    fun `after blockers are declared, with an attack, it asks`() {
        // The combat-trick window — the one priority window whose absence loses games rather than
        // wasting time.
        val blocking = priorityAt(PhaseStep.DeclareBlockers).copy(combat = listOf(CombatGroup(attackerIds = listOf("bear"))))

        assertEquals(PassDecision.AskThePlayer, policy.decide(blocking))
    }

    @Test
    fun `with no attack, it does not`() {
        // Conditional on there having *been* an attack: with no combat there is nothing to respond to,
        // and stopping anyway is exactly the pointless question this policy exists to remove.
        assertEquals(PassDecision.PassImmediately, policy.decide(priorityAt(PhaseStep.DeclareBlockers)))
    }

    @Test
    fun `a declaration is never passed`() {
        // A `Select` carrying `possibleAttackers` is a declaration, closed with the server's own done
        // arm. Passing it would be answering a different question.
        val declaring =
            priorityAt(PhaseStep.DeclareAttackers, yourTurn = true).copy(
                prompt =
                    GamePrompt.Select(
                        message = "Select attackers",
                        options = PromptOptions(ids = mapOf(PromptOptions.POSSIBLE_ATTACKERS to listOf("bear"))),
                    ),
            )

        assertEquals(PassDecision.AskThePlayer, policy.decide(declaring))
    }

    @Test
    fun `a prompt that is not a priority window is left alone`() {
        val asking = priorityAt(PhaseStep.Upkeep).copy(prompt = GamePrompt.Ask(message = "Mulligan?"))

        assertEquals(PassDecision.AskThePlayer, policy.decide(asking))
    }

    private fun priorityAt(
        step: PhaseStep,
        yourTurn: Boolean = false,
    ) = GameState(
        gameId = "g",
        step = step,
        hasSnapshot = true,
        viewerPlayerId = "me",
        activePlayerId = if (yourTurn) "me" else "them",
        players =
            listOf(
                GamePlayer(playerId = "me", name = "Me", isViewer = true),
                GamePlayer(playerId = "them", name = "Them"),
            ),
        prompt = GamePrompt.Select(message = "Play instants and activated abilities"),
    )
}
