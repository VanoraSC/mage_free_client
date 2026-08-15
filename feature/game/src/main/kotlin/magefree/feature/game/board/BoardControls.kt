package magefree.feature.game.board

import magefree.network.game.GameCard
import magefree.network.game.GamePrompt
import magefree.network.game.GameState
import magefree.network.game.ManaType
import magefree.network.game.PromptOptions

/*
 * The **answering** half of the board (story 0057): a pure projection of the server's outstanding
 * prompt into the floating controls that answer it, and the closed set of actions those controls emit.
 *
 * No Compose type, no Android type, no coroutine — exactly as [BoardUi] is — so every prompt kind, every
 * cancel path and every "what may I tap right now" question is provable in a plain JVM unit test.
 *
 * ## Reachability record (verification standard 2) — what produces each thing the controls gate on
 *
 * | Gated on | Produced by |
 * |---|---|
 * | which controls exist at all | `GameState.prompt` — 0052: "**the** outstanding question, or null when the server is not waiting on the viewer" |
 * | the question's wording | `GamePrompt.message`, HTML-stripped by [stripServerMarkup] |
 * | button labels for yes/no and done | `PromptOptions.leftButtonText` / `rightButtonText` (upstream `UI.left.btn.text` / `UI.right.btn.text`), falling back to our own words only when the server sent none |
 * | whether a *special* button exists | `PromptOptions.specialButtonText` (upstream `specialButton`) — the server telling us the button is valid |
 * | which board objects may be **played** | `GameState.playable` (upstream `GameView.canPlayObjects`) |
 * | which board objects may be **targeted** | the prompt's own `targetIds` + `PromptOptions.possibleTargets` |
 * | which creatures may be **declared as attackers** | `PromptOptions.possibleAttackers` on the server's `Select` during `DeclareAttackers` (upstream `possibleAttackers`) — **not** `playable`, which is empty there |
 * | which creatures may be **declared as blockers** | `PromptOptions.possibleBlockers` on the server's `Select` during `DeclareBlockers` — likewise not `playable` |
 * | which of combat's two roles the board is in | which of those two keys the server sent ([CombatRole.of]) |
 * | which targets are already chosen | `PromptOptions.chosenTargets` |
 * | candidate cards not on the board (scry, piles) | the prompt's own `cards` / `pile1` / `pile2` |
 * | which mana types may be unlocked | the viewer's own `GamePlayer.manaPool` |
 * | whether cancel may be offered | `GamePrompt.Target.isRequired` / `ChooseChoice.isRequired`, and 0052's list of the four prompts `cancelPrompt` actually answers |
 *
 * ## The three things this file exists to get right
 *
 * 1. **Nothing is modal** (§16.2). These are controls that *float over* the board; the board stays
 *    visible and live underneath for every prompt kind, including the ones §6.2 used to send to a
 *    dialog. The distinction §6.2 drew — answered *from the prompt's own content* vs answered *by
 *    touching the board* — survives as [pickableObjectIds] being empty or not, and nothing else.
 * 2. **Target picks are never batched** (§17.2, verified live). The prompt reads *"Select targets
 *    (selected 0 of 2, min 1)"* and the server **re-prompts with an updated count and candidate set
 *    after each pick**, so a client that accumulated picks against the first candidate list could
 *    assemble a combination the server rejects. Each tap emits [BoardAction.ChooseTarget] on its own;
 *    the player's confirmation emits [BoardAction.FinishTargeting], which is the final *done*.
 * 3. **Legality and mana payment are never computed** (§0, §6.5). Every id offered here came from the
 *    server in this snapshot. When the server offers nothing, the controls say so rather than inventing
 *    something tappable.
 */

/**
 * Everything the player can do to the game right now — the closed set of things a floating control, a
 * tapped card or the board menu may emit.
 *
 * One sealed set, dispatched in exactly one place ([GameBoardViewModel.act]), so the answer to "what can
 * this screen send to the server?" is this file and nothing else.
 */
sealed interface BoardAction {
    /** Play or activate an object the server listed in [GameState.playable]. Answers a `Select`. */
    data class PlayObject(
        val objectId: String,
    ) : BoardAction

    /**
     * Pass priority — the plain "I'm done here". Routed through [PassPolicy]'s single seam, never sent
     * from a Composable.
     */
    data object PassPriority : BoardAction

    /** Use the server's extra *special* button, valid only when it offered one. */
    data object UseSpecial : BoardAction

