package magefree.bridge.mapping.game

import mage.choices.Choice
import mage.view.AbilityPickerView
import mage.view.GameClientMessage
import mage.view.GameView
import magefree.bridge.mapping.GameViewMapper
import magefree.protocol.AskPrompt
import magefree.protocol.ChooseAbilityPrompt
import magefree.protocol.ChooseChoicePrompt
import magefree.protocol.ChoosePilePrompt
import magefree.protocol.GameAbilityChoice
import magefree.protocol.GameChoiceOption
import magefree.protocol.GameMultiAmountEntry
import magefree.protocol.GamePrompt
import magefree.protocol.GamePromptOptions
import magefree.protocol.GamePrompted
import magefree.protocol.GameStateView
import magefree.protocol.GetAmountPrompt
import magefree.protocol.GetMultiAmountPrompt
import magefree.protocol.PlayManaPrompt
import magefree.protocol.PlayXManaPrompt
import magefree.protocol.SelectPrompt
import magefree.protocol.TargetPrompt
import java.util.UUID

/**
 * The per-callback mappers for the in-game **prompts** — "the server is waiting for you" (story 0051).
 *
 * Every prompt callback but one carries a `mage.view.GameClientMessage`, whose fields mean different
 * things per callback (`cardsView1` is a candidate set for `GAME_TARGET` but the first pile for
 * `GAME_CHOOSE_PILE`; `min`/`max` bound an amount for `GAME_GET_AMOUNT` but a *total* for
 * `GAME_GET_MULTI_AMOUNT`). Mapping each callback separately here is what turns that one overloaded
 * payload into the closed, typed [GamePrompt] set the app can actually answer — the exception being
 * `GAME_CHOOSE_ABILITY`, which upstream sends as an `AbilityPickerView` carrying its own `GameView`.
 *
 * Every mapper produces one [GamePrompted]: the state mapped **once** by [GameViewMapper], plus the
 * question. Pure and deterministic; [magefree.bridge.mapping.CallbackMapper]'s `mapGuarded` is the outer
 * never-throws backstop.
 */
public object GamePromptMapper {
    /**
     * `GAME_SELECT` → [SelectPrompt]. The viewer has priority; the answer is one of
     * `GameStateView.playable`, a pass (`sendPlayerBoolean(false)`), or the special button.
     */
    public fun select(
        gameId: UUID?,
        message: GameClientMessage,
    ): GamePrompted =
        prompted(gameId, message.gameView) {
            SelectPrompt(message = message.text(), options = message.optionsView())
        }

    /**
     * `GAME_TARGET` → [TargetPrompt]. `cardsView1` is the candidate set the server chose to show,
     * `targets` the ids it marked, and the payload's `flag` is upstream's `required`.
     *
     * `targets` is `null` (not merely empty) for two different shapes, which need two different
     * fallbacks:
     * - `PICK_ABILITY` — ordering simultaneous triggered abilities — whose `GameController.java`
     *   overload never populates it (`Mage.Server/.../GameController.java:881-885`, read directly),
     *   because for that prompt the candidate set *is* the answer set. Story 0072: falling back to
     *   `cards.map { it.id }` is correct here — there is no separate narrowing.
     * - `TargetCardInLibrary` — a fetchland's "search your library" — whose `HumanPlayer.chooseTarget`
     *   overload (read directly) sends `cardsView1` as the **entire searched zone** (the whole
     *   library, often dozens of cards) while the real, narrow answer set only ever exists in
     *   `options["possibleTargets"]` (`target.possibleTargets(...)`, computed before the event fires).
     *   Falling back to `cards.map { it.id }` here — as the `PICK_ABILITY` case does — would offer
     *   every card in the library as if it were a legal fetch, indistinguishable from the 1-4 that
     *   actually are (**found live, Pete, 2026-08-20**: a Marsh Flats activation with no way to tell
     *   which of ~30 library cards could actually be picked). `possibleTargets`, when present, is
     *   therefore preferred over the whole-`cardsView1` fallback.
     */
    public fun target(
        gameId: UUID?,
        message: GameClientMessage,
    ): GamePrompted =
        prompted(gameId, message.gameView) {
            val cards = GameViewMapper.mapCards(message.cardsView1)
            val options = message.optionsView()
            val possibleTargets = options.ids[GamePromptOptions.POSSIBLE_TARGETS]
            TargetPrompt(
                message = message.text(),
                cards = cards,
                targetIds =
                    message.targets
                        ?.filterNotNull()
                        ?.map { it.toString() }
                        ?: possibleTargets
                        ?: cards.map { it.id },
                required = message.isFlag,
                options = options,
            )
        }

