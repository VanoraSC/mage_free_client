package magefree.network.game

/**
 * **What the server is waiting for**, as a closed, typed set — the app-schema mirror of the
 * `magefree.protocol.GamePrompt`, with the `:protocol` type kept off this ABI (the discipline).
 *
 * The point of a *closed* set is that a consumer can always know what a valid answer looks like. Each
 * subtype's doc therefore names the exact [GameClient] method that answers it, and those methods accept
 * only what that prompt can validly be answered with — an object id, a boolean, an amount, a mana type or
 * a key. There is no "send arbitrary text" escape hatch, because a prompt whose answer shape is unknown
 * must not be answered at all (see [Unrecognised]).
 *
 * **One at a time, not a queue.** [GameState.prompt] holds a single prompt. Upstream blocks the game
 * thread on the player's response, so at most one question is outstanding per game per seat; a new prompt
 * therefore *replaces* the previous one rather than joining it in a backlog. This mirrors the wire type's
 * `GamePrompted` contract exactly.
 */
sealed interface GamePrompt {
    /** The server's own prompt text, written for a human. Always present, even when it is empty. */
    val message: String

    /**
     * `GAME_SELECT` — **you have priority**: do something, or pass. The one prompt whose answer set is
     * the whole game, which is why [GameState.playable] matters: it is the server's list of what may be
     * chosen here.
     *
     * Answer with [GameClient.playObject] (an id from [GameState.playable]),
     * [GameClient.passPriority] (the pass/done arm), or [GameClient.useSpecialAction] when
     * [PromptOptions.SPECIAL_BUTTON] is offered.
     *
     * @property options the server's UI hints — button labels, and the possible attackers/blockers when
     *   this select is a combat declaration (see [PromptOptions]).
     */
    data class Select(
        override val message: String,
        val options: PromptOptions = PromptOptions(),
    ) : GamePrompt

    /**
     * `GAME_TARGET` — choose a target, or a card from a shown set.
     *
     * Answer with [GameClient.chooseTarget] (an id), or [GameClient.cancelPrompt] to decline — which is
     * only legitimate when [isRequired] is false or enough targets have already been chosen.
     *
     * @property cards the candidate cards the server sent with the prompt (upstream `cardsView1`); often
     *   empty, because many targets are permanents/players already visible in [GameState].
     * @property targetIds the ids the server marked as targetable or already chosen (upstream `targets`).
     * @property isRequired the server's `flag`: `false` means the choice may be declined.
     * @property options the server's UI hints, including [PromptOptions.POSSIBLE_TARGETS] and
     *   [PromptOptions.CHOSEN_TARGETS] where it supplied them.
     */
    data class Target(
        override val message: String,
        val cards: List<GameCard> = emptyList(),
        val targetIds: List<String> = emptyList(),
        val isRequired: Boolean = false,
        val options: PromptOptions = PromptOptions(),
    ) : GamePrompt

    /**
     * `GAME_ASK` — a yes/no question (a mulligan, an optional trigger, "do you want to …").
     *
     * Answer with [GameClient.answerAsk]. [PromptOptions.LEFT_BUTTON_TEXT] /
     * [PromptOptions.RIGHT_BUTTON_TEXT] carry the server's own labels for yes/no when it supplied them
     * (e.g. "Mulligan" / "Keep") — a UI must prefer those over hard-coded "Yes"/"No".
     */
    data class Ask(
        override val message: String,
        val options: PromptOptions = PromptOptions(),
    ) : GamePrompt

    /**
     * `GAME_CHOOSE_ABILITY` — pick which of an object's abilities (or which mode) to use.
     *
     * Answer with [GameClient.chooseAbility] carrying an [AbilityChoice.abilityId], or
     * [GameClient.cancelPrompt].
     *
     * @property choices the server's own ordered, pre-rendered list.
     */
    data class ChooseAbility(
        override val message: String,
        val choices: List<AbilityChoice> = emptyList(),
    ) : GamePrompt

    /**
     * `GAME_CHOOSE_PILE` — pick one of two piles of cards.
     *
     * Answer with [GameClient.choosePile]: `first = true` picks [pile1], `false` picks [pile2].
     */
    data class ChoosePile(
        override val message: String,
        val pile1: List<GameCard> = emptyList(),
        val pile2: List<GameCard> = emptyList(),
    ) : GamePrompt

    /**
     * `GAME_CHOOSE_CHOICE` — pick one entry from a server-supplied list (a colour, a card name, a mode).
     *
     * Answer with [GameClient.chooseChoice] carrying a [ChoiceOption.key]. When [specialText] is non-null
     * the choice also offers a "special" option, sent with the same key and `special = true`.
     *
     * @property choices the options, always normalised to key/label pairs — upstream's `Choice` comes in
     *   a plain-string and a keyed flavour, and the wire flattens both, so a caller never has to know which.
     * @property subMessage extra server-supplied detail below the message, when it sent any.
     * @property isRequired whether the choice may be declined.
     * @property specialText the label of the extra "special" option, or `null` when none is offered.
     */
    data class ChooseChoice(
        override val message: String,
        val choices: List<ChoiceOption> = emptyList(),
        val subMessage: String? = null,
        val isRequired: Boolean = false,
        val specialText: String? = null,
    ) : GamePrompt

    /**
     * `GAME_PLAY_MANA` — you owe mana: tap something for it, unlock some already floating, or cancel.
     *
     * Answer with [GameClient.playManaSource] (the source to tap — one of [GameState.playable]),
     * [GameClient.unlockMana] (a type already in a pool), [GameClient.useSpecialAction] when
     * [PromptOptions.SPECIAL_BUTTON] is offered, or [GameClient.cancelPrompt] to abandon the payment.
     */
    data class PlayMana(
        override val message: String,
        val options: PromptOptions = PromptOptions(),
    ) : GamePrompt