    /**
     * Choose one target. Sent **per pick** — see the file header: the server re-prompts after each one.
     */
    data class ChooseTarget(
        val targetId: String,
    ) : BoardAction

    /**
     * The player's confirmation that they are done choosing targets — the final *done*
     * (`SendPlayerBoolean(false)`), which §17.2 shows is legitimate once `min` is satisfied.
     *
     * It closes a **combat declaration** too (story 0061): upstream ends `Select attackers` /
     * `Select blockers` with the same shared "done / cancel" arm, and both probes closed a declaration
     * with exactly this message. The label differs per role; the action does not.
     *
     * The same wire message as [CancelPrompt], and a **different action** on purpose: they mean opposite
     * things to the player, the board labels them differently, and a reader of [GameBoardViewModel.act]
     * should not have to work out which one a bare "cancel" was.
     */
    data object FinishTargeting : BoardAction

    /**
     * Decline the outstanding prompt and let the server rewind — the cancel of §16.5, proven live to
     * return the card to hand, clear the stack and leave mana unspent at both the **target** step
     * (§17.1) and the **mana** step (§6.4a).
     */
    data object CancelPrompt : BoardAction

    /** Answer a yes/no question. */
    data class AnswerAsk(
        val yes: Boolean,
    ) : BoardAction

    /** Choose one of an object's abilities, or one mode of a modal spell. */
    data class ChooseAbility(
        val abilityId: String,
    ) : BoardAction

    /** Choose the first or the second pile. */
    data class ChoosePile(
        val first: Boolean,
    ) : BoardAction

    /** Choose one entry of a server-supplied list, optionally its *special* arm. */
    data class ChooseChoice(
        val key: String,
        val special: Boolean = false,
    ) : BoardAction

    /** Tap one of your own sources for mana. Never computed — the source came from the server. */
    data class PlayManaSource(
        val sourceId: String,
    ) : BoardAction

    /** Spend mana already floating in your pool. */
    data class UnlockMana(
        val manaType: ManaType,
    ) : BoardAction

    /** Announce the value of X. */
    data class AnnounceX(
        val value: Int,
    ) : BoardAction

    /** Answer a "choose a number" with a value inside the prompt's own bounds. */
    data class ChooseAmount(
        val amount: Int,
    ) : BoardAction

    /** Distribute a total, one amount per row **in the prompt's order** (upstream parses positionally). */
    data class DistributeAmounts(
        val amounts: List<Int>,
    ) : BoardAction

    /** Concede **this game** — the opponent wins it. Separate from [QuitMatch] (§12.2). */
    data object Concede : BoardAction

    /** Leave the whole **match**. Separate from [Concede] (§12.2), mirroring upstream's separate verbs. */
    data object QuitMatch : BoardAction
}

/**
 * One floating button: what it says, what it sends, and how prominent it is.
 *
 * @property confirmLabel when non-null, the button **arms** on the first press and sends only on the
 *   second, which presses [confirmLabel] instead. §16.4's confirm-before-submit, applied to the buttons
 *   that commit something in one press rather than to every button. Its state belongs to the control
 *   (nothing is held back from the server; nothing has been sent yet either), so an armed button that
 *   is never pressed again simply does nothing.
 */
data class ControlButton(
    val label: String,
    val action: BoardAction,
    val isPrimary: Boolean = false,
    val confirmLabel: String? = null,
)

/**
 * Which half of combat the board is in — **never both** (§7.4, Pete).
 *
 * > *"the attacking player assigns attackers to targets, player or battle or Planeswalker, etc. the
 * > blocker assigns blockers to attackers. we need to consider how best to represent each of these
 * > situations as they never occur for the same player at the same time"*
 *
 * The server decides which: a declaration `Select` carries `possibleAttackers` **or** `possibleBlockers`,
 * and the role is read from whichever it sent. It is a role, not a mode: there is no state here to get
 * out of step with the game, and no snapshot can leave the board in a role the server is not in.
 */
enum class CombatRole {
    /** `Select attackers` — each attacker is assigned to a defender (player, planeswalker or battle). */
    Attacking,

    /** `Select blockers` — each blocker is assigned to an attacker. */
    Blocking,
    ;

    /** The ids this role's declaration offers, from the prompt's own options. Never from `playable`. */
    internal fun candidatesIn(options: PromptOptions): List<String> =
        when (this) {
            Attacking -> options.possibleAttackers
            Blocking -> options.possibleBlockers
        }