    /** `GAME_ASK` → [AskPrompt]: a yes/no, answered with `sendPlayerBoolean`. */
    public fun ask(
        gameId: UUID?,
        message: GameClientMessage,
    ): GamePrompted =
        prompted(gameId, message.gameView) {
            AskPrompt(message = message.text(), options = message.optionsView())
        }

    /**
     * `GAME_CHOOSE_ABILITY` → [ChooseAbilityPrompt]. The odd one out: upstream sends an
     * `AbilityPickerView` (its own message, its own `GameView`, and an **ordered** id→rendered-text map)
     * rather than a `GameClientMessage`.
     */
    public fun chooseAbility(
        gameId: UUID?,
        picker: AbilityPickerView,
    ): GamePrompted =
        prompted(gameId, picker.gameView) {
            ChooseAbilityPrompt(
                message = picker.message.orEmpty(),
                choices =
                    picker.choices.orEmpty().entries.map { (abilityId, text) ->
                        GameAbilityChoice(abilityId = abilityId?.toString().orEmpty(), text = text.orEmpty())
                    },
            )
        }

    /** `GAME_CHOOSE_PILE` → [ChoosePilePrompt]: `cardsView1`/`cardsView2` are the two piles. */
    public fun choosePile(
        gameId: UUID?,
        message: GameClientMessage,
    ): GamePrompted =
        prompted(gameId, message.gameView) {
            ChoosePilePrompt(
                message = message.text(),
                pile1 = GameViewMapper.mapCards(message.cardsView1),
                pile2 = GameViewMapper.mapCards(message.cardsView2),
            )
        }

    /**
     * `GAME_CHOOSE_CHOICE` → [ChooseChoicePrompt]. Upstream's `Choice` has two flavours — a plain
     * `Set<String>` and a key→value map — and the reply (`sendPlayerString`) differs accordingly. Both
     * are normalised to key/label pairs here, so the app always answers with a key and never has to know
     * which flavour it received.
     */
    public fun chooseChoice(
        gameId: UUID?,
        message: GameClientMessage,
    ): GamePrompted = prompted(gameId, message.gameView) { mapChoice(message.choice, message.message) }

    /**
     * `GAME_PLAY_MANA` → [PlayManaPrompt]: tap a source (`sendPlayerUUID`), unlock a pooled type
     * (`sendPlayerManaType`), take the special option, or cancel.
     */
    public fun playMana(
        gameId: UUID?,
        message: GameClientMessage,
    ): GamePrompted =
        prompted(gameId, message.gameView) {
            PlayManaPrompt(message = message.text(), options = message.optionsView())
        }

    /** `GAME_PLAY_XMANA` → [PlayXManaPrompt]: announce X with `sendPlayerInteger`. */
    public fun playXMana(
        gameId: UUID?,
        message: GameClientMessage,
    ): GamePrompted =
        prompted(gameId, message.gameView) {
            PlayXManaPrompt(message = message.text(), options = message.optionsView())
        }

    /** `GAME_GET_AMOUNT` → [GetAmountPrompt]: `min`/`max` bound the single number asked for. */
    public fun getAmount(
        gameId: UUID?,
        message: GameClientMessage,
    ): GamePrompted =
        prompted(gameId, message.gameView) {
            GetAmountPrompt(
                message = message.text(),
                min = message.min,
                max = message.max,
                options = message.optionsView(),
            )
        }

