package magefree.feature.game.board

import magefree.network.game.GameCard
import magefree.network.game.GamePrompt
import magefree.network.game.GameState
import magefree.network.game.ManaType

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

/** One floating button: what it says, what it sends, and how prominent it is. */
data class ControlButton(
    val label: String,
    val action: BoardAction,
    val isPrimary: Boolean = false,
)

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
        is GamePrompt.Select ->
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