    /** What a tap on one of them is called. */
    internal fun actionLabel(): String =
        when (this) {
            Attacking -> DECLARE_ATTACKER_ACTION_LABEL
            Blocking -> DECLARE_BLOCKER_ACTION_LABEL
        }

    /** What the button that ends the declaration says. */
    internal fun doneLabel(): String =
        when (this) {
            Attacking -> DONE_DECLARING_ATTACKERS_LABEL
            Blocking -> DONE_DECLARING_BLOCKERS_LABEL
        }

    /** The panel's instruction line for this role — one is on screen, never both. */
    internal fun note(): String =
        when (this) {
            Attacking -> DECLARE_ATTACKERS_NOTE
            Blocking -> DECLARE_BLOCKERS_NOTE
        }

    /** The panel's headline for this role. */
    internal fun title(): String =
        when (this) {
            Attacking -> DECLARING_ATTACKERS_TITLE
            Blocking -> DECLARING_BLOCKERS_TITLE
        }

    internal companion object {
        /**
         * The role a `Select` is a declaration for, or null when it is an ordinary priority window.
         *
         * **The signal is the key, not its contents.** Upstream's `selectAttackers` puts
         * `possibleAttackers` into the options **unconditionally** and only then decides whether to
         * prompt, so a player whose creatures all cannot attack gets `Select attackers` with an *empty*
         * list. Keying off "the list is non-empty" would project that as an ordinary priority window —
         * a "Pass priority" button on a prompt that is not a priority window, and a board that says
         * nothing about the step it is in. Keying off the key itself keeps it honest: the board is
         * declaring attackers, and there happen to be none to declare.
         *
         * **Attackers win if both keys ever arrive together.** The server never sends both — the two
         * roles never belong to the same player at the same moment (§7.4) — but if it ever did, the
         * board must still be in exactly one of them: offering the union would be offering blocks during
         * a declare-attackers step, which is the failure this ordering exists to make impossible.
         */
        fun of(options: PromptOptions): CombatRole? =
            when {
                options.ids.containsKey(PromptOptions.POSSIBLE_ATTACKERS) -> Attacking
                options.ids.containsKey(PromptOptions.POSSIBLE_BLOCKERS) -> Blocking
                else -> null
            }
    }
}

/** A card the *prompt itself* carried (a scry card, a pile), which is not on the board to be tapped. */
data class CandidateCardUi(
    val objectId: String,
    val card: CardUi,
    val isChosen: Boolean = false,
)

/** A "choose a number" request, with the server's own bounds. */
data class AmountRequestUi(
    val min: Int,
    val max: Int,
    val kind: AmountKind,
)

/** Which reply an [AmountRequestUi] is answered with. */
enum class AmountKind {
    /** `GAME_GET_AMOUNT` → [BoardAction.ChooseAmount]. */
    Amount,

    /** `GAME_PLAY_XMANA` → [BoardAction.AnnounceX]. */
    AnnounceX,
}

/** One row of a distribute-a-total prompt. */
data class AmountRowUi(
    val label: String,
    val min: Int,
    val max: Int,
    val initial: Int,
)

/**
 * The floating controls for the outstanding prompt — one subtype per way a prompt is answered.
 *
 * Null when the server is not waiting on the viewer, in which case the board draws no prompt controls at
 * all (the board menu and the visibility toggle are always there, and are not prompt controls).
 */
sealed interface PromptControlsUi {
    /** The server's own question, HTML stripped; null when it sent nothing readable. */
    val message: String?

    /** The buttons this prompt is answered with. Empty only for [Notice]. */
    val buttons: List<ControlButton>

    /** Board objects the player may tap right now. Empty for a prompt answered from its own content. */
    val pickableObjectIds: Set<String> get() = emptySet()

    /** Board objects the server says are already chosen for this prompt. */
    val chosenObjectIds: Set<String> get() = emptySet()

    /** Cards the prompt carried itself, for prompts whose candidates are not on the board. */
    val candidateCards: List<CandidateCardUi> get() = emptyList()

    /** A number the player must pick, or null. */
    val amountRequest: AmountRequestUi? get() = null

    /** Rows of a distribute-a-total prompt, or empty. */
    val amountRows: List<AmountRowUi> get() = emptyList()

    /**
     * Whether the app knows a valid answer at all. False **only** for [Notice] — see
     * [GamePrompt.Unrecognised], which deliberately has no answering method.
     */
    val isAnswerable: Boolean get() = true