    /**
     * `GAME_GET_MULTI_AMOUNT` → [GetMultiAmountPrompt]. Here `messages` carries one row per entry (each
     * with its own bounds) and the payload's `min`/`max` bound the **total**; the reply is the amounts
     * as a comma-separated string in entry order.
     */
    public fun getMultiAmount(
        gameId: UUID?,
        message: GameClientMessage,
    ): GamePrompted =
        prompted(gameId, message.gameView) {
            GetMultiAmountPrompt(
                message = message.text(),
                entries =
                    message.messages.orEmpty().filterNotNull().map { entry ->
                        GameMultiAmountEntry(
                            message = entry.message.orEmpty(),
                            min = entry.min,
                            max = entry.max,
                            defaultValue = entry.defaultValue,
                        )
                    },
                min = message.min,
                max = message.max,
                options = message.optionsView(),
            )
        }

    /**
     * Wraps [prompt] with the snapshot from [view], mapped **once** so the prompt can reference state by
     * id instead of duplicating it. A prompt whose payload carried no `GameView` (upstream always sends
     * one; this is the defensive arm) still reaches the app, carrying an all-defaults state rather than
     * being dropped — the question matters more than the snapshot.
     */
    private inline fun prompted(
        gameId: UUID?,
        view: GameView?,
        prompt: () -> GamePrompt,
    ): GamePrompted =
        GamePrompted(
            gameId = gameId.asGameId(),
            state = view?.let(GameViewMapper::map) ?: GameStateView(),
            prompt = prompt(),
        )

    /** Normalises a `mage.choices.Choice` (keyed or plain) to the app-schema [ChooseChoicePrompt]. */
    private fun mapChoice(
        choice: Choice?,
        fallbackMessage: String?,
    ): ChooseChoicePrompt {
        val options =
            when {
                choice == null -> emptyList()
                choice.isKeyChoice ->
                    choice.keyChoices.orEmpty().entries.map { (key, label) ->
                        GameChoiceOption(key = key.orEmpty(), label = label.orEmpty())
                    }
                // The plain flavour has no keys: upstream matches the reply against the string itself,
                // so the choice *is* its own key.
                else ->
                    choice.choices
                        .orEmpty()
                        .filterNotNull()
                        .map { GameChoiceOption(key = it, label = it) }
            }
        return ChooseChoicePrompt(
            message = choice?.message?.takeIf { it.isNotBlank() } ?: fallbackMessage.orEmpty(),
            choices = options,
            subMessage = choice?.subMessage?.takeIf { it.isNotBlank() },
            required = choice?.isRequired ?: false,
            specialText = choice?.takeIf { it.isSpecialEnabled }?.specialText?.takeIf { it.isNotBlank() },
        )
    }

    /** The prompt's own text; upstream leaves it null for the payloads that carry a `Choice` instead. */
    private fun GameClientMessage.text(): String = message.orEmpty()

    /**
     * Projects the payload's `options` (`Map<String, Serializable>`) into the two typed halves of
     * [GamePromptOptions]: a value that is a collection becomes an id list (upstream uses those for
     * `possibleAttackers`/`possibleBlockers`/`chosenTargets`/`possibleTargets`), anything else becomes
     * text. Unknown keys are carried through rather than dropped — the server adds hints faster than we
     * render them, and a dropped hint is invisible where a carried one is merely unused.
     */
    private fun GameClientMessage.optionsView(): GamePromptOptions {
        val raw = options ?: return GamePromptOptions()
        val textOptions = LinkedHashMap<String, String>()
        val idOptions = LinkedHashMap<String, List<String>>()
        for ((key, value) in raw) {
            when {
                value is Collection<*> -> idOptions[key] = value.filterNotNull().map(Any::toString)
                value != null -> textOptions[key] = value.toString()
            }
        }
        return GamePromptOptions(text = textOptions, ids = idOptions)
    }
}
