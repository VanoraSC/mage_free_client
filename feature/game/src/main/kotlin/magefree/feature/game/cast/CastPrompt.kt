package magefree.feature.game.cast

import magefree.network.game.GamePrompt

/*
 * The seam between the conversation the server has and the surface a player sees.
 *
 * A cast is not one question. The server asks an ordered sequence — optional costs, X, modes, targets,
 * and then **one prompt per mana source** — and each answer is submitted as it is given. That is the
 * design as of 2026-09-02 (plan §7.6): the client follows the server rather than assembling a cast
 * locally and replaying it, because the model that did the latter rested on a server-proposed payment
 * that does not exist.
 *
 * What is left for the client is form, never content. This file decides how each question is put; the
 * answer is always the player's, and always a direct reply to something just asked.
 *
 * **Everything here comes from `docs/upstream-cast-sequence.md`**, which was read out of the pinned
 * XMage source. In particular §3, the cancellation table, which is the part that cannot be guessed:
 * the server accepts a decline for some of these prompts and not others, and offering one where it
 * does not is a control the server discards.
 */

/**
 * Whether this prompt can be backed out of, and what the way out is called.
 *
 * Not a boolean, because the same wire message means two different things to a player. Declining a
 * mana prompt abandons the cast; declining an optional target means the player is finished choosing.
 * `GameClient.cancelPrompt`'s own documentation makes the point — it is one message and only one of
 * the two labels is ever right on a button.
 */
sealed interface CastExit {
    /**
     * The server accepts a decline here.
     *
     * @property label what to call it. The server's own wording wins when it sent any, exactly as the
     *   Prompt's button labels do elsewhere: upstream phrases these itself and its phrasing is part of
     *   the question.
     */
    data class Offered(
        val label: String,
    ) : CastExit

    /**
     * The server does not accept a decline, so nothing is offered.
     *
     * @property because why not, in words a player can act on. This is shown rather than swallowed —
     *   a missing button with no explanation is indistinguishable from a broken one.
     */
    data class NotAccepted(
        val because: String,
    ) : CastExit
}

/**
 * One question of a cast, as the surface should put it.
 *
 * @property headline the server's own message. Never rewritten: it is the question being answered.
 * @property exit whether backing out is possible here, per the trace's §3.
 * @property special the label for a special mana action — convoke, improvise, delve — or `null`.
 *   Driven entirely by whether the **server** offered one, which is what makes "only while it is still
 *   usable" true by construction: once a special payment has been used, upstream locks out normal mana
 *   abilities for the rest of the cast and stops offering the button (trace §2.4).
 * @property amount the range when the prompt asks for a number, else `null`.
 * @property choosesOnBoard whether the answer is picked on the battlefield rather than in the Prompt.
 *   Mana sources and targets are board picks; a yes/no is not.
 * @property answers the buttons that *answer* the question, as opposed to leaving it — currently only
 *   an optional cost's two. **Index 0 is the affirmative**, and the ordering is part of the contract
 *   rather than a convention, because the surface replies by index and getting it backwards would pay
 *   a cost the player declined. Empty for everything answered on the board or by a number.
 * @property choices the options the **server** listed, when the question is "which of these".
 *
 *   Every one of them is shown. The narrowing is upstream's and has already happened: it suppresses
 *   the picker entirely for a single mana ability, and `ManaUtil.tryToAutoPay` cuts a permanent's
 *   abilities down against the unpaid cost before any of this is sent (trace §2.7). So a picker that
 *   arrives is one where the choice is **real** — a dual land against a coloured cost, a spell that
 *   cares which colour paid — and dropping an option here would be choosing which mana to produce,
 *   which is content rather than form.
 */
data class CastPromptModel(
    val headline: String,
    val exit: CastExit,
    val special: String? = null,
    val amount: IntRange? = null,
    val choosesOnBoard: Boolean = false,
    val answers: List<String> = emptyList(),
    val choices: List<CastChoice> = emptyList(),
)

/**
 * One option of a picker the server sent.
 *
 * @property reply what the answer carries. Opaque here on purpose: it is an ability id for one prompt
 *   and a choice key for another, and the surface knows which because it was handed the prompt.
 * @property label what to show — the server's own rendered text, never rebuilt from the reply.
 */
data class CastChoice(
    val reply: String,
    val label: String,
)

/**
 * How this prompt should be put to the player.
 *
 * The cancellation rules are upstream's, transcribed:
 *
 * - **Mana — always.** A boolean response cancels, aborting the payment loop.
 * - **Targets — usually.** Upstream sets `required = false` when the ability is an activated one cast
 *   by a human, and `true` for a free cast such as Suspend. So the prompt carries the answer and
 *   nothing here re-derives it.
 * - **Modes — yes**, on the same `required` flag.
 * - **A number — never.** `announceX` loops until a valid value arrives, and so does the repetition
 *   prompt. Once asked, it must be answered.
 * - **An optional cost — not a cancel at all.** It is a yes/no, and declining continues the cast.
 */