    /**
     * The action a tap on the board object [objectId] means right now, or null when tapping it means
     * nothing to the server. Card detail (§11.1) uses it to decide whether it can offer a play button.
     */
    fun actionFor(objectId: String): BoardAction? = null

    /** What a tap on [objectId] would be called, for the label on the detail view's button. */
    fun actionLabelFor(objectId: String): String? = null

    /**
     * `GAME_SELECT` — **you hold priority**: play something the server offered, or pass.
     *
     * The pass button is here, but the pass itself is not: [BoardAction.PassPriority] goes through
     * [PassPolicy]'s seam in the ViewModel (§14.1).
     */
    data class Priority(
        override val message: String?,
        override val pickableObjectIds: Set<String>,
        override val buttons: List<ControlButton>,
    ) : PromptControlsUi {
        override fun actionFor(objectId: String): BoardAction? =
            if (objectId in pickableObjectIds) BoardAction.PlayObject(objectId) else null

        override fun actionLabelFor(objectId: String): String? = if (objectId in pickableObjectIds) PLAY_ACTION_LABEL else null
    }

    /**
     * `GAME_SELECT` **during a combat declaration** — the same prompt *kind* as [Priority], and a
     * different question entirely (story 0061).
     *
     * The server distinguishes it by its **options**, not by its type: a declaration carries
     * `possibleAttackers` or `possibleBlockers` (requirements §7.2/§7.3, measured live). It is its own
     * case rather than a [Priority] with extra ids because everything about it differs — where the ids
     * come from, what a tap means, what the closing button says, and what the panel tells the player to
     * do.
     *
     * **Why this exists at all:** `playable` is *empty* during a declaration, so [Priority] — which
     * derives its pickable set from `playable` — offered nothing to tap. The board could attack with
     * everything (the server's own "All attack" button) or nothing, and could not block at all.
     *
     * @property role which of combat's two assignment problems this is (§7.4). Exactly one, always.
     */
    data class Declaration(
        override val message: String?,
        override val pickableObjectIds: Set<String>,
        override val buttons: List<ControlButton>,
        val role: CombatRole,
    ) : PromptControlsUi {
        /**
         * A declaration pick is a `chooseTarget` — the same verb targeting uses, which is what upstream
         * expects here (`HumanPlayer` answers both from the same select loop, and the probes declared
         * live this way). It is sent per tap, never batched: the server re-prompts after each pick with
         * the remaining candidates, exactly as it does for targets (§17.2).
         */
        override fun actionFor(objectId: String): BoardAction? =
            if (objectId in pickableObjectIds) BoardAction.ChooseTarget(objectId) else null

        override fun actionLabelFor(objectId: String): String? = if (objectId in pickableObjectIds) role.actionLabel() else null
    }

    /**
     * `GAME_TARGET` — pick targets on the board (or from the cards the prompt carried), one at a time.
     *
     * @property hasPicked whether the player has already sent at least one pick in this target
     *   sequence. It decides the *label* on the closing button and nothing else: with a pick sent, the
     *   button is the player's **done**; with none, it is a **cancel** that rewinds the cast (§17.1).
     *   Its producer is the ViewModel's own record of what it has sent — deliberately not the prompt
     *   text, which states progress in prose ("selected 0 of 2, min 1") that this app does not parse.
     */
    data class Targeting(
        override val message: String?,
        override val pickableObjectIds: Set<String>,
        override val chosenObjectIds: Set<String>,
        override val candidateCards: List<CandidateCardUi>,
        override val buttons: List<ControlButton>,
        val hasPicked: Boolean,
    ) : PromptControlsUi {
        override fun actionFor(objectId: String): BoardAction? =
            if (objectId in pickableObjectIds) BoardAction.ChooseTarget(objectId) else null

        override fun actionLabelFor(objectId: String): String? = if (objectId in pickableObjectIds) TARGET_ACTION_LABEL else null
    }

    /**
     * `GAME_PLAY_MANA` — you owe mana. The player taps their own sources on the board (§6.3); the
     * server has **already** narrowed the choice (`ManaUtil.tryToAutoPay`, §6.5), so what is offered
     * here is the residue it declined to guess at, rendered as-is.
     */
    data class Mana(
        override val message: String?,
        override val pickableObjectIds: Set<String>,
        override val buttons: List<ControlButton>,
    ) : PromptControlsUi {
        override fun actionFor(objectId: String): BoardAction? =
            if (objectId in pickableObjectIds) BoardAction.PlayManaSource(objectId) else null

        override fun actionLabelFor(objectId: String): String? = if (objectId in pickableObjectIds) MANA_ACTION_LABEL else null
    }