    /**
     * `GAME_PLAY_XMANA` — announce the value of X.
     *
     * Answer with [GameClient.announceX], or [GameClient.cancelPrompt].
     */
    data class PlayXMana(
        override val message: String,
        val options: PromptOptions = PromptOptions(),
    ) : GamePrompt

    /**
     * `GAME_GET_AMOUNT` — choose a number between [min] and [max] inclusive.
     *
     * Answer with [GameClient.chooseAmount].
     */
    data class GetAmount(
        override val message: String,
        val min: Int = 0,
        val max: Int = 0,
        val options: PromptOptions = PromptOptions(),
    ) : GamePrompt

    /**
     * `GAME_GET_MULTI_AMOUNT` — distribute a total between several entries (damage among blockers,
     * counters among permanents).
     *
     * Answer with [GameClient.distributeAmounts], passing one amount per [entries] item **in order**.
     *
     * @property entries the rows, each with its own label and bounds.
     * @property min the lower bound on the **total**; @property max the upper bound on the total.
     */
    data class GetMultiAmount(
        override val message: String,
        val entries: List<MultiAmountEntry> = emptyList(),
        val min: Int = 0,
        val max: Int = 0,
        val options: PromptOptions = PromptOptions(),
    ) : GamePrompt

    /**
     * A prompt this build does not recognise — the app-schema face of the `UnknownGamePrompt`, which
     * a newer bridge produces when it adds a prompt kind additively.
     *
     * **It must not be answered**: the whole value of a closed set is knowing what a valid reply is, and
     * for this one nothing does. [GameClient] therefore offers no method that takes it. A UI shows the
     * game as waiting and lets the user concede or quit; the next push replaces it.
     *
     * @property type the unrecognised discriminator the bridge sent, when it carried one.
     */
    data class Unrecognised(
        val type: String?,
    ) : GamePrompt {
        override val message: String get() = "unrecognised prompt"
    }
}

/** One option of a [GamePrompt.ChooseAbility]: the ability's id and the server's rendered text. */
data class AbilityChoice(
    val abilityId: String,
    val text: String,
)

/**
 * One option of a [GamePrompt.ChooseChoice].
 *
 * @property key what the reply carries — never show this; @property label what to show.
 */
data class ChoiceOption(
    val key: String,
    val label: String,
)

/** One row of a [GamePrompt.GetMultiAmount]: its label and its own bounds/default. */
data class MultiAmountEntry(
    val message: String,
    val min: Int = 0,
    val max: Int = 0,
    val defaultValue: Int = 0,
)

/**
 * The server's UI hints for a prompt — upstream's `options` map, split into two typed halves so nothing
 * arbitrary crosses this ABI: [text] holds the scalar entries rendered as strings, [ids] the entries that
 * are collections of object ids. Unknown keys are carried through rather than dropped, because the server
 * may send hints this build does not yet render.
 *
 * The well-known keys are named in the companion; use those constants rather than the raw strings.
 */
data class PromptOptions(
    val text: Map<String, String> = emptyMap(),
    val ids: Map<String, List<String>> = emptyMap(),
) {
    /** The server's label for the affirmative/left button (e.g. "Mulligan"), or `null` if it sent none. */
    val leftButtonText: String? get() = text[LEFT_BUTTON_TEXT]

    /** The server's label for the negative/right button (e.g. "Keep", "Done"), or `null`. */
    val rightButtonText: String? get() = text[RIGHT_BUTTON_TEXT]

    /**
     * The label of the extra "special" button, or `null` when the server offered none. When it is
     * non-null, [GameClient.useSpecialAction] is a valid answer to this prompt.
     */
    val specialButtonText: String? get() = text[SPECIAL_BUTTON]

    /** The permanents that may be declared as attackers in this select; empty outside a declaration. */
    val possibleAttackers: List<String> get() = ids[POSSIBLE_ATTACKERS].orEmpty()

    /** The permanents that may be declared as blockers in this select; empty outside a declaration. */
    val possibleBlockers: List<String> get() = ids[POSSIBLE_BLOCKERS].orEmpty()

    /** The targets already chosen for the current target requirement. */
    val chosenTargets: List<String> get() = ids[CHOSEN_TARGETS].orEmpty()

    /** The targets that may still be chosen. */
    val possibleTargets: List<String> get() = ids[POSSIBLE_TARGETS].orEmpty()

    /** The upstream keys these hints travel under; mirrored from the `GamePromptOptions`. */
    companion object {
        /** [text] key behind [leftButtonText]. */
        const val LEFT_BUTTON_TEXT: String = "UI.left.btn.text"

        /** [text] key behind [rightButtonText]. */
        const val RIGHT_BUTTON_TEXT: String = "UI.right.btn.text"

        /** [text] key behind [specialButtonText]. */
        const val SPECIAL_BUTTON: String = "specialButton"

        /** [ids] key behind [possibleAttackers]. */
        const val POSSIBLE_ATTACKERS: String = "possibleAttackers"

        /** [ids] key behind [possibleBlockers]. */
        const val POSSIBLE_BLOCKERS: String = "possibleBlockers"

        /** [ids] key behind [chosenTargets]. */
        const val CHOSEN_TARGETS: String = "chosenTargets"

        /** [ids] key behind [possibleTargets]. */
        const val POSSIBLE_TARGETS: String = "possibleTargets"
    }
}
