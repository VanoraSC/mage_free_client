package magefree.feature.game.board

import magefree.network.game.GamePrompt
import magefree.network.game.GameState

/**
 * **The one place that decides *when* the app answers a priority prompt** (requirements §9.1, §14.1).
 *
 * Priority is manual today: the player is asked every time, and nothing is answered on their behalf.
 * Stops and configurable auto-pass are a **named future feature** — Pete: *"in the future I want to
 * support similar functions to Arena and MTGO with manual set stops and configurable auto pass"* — and
 * §14.1 turns that into a design constraint on this story:
 *
 * > passing priority must remain a **policy decision made in one place**, not logic scattered through
 * > the UI. When stops arrive, they change *when the app answers a priority prompt*, and nothing else
 * > should have to change.
 *
 * So this interface is that one place, and [GameBoardViewModel] is the only caller. Every pass the app
 * ever sends — the player's tap today, an auto-pass tomorrow — goes through
 * [GameBoardViewModel.passPriority], which consults *this* and then makes exactly one
 * [magefree.network.game.GameClient.passPriority] call. Nothing else in `feature/game` calls the client's
 * pass verb, and no Composable does.
 *
 * **How auto-pass hooks in, concretely.** Replace the bound implementation (see `BoardModule`) with one
 * that inspects the state — stops the player has set, whether the stack is empty, whose turn it is,
 * whether mana is floating (§14.2 flags that case explicitly) — and returns [PassDecision.PassImmediately]
 * where the player would have. No other file changes: the decision point, the send, and the UI's
 * "waiting on you" statement all already live behind this call.
 *
 * **It is consulted on every [GamePrompt.Select], and only there**, because that is the only prompt a
 * pass is a valid answer to (0052: `passPriority` is the `false` arm of the select). The standing
 * "pass until …" verbs are a different thing entirely — they are player actions, not answers — and are
 * deliberately not modelled here.
 */
fun interface PassPolicy {
    /**
     * What the app should do with the [GamePrompt.Select] outstanding in [state].
     *
     * @param state the snapshot the prompt arrived on, so a future policy can see the stack, the step,
     *   the floating mana and everything else it needs. Today's policy reads none of it.
     */
    fun decide(state: GameState): PassDecision
}

/** What a [PassPolicy] says to do with an outstanding [GamePrompt.Select]. */
enum class PassDecision {
    /** Show the player the controls and wait for them. The only answer [ManualPassPolicy] ever gives. */
    AskThePlayer,

    /** Answer the select with a pass, now, without asking. The arm auto-pass and stops will use. */
    PassImmediately,
}

/**
 * The policy this release ships: **everything explicit and manual** (§14.1). The player is asked every
 * time; the app never passes on its own.
 *
 * It is a real object rather than an `if` in the ViewModel so that the seam is load-bearing from day one:
 * the hermetic suite drives a policy that returns [PassDecision.PassImmediately] and proves the app then
 * passes without any UI interaction, which is the proof that the auto-pass hook is wired and not
 * decorative.
 */
object ManualPassPolicy : PassPolicy {
    override fun decide(state: GameState): PassDecision = PassDecision.AskThePlayer
}