    /**
     * Every prompt answered by pressing one of a set of buttons: `GAME_ASK`, `GAME_CHOOSE_ABILITY`,
     * `GAME_CHOOSE_CHOICE` and `GAME_CHOOSE_PILE`. §6.2 called these "answered from the prompt's own
     * content" and sent them to a modal; §16.2 keeps the distinction and drops the modal.
     *
     * @property detail the server's extra sub-message where it sent one.
     */
    data class Choices(
        override val message: String?,
        override val buttons: List<ControlButton>,
        override val candidateCards: List<CandidateCardUi> = emptyList(),
        val detail: String? = null,
    ) : PromptControlsUi

    /** `GAME_GET_AMOUNT` / `GAME_PLAY_XMANA` — pick a number inside the server's bounds. */
    data class Amount(
        override val message: String?,
        override val amountRequest: AmountRequestUi?,
        override val buttons: List<ControlButton>,
    ) : PromptControlsUi

    /** `GAME_GET_MULTI_AMOUNT` — distribute a total across the server's own rows, in order. */
    data class MultiAmount(
        override val message: String?,
        override val amountRows: List<AmountRowUi>,
        override val buttons: List<ControlButton>,
    ) : PromptControlsUi

    /**
     * A prompt this build does not recognise: shown as a **notice**, never as a control.
     *
     * 0052 gives [GamePrompt.Unrecognised] no answering method on purpose — nothing knows what a valid
     * reply to it is — so offering any button here would be offering the player something they cannot
     * satisfy. The board menu (concede / quit) stays available, which is exactly what 0052's KDoc says a
     * UI should do.
     */
    data class Notice(
        override val message: String?,
    ) : PromptControlsUi {
        override val buttons: List<ControlButton> get() = emptyList()
        override val isAnswerable: Boolean get() = false
    }
}

/**
 * Project the outstanding prompt of [state] onto the floating controls that answer it.
 *
 * @param hasPickedTarget whether the app has already sent a pick for the target sequence in flight —
 *   the ViewModel's own record, which decides whether the closing button reads *done* or *cancel*.
 */