fun castPromptModel(prompt: GamePrompt): CastPromptModel =
    when (prompt) {
        is GamePrompt.PlayMana ->
            CastPromptModel(
                headline = prompt.message,
                exit = CastExit.Offered(prompt.options.rightButtonText ?: CANCEL_PAYMENT),
                special = prompt.options.specialButtonText,
                choosesOnBoard = true,
            )

        is GamePrompt.Target ->
            CastPromptModel(
                headline = prompt.message,
                exit =
                    if (prompt.isRequired) {
                        CastExit.NotAccepted(TARGET_REQUIRED)
                    } else {
                        CastExit.Offered(prompt.options.rightButtonText ?: DONE_CHOOSING)
                    },
                special = prompt.options.specialButtonText,
                choosesOnBoard = true,
            )

        is GamePrompt.ChooseChoice ->
            CastPromptModel(
                headline = prompt.message,
                exit =
                    if (prompt.isRequired) {
                        CastExit.NotAccepted(CHOICE_REQUIRED)
                    } else {
                        CastExit.Offered(CANCEL_CAST)
                    },
                choices = prompt.choices.map { CastChoice(reply = it.key, label = it.label) },
            )

        // The one point of no return in a cast. Said out loud rather than left to be discovered: a
        // player who changes their mind about an X spell has to answer, ride through to the mana
        // prompts, and cancel there instead.
        is GamePrompt.GetAmount ->
            CastPromptModel(
                headline = prompt.message,
                exit = CastExit.NotAccepted(AMOUNT_IS_FINAL),
                amount = prompt.min..prompt.max,
            )

        // Dead upstream: `firePlayXManaEvent` is declared, implemented and wired to a callback, and
        // nothing calls it (trace §2.1). Handled rather than dropped, because a callback that arrives
        // and renders nothing is worse than one that arrives and is put honestly.
        is GamePrompt.PlayXMana ->
            CastPromptModel(
                headline = prompt.message,
                exit = CastExit.NotAccepted(AMOUNT_IS_FINAL),
            )

        // Which of this permanent's abilities to use — a dual land against a coloured cost, most
        // often. Every option upstream sent is offered, because upstream has already removed the ones
        // that were not real (trace §2.7).
        is GamePrompt.ChooseAbility ->
            CastPromptModel(
                headline = prompt.message,
                exit = CastExit.Offered(CANCEL_CAST),
                choices = prompt.choices.map { CastChoice(reply = it.abilityId, label = it.text) },
            )

        // A yes/no. Both answers continue the cast, so there is nothing to back out of — and the
        // server's own wording is the question: upstream sends "Mulligan"/"Keep" where that is what it
        // means, and answering a differently-worded question is answering a different one.
        is GamePrompt.Ask ->
            CastPromptModel(
                headline = prompt.message,
                exit = CastExit.NotAccepted(ASK_IS_ANSWERABLE),
                answers =
                    listOf(
                        prompt.options.leftButtonText ?: DEFAULT_YES,
                        prompt.options.rightButtonText ?: DEFAULT_NO,
                    ),
            )

        is GamePrompt.Select ->
            CastPromptModel(
                headline = prompt.message,
                exit = CastExit.NotAccepted(SELECT_IS_PRIORITY),
                special = prompt.options.specialButtonText,
                choosesOnBoard = true,
            )

        is GamePrompt.GetMultiAmount ->
            CastPromptModel(headline = prompt.message, exit = CastExit.NotAccepted(AMOUNT_IS_FINAL))

        is GamePrompt.ChoosePile ->
            CastPromptModel(headline = prompt.message, exit = CastExit.NotAccepted(CHOICE_REQUIRED))

        // Deliberately opaque: an unrecognised prompt has no known answer, so it certainly has no
        // known way out. Saying so beats offering a button that may do nothing.
        is GamePrompt.Unrecognised ->
            CastPromptModel(headline = prompt.message, exit = CastExit.NotAccepted(UNRECOGNISED))
    }

/** Default label for abandoning a payment — the server's own wording wins when it sent one. */
internal const val CANCEL_PAYMENT: String = "Cancel"

/** Default label for finishing an optional target choice. Declining here means "I am done". */
internal const val DONE_CHOOSING: String = "Done"

/** Default label for backing out of a cast at a choice the server lets you decline. */
internal const val CANCEL_CAST: String = "Cancel"

internal const val TARGET_REQUIRED: String = "This spell was cast without paying its cost, so its targets can't be taken back."

internal const val CHOICE_REQUIRED: String = "This choice has to be made before the spell can go on the stack."

internal const val AMOUNT_IS_FINAL: String = "Once announced this can't be taken back — you can still cancel when paying."

/** Fallbacks when the server sent no wording of its own. */
internal const val DEFAULT_YES: String = "Yes"

internal const val DEFAULT_NO: String = "No"

internal const val ASK_IS_ANSWERABLE: String = "Either answer carries on with the spell."

internal const val SELECT_IS_PRIORITY: String = "Nothing is being cast yet."

internal const val UNRECOGNISED: String = "This build does not know what is being asked, so it does not know how to leave it."