internal fun controlsFor(
    state: GameState,
    hasPickedTarget: Boolean = false,
): PromptControlsUi? {
    val prompt = state.prompt ?: return null
    val message = prompt.message.cleanedOrNull()
    // The server's own list of what may be played/tapped right now. Never recomputed, never widened.
    val offeredIds = state.playable.map { it.objectId }.toSet()

    return when (prompt) {
        // A `Select` is **two** different questions, told apart by its options and not by its type: a
        // combat declaration when it carries `possibleAttackers`/`possibleBlockers` (§7.2/§7.3), an
        // ordinary priority window otherwise.
        is GamePrompt.Select ->
            when (val role = CombatRole.of(prompt.options)) {
                null ->
                    PromptControlsUi.Priority(
                        message = message,
                        pickableObjectIds = offeredIds,
                        buttons =
                            buildList {
                                // Pass stays first: it is the single most repeated interaction in a game (§9.1).
                                add(ControlButton(label = PASS_LABEL, action = BoardAction.PassPriority, isPrimary = true))
                                prompt.options.specialButtonText?.cleanedOrNull()?.let {
                                    add(ControlButton(label = it, action = BoardAction.UseSpecial))
                                }
                                // An offer the board cannot draw is still an offer — see [offBoardCandidateButtons].
                                addAll(offBoardCandidateButtons(state, offeredIds, BoardAction::PlayObject))
                            },
                    )

                else -> declarationControls(state, prompt, message, role)
            }

        is GamePrompt.Target -> {
            // Both halves of the server's own answer: what may still be picked, and what it already
            // holds. Nothing is filtered out — a target the server lists is a target the server accepts.
            val pickable = (prompt.targetIds + prompt.options.possibleTargets).toSet()
            val chosen = prompt.options.chosenTargets.toSet()
            // The server's own `chosenTargets` wins where it sends one; the caller's record of what it
            // has sent is the fallback for the (common) case where it sends none.
            val hasPicked = chosen.isNotEmpty() || hasPickedTarget
            PromptControlsUi.Targeting(
                message = message,
                pickableObjectIds = pickable,
                chosenObjectIds = chosen,
                candidateCards = prompt.cards.map { it.toCandidate(chosen) },
                hasPicked = hasPicked,
                buttons =
                    buildList {
                        // Candidates the board cannot draw — players, above all — come first, because
                        // without them this prompt has no answer at all.
                        addAll(offBoardCandidateButtons(state, pickable, BoardAction::ChooseTarget))
                        // The player's confirmation *is* the final done (§17.2) — it is not a
                        // client-side accumulator being flushed, because every pick was already sent.
                        if (hasPicked) {
                            add(
                                ControlButton(
                                    label = prompt.options.rightButtonText?.cleanedOrNull() ?: DONE_LABEL,
                                    action = BoardAction.FinishTargeting,
                                    isPrimary = true,
                                ),
                            )
                        }
                        // Cancel is offered exactly where the server says the choice may be declined.
                        if (!prompt.isRequired) {
                            add(ControlButton(label = CANCEL_CAST_LABEL, action = BoardAction.CancelPrompt))
                        }
                    },
            )
        }

        is GamePrompt.PlayMana ->
            PromptControlsUi.Mana(
                message = message,
                pickableObjectIds = offeredIds,
                buttons =
                    buildList {
                        // A source the board cannot draw is still a source the server offered.
                        addAll(offBoardCandidateButtons(state, offeredIds, BoardAction::PlayManaSource))
                        // Mana already floating: the pool is the producer, and only types actually in it
                        // are offered.
                        state.viewer?.manaPool?.let { pool ->
                            floatingTypes(pool).forEach { (type, count) ->
                                add(
                                    ControlButton(
                                        label = "$UNLOCK_MANA_PREFIX ${type.label()} ($count)",
                                        action = BoardAction.UnlockMana(type),
                                    ),
                                )
                            }
                        }
                        prompt.options.specialButtonText?.cleanedOrNull()?.let {
                            add(ControlButton(label = it, action = BoardAction.UseSpecial))
                        }
                        add(ControlButton(label = CANCEL_CAST_LABEL, action = BoardAction.CancelPrompt))
                    },
            )

        is GamePrompt.Ask ->
            PromptControlsUi.Choices(
                message = message,
                buttons =
                    listOf(
                        ControlButton(
                            // The server's own wording wins over ours: it says "Mulligan"/"Keep" where
                            // "Yes"/"No" would be actively misleading.
                            label = prompt.options.leftButtonText?.cleanedOrNull() ?: YES_LABEL,
                            action = BoardAction.AnswerAsk(yes = true),
                            isPrimary = true,
                        ),
                        ControlButton(
                            label = prompt.options.rightButtonText?.cleanedOrNull() ?: NO_LABEL,
                            action = BoardAction.AnswerAsk(yes = false),
                        ),
                    ),
            )

        is GamePrompt.ChooseAbility ->
            PromptControlsUi.Choices(
                message = message,
                buttons =
                    buildList {
                        prompt.choices.forEach { choice ->
                            add(
                                ControlButton(
                                    label = choice.text.cleanedOrNull() ?: choice.abilityId,
                                    action = BoardAction.ChooseAbility(choice.abilityId),
                                ),
                            )
                        }
                        // `cancelPrompt` answers this prompt (0052), so the cast can be backed out of
                        // here too. Whether the *server* rewinds the whole cast from a mode choice is
                        // its behaviour and is recorded as unverified — the board claims nothing.
                        add(ControlButton(label = CANCEL_CAST_LABEL, action = BoardAction.CancelPrompt))
                    },
            )

        is GamePrompt.ChoosePile ->
            PromptControlsUi.Choices(
                message = message,
                buttons =
                    listOf(
                        ControlButton(
                            label = "$PILE_LABEL 1 (${prompt.pile1.size})",
                            action = BoardAction.ChoosePile(first = true),
                            isPrimary = true,
                        ),
                        ControlButton(label = "$PILE_LABEL 2 (${prompt.pile2.size})", action = BoardAction.ChoosePile(first = false)),
                    ),
                candidateCards = (prompt.pile1 + prompt.pile2).map { it.toCandidate(emptySet()) },
            )

        is GamePrompt.ChooseChoice ->
            PromptControlsUi.Choices(
                message = message,
                detail = prompt.subMessage?.cleanedOrNull(),
                buttons =
                    buildList {
                        prompt.choices.forEach { option ->
                            add(
                                ControlButton(
                                    label = option.label.cleanedOrNull() ?: option.key,
                                    action = BoardAction.ChooseChoice(option.key),
                                ),
                            )
                        }
                        prompt.specialText?.cleanedOrNull()?.let { special ->
                            // The special arm is sent with the *same* key and `special = true`, so it
                            // only exists once there is a key to send it with.
                            prompt.choices.firstOrNull()?.let { first ->
                                add(ControlButton(label = special, action = BoardAction.ChooseChoice(first.key, special = true)))
                            }
                        }
                    },
            )

        is GamePrompt.GetAmount ->
            PromptControlsUi.Amount(
                message = message,
                amountRequest = AmountRequestUi(min = prompt.min, max = prompt.max, kind = AmountKind.Amount),
                // The confirm button is built by the control itself, which owns the draft value; there is
                // no fixed action here because the action carries the number.
                buttons = emptyList(),
            )

        is GamePrompt.PlayXMana ->
            PromptControlsUi.Amount(
                message = message,
                amountRequest = AmountRequestUi(min = 0, max = X_ANNOUNCE_CEILING, kind = AmountKind.AnnounceX),
                buttons = listOf(ControlButton(label = CANCEL_CAST_LABEL, action = BoardAction.CancelPrompt)),
            )

        is GamePrompt.GetMultiAmount ->
            PromptControlsUi.MultiAmount(
                message = message,
                amountRows =
                    prompt.entries.map { entry ->
                        AmountRowUi(
                            label = entry.message.cleanedOrNull() ?: "",
                            min = entry.min,
                            max = entry.max,
                            initial = entry.defaultValue.coerceIn(entry.min, maxOf(entry.min, entry.max)),
                        )
                    },
                buttons = emptyList(),
            )

        // No answering method exists, so no control is offered — only the notice.
        is GamePrompt.Unrecognised -> PromptControlsUi.Notice(message = UNRECOGNISED_PROMPT_NOTICE)
    }
}

/**
 * The controls for one half of combat — story 0061's whole answer to *"the board cannot declare"*.
 *
 * **Where the ids come from, and why it matters.** `role.candidatesIn(options)` reads
 * `possibleAttackers` / `possibleBlockers` off the prompt the server just sent. It does **not** read
 * `state.playable`, which is empty during a declaration (§7.2, measured live) — that is the defect this
 * function exists to fix, and it is why the tests build their fixtures with `playable` empty.
 *
 * **The server's set is never widened.** `getCreaturesForcedToAttack` is consulted upstream before
 * `selectDefender`, so a creature *forced* to attack has a narrower legal-defender set than the general
 * one. Whatever the server offers is what is offered here, in full and no further.
 */
private fun declarationControls(
    state: GameState,
    prompt: GamePrompt.Select,
    message: String?,
    role: CombatRole,
): PromptControlsUi.Declaration {
    val candidates = role.candidatesIn(prompt.options)
    return PromptControlsUi.Declaration(
        message = message,
        role = role,
        // Exactly this role's candidates: never the union of both roles, never `playable`, never a set
        // this app worked out for itself.
        pickableObjectIds = candidates.toSet(),
        buttons =
            buildList {
                // A candidate the board cannot draw is still a candidate — the same promotion every
                // other prompt gets. A creature is normally on a battlefield the board draws, so this
                // is usually empty; it costs nothing and it cannot be the reason a declaration is
                // unanswerable.
                addAll(offBoardCandidateButtons(state, candidates, BoardAction::ChooseTarget))
                // The *done*: upstream's shared "done / cancel" arm, which is how a declaration is
                // closed (both probes closed one exactly this way). Never a pass — a declaration is not
                // a priority window, whatever prompt kind it arrives as.
                add(
                    ControlButton(
                        label = prompt.options.rightButtonText?.cleanedOrNull() ?: role.doneLabel(),
                        action = BoardAction.FinishTargeting,
                        isPrimary = true,
                    ),
                )
                // "All attack" — the server's own shortcut, kept because it is the server's and it is
                // proven to work live, and **confirmed** because it commits the whole team in one press
                // (§16.4 / §7.5, Pete). Blocking gets no equivalent and none is invented: this button
                // exists only where the server sent a `specialButton`, which it does only for attacking.
                prompt.options.specialButtonText?.cleanedOrNull()?.let {
                    add(ControlButton(label = it, action = BoardAction.UseSpecial, confirmLabel = ALL_ATTACK_CONFIRM_LABEL))
                }
            },
    )
}

/**
 * The object ids the board actually **draws**: the viewer's hand, both battlefields, and the stack.
 *
 * This is the honest definition of "tappable on the board", and it is deliberately the same set
 * [BoardUi.cardFor] resolves a detail view from — if the board cannot draw it, the player cannot tap it,
 * and the affordance has to live somewhere else.
 */
private fun GameState.drawnObjectIds(): Set<String> =
    buildSet {
        hand.forEach { add(it.id) }
        players.forEach { seat -> seat.battlefield.forEach { add(it.card.id) } }
        stack.forEach { add(it.id) }
    }

/**
 * What to call [id] — a **player**, or a card in any zone the snapshot carries identities for.
 *
 * Returns null when nothing in the snapshot names it. A graveyard is a *count* upstream (0052), so a
 * card there genuinely has no name to show; see [UNNAMED_CANDIDATE_LABEL] for why that still gets a
 * control rather than nothing.
 */
private fun GameState.nameFor(id: String): String? {
    players.firstOrNull { it.playerId == id }?.let { seat ->
        return if (seat.isViewer) seat.name + YOU_SUFFIX else seat.name
    }
    hand.firstOrNull { it.id == id }?.let { return it.name }
    val onBattlefield = players.flatMap { it.battlefield }
    onBattlefield.firstOrNull { it.card.id == id }?.let { return it.card.name }
    stack.firstOrNull { it.id == id }?.let { return it.name }
    val inSideZones = (exile + revealed).flatMap { it.cards }
    return inSideZones.firstOrNull { it.id == id }?.name
}

/**
 * Promote every candidate the board **cannot draw** into a labelled button in the panel.
 *
 * **Why this exists** (found on a real device, and blocking): a candidate id does not have to name
 * anything the board draws. The first prompt of every game — *"Select a starting player"*, asked before
 * priority exists — carries two **player** ids and an empty `cards` list. Players are not cards, so a
 * projection that only lit up matching cards produced a prompt with no candidate, no *done* (nothing
 * picked) and no *cancel* (`isRequired` is true): zero affordances, on the path that runs before any
 * other. The same shape reaches us for any spell aimed at a face, and for `canPlayObjects` naming a card
 * in a zone the board does not render (flashback from a graveyard is the everyday case).
 *
 * The presentation is the one §16.2 already gives to prompts *answered from their own content* — a
 * button in the floating panel — so nothing new is invented and nothing becomes modal. What the board
 * **can** draw stays a board tap (§5.2); only what it cannot is promoted.
 */
private fun offBoardCandidateButtons(
    state: GameState,
    candidateIds: Collection<String>,
    action: (String) -> BoardAction,
): List<ControlButton> {
    val drawn = state.drawnObjectIds()
    var unnamed = 0
    return candidateIds.filterNot { it in drawn }.map { id ->
        val label = state.nameFor(id) ?: "$UNNAMED_CANDIDATE_LABEL ${++unnamed}"
        ControlButton(label = label, action = action(id))
    }
}

/** The mana types actually floating in [pool], with their counts — never the empty ones. */
private fun floatingTypes(pool: magefree.network.game.ManaPool): List<Pair<ManaType, Int>> =
    buildList {
        if (pool.white > 0) add(ManaType.White to pool.white)
        if (pool.blue > 0) add(ManaType.Blue to pool.blue)
        if (pool.black > 0) add(ManaType.Black to pool.black)
        if (pool.red > 0) add(ManaType.Red to pool.red)
        if (pool.green > 0) add(ManaType.Green to pool.green)
        if (pool.colorless > 0) add(ManaType.Colorless to pool.colorless)
    }

private fun ManaType.label(): String =
    when (this) {
        ManaType.White -> "White"
        ManaType.Blue -> "Blue"
        ManaType.Black -> "Black"
        ManaType.Red -> "Red"
        ManaType.Green -> "Green"
        ManaType.Colorless -> "Colourless"
        ManaType.Generic -> "Generic"
    }

private fun GameCard.toCandidate(chosen: Set<String>): CandidateCardUi =
    CandidateCardUi(objectId = id, card = toCardUi(), isChosen = id in chosen)

/**
 * The ceiling the X control counts up to.
 *
 * Upstream sends no maximum with `GAME_PLAY_XMANA` — the real limit is how much mana the player can
 * produce, which only the server knows — so this is a **UI convenience bound on the stepper**, not a
 * rule. Announcing more than can be paid is answered by the server, which then asks for the mana and
 * lets the player cancel (§6.4a).
 */
private const val X_ANNOUNCE_CEILING = 20
