package magefree.feature.game.board

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import magefree.network.game.GameClient
import magefree.network.game.GamePrompt
import magefree.network.game.GameState
import javax.inject.Inject

/**
 * The persistent context of a cast in flight — requirements §6.4's organizing principle: *"a player
 * casting a spell is performing one continuous act with several decisions inside it. The board must
 * present it that way — not as unrelated dialogs that happen to arrive in sequence."*
 *
 * **What it is and is not.** It is the app's record of *what the player began casting*, so the spell
 * does not vanish behind each new question. It is **not** a model of the game: the stack, the hand and
 * the mana are all the server's, read from the snapshot, and this never contradicts them.
 *
 * @property cardName the card the player played, taken from the board's own hand/battlefield projection
 *   at the moment they played it.
 * @property stepLabel which decision the server is asking for now, derived from the prompt kind that
 *   arrived after the cast began (never from the prompt's prose).
 * @property isCancelling the player has declined a step and the server is rewinding. The wording
 *   deliberately does **not** claim the spell is uncast: `playObject` puts it on the stack immediately
 *   (CR 601 / §16.5) and the rewind is the server's, arriving on a later snapshot.
 */
data class CastUi(
    val cardName: String,
    val stepLabel: String? = null,
    val isCancelling: Boolean = false,
)

/**
 * Immutable UI state for the playable board (stories 0055 + 0057).
 *
 * @property board the projected snapshot — see [BoardUi] for the field-by-field reachability record.
 * @property isJoining true until the join has been answered; the board itself renders throughout
 *   (requirements §1.2 — the board appears before the hand exists), so this only drives a small status
 *   line, never a gate on the board.
 * @property joinError the server's own reason when `joinGame` was declined, else null.
 * @property isHandExpanded whether the peek-and-expand hand (§3.2) is open. **View state, not game
 *   state**: opening the hand looks at cards the player already holds and sends nothing to the server.
 * @property areControlsVisible whether the floating controls are shown (§16.3). Also view state — and
 *   hiding them must never hide that the server is waiting, which is why [BoardUi.priority] is drawn by
 *   the board itself rather than by the controls, and why the collapsed toggle restates it.
 * @property controls the floating controls for the outstanding prompt, or null when the server is not
 *   waiting on the viewer. Projected by [controlsFor] from `GameState.prompt` alone.
 * @property selectedObjectId the card the player has tapped (§5.1: first tap raises it and shows its
 *   detail; a second tap or the detail's own button commits). Cleared whenever the prompt changes, so a
 *   detail view can never outlive the question it was opened against.
 * @property cast the cast in flight (§6.4), or null.
 * @property actionError the server's own reason for declining the last action, else null.
 */
data class GameBoardUiState(
    val board: BoardUi,
    val isJoining: Boolean = true,
    val joinError: String? = null,
    val isHandExpanded: Boolean = false,
    val areControlsVisible: Boolean = true,
    val controls: PromptControlsUi? = null,
    val selectedObjectId: String? = null,
    val cast: CastUi? = null,
    val actionError: String? = null,
)

/**
 * MVVM ViewModel for the playable board: it collects [GameClient.observeGame] for one game, projects each
 * snapshot through [BoardUi.from] and [controlsFor], and answers the server's prompts through
 * [act] — the **single** place in `feature/game` that calls a [GameClient] verb.
 *
 * ### The seams this story is built around
 *
 * 1. **One action seam.** Every control, every tapped card and every menu item emits a [BoardAction];
 *    [act] is the only translator from an action to a client verb. So "what can this screen send?" is
 *    answered by reading one `when`, and no Composable holds a [GameClient].
 * 2. **One pass seam.** [PassPolicy] decides *when* the app answers a priority prompt (§14.1). It is
 *    consulted on every arriving [GamePrompt.Select] and by the player's own pass, and both routes end
 *    at the same single [GameClient.passPriority] call. Auto-pass and stops replace the policy and
 *    nothing else.
 * 3. **Never batch target picks** (§17.2, verified live). [BoardAction.ChooseTarget] is sent the moment
 *    the player taps, so the server can re-prompt with an updated count and a narrowed candidate set;
 *    the player's confirmation is [BoardAction.FinishTargeting] — the final *done*, not a flush of
 *    locally-held picks.
 *
 * ### Ordering, and why a missed `GAME_INIT` is survivable
 * The push side-channel is replay-less, so [observe] starts collecting **before** it calls `joinGame` —
 * a subscription opened afterwards can miss the game's very first snapshot. That ordering is not the
 * only defence, though: since story 0054, `observeGame` issues a **targeted read** of the bridge's held
 * snapshot as the flow opens, so even a genuinely missed `GAME_INIT` is repaired by the read rather than
 * leaving a permanently blank board.
 *
 * ### Snapshot replace, never merge
 * Each emission is a whole game view (0052), so each is projected on its own. Nothing here remembers a
 * card the server has stopped sending. The two pieces of state that *do* span snapshots — [CastUi] and
 * the "have I picked a target yet" flag — are records of what **this app sent**, never of what the game
 * contains.
 */
@HiltViewModel
class GameBoardViewModel
    @Inject
    constructor(
        private val gameClient: GameClient,
        private val passPolicy: PassPolicy,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(GameBoardUiState(board = BoardUi(gameId = "")))

        val uiState: StateFlow<GameBoardUiState> = _uiState.asStateFlow()

        private var started = false
        private var gameId: String = ""

        /** The latest snapshot, so an action can be dispatched against what the server last said. */
        private var latestState: GameState? = null

        /**
         * Whether a pick has already been sent for the target sequence in flight. It decides only
         * whether the closing button reads *done* or *cancel* — the app's own record of what it sent,
         * because the server states its progress in prose ("selected 0 of 2, min 1") that is not parsed.
         *
         * Reset whenever the outstanding prompt is not a target prompt, which is every boundary between
         * one target sequence and the next in practice. **Known limit, label-only:** a modal spell with
         * two target *requirements* back to back would carry the flag into the second one, so its first
         * prompt would offer *done* where *cancel* is the truer word. Both send the same message and the
         * server decides which it was, so nothing is mis-sent — and where the server does supply
         * `chosenTargets`, that server fact is used in preference to this record.
         */
        private var hasPickedTarget = false

        /** The prompt instance the [PassPolicy] has already been asked about, so it is asked once each. */
        private var policyAskedFor: GamePrompt? = null

        /** The question the current controls answer, so a *changed* question can close a stale detail. */
        private var outstandingPrompt: GamePrompt? = null

        /**
         * Begin observing [gameId] and take our seat in it. Idempotent — a recomposition or a
         * configuration change must not open a second subscription or re-join.
         */
        fun observe(gameId: String) {
            if (started) return
            started = true
            this.gameId = gameId
            _uiState.value = GameBoardUiState(board = BoardUi(gameId = gameId))

            gameClient
                .observeGame(gameId, GameState(gameId))
                .onEach(::onSnapshot)
                .launchIn(viewModelScope)

            viewModelScope.launch {
                gameClient.joinGame(gameId).fold(
                    onSuccess = { _uiState.value = _uiState.value.copy(isJoining = false, joinError = null) },
                    onFailure = { error ->
                        _uiState.value =
                            _uiState.value.copy(
                                isJoining = false,
                                joinError = error.message ?: "Couldn't join the game.",
                            )
                    },
                )
            }
        }

        /**
         * Fold one snapshot into the UI state, then give the [PassPolicy] its chance.
         *
         * The prompt is what moves everything else: a *new* prompt ends the previous question, so the
         * tapped-card detail closes, a target sequence's pick record resets, and the cast context either
         * advances a step or ends.
         */
        private fun onSnapshot(state: GameState) {
            latestState = state
            val previous = _uiState.value
            val promptChanged = state.prompt != outstandingPrompt
            outstandingPrompt = state.prompt
            if (state.prompt !is GamePrompt.Target) hasPickedTarget = false

            _uiState.value =
                previous.copy(
                    board = BoardUi.from(state),
                    controls = controlsFor(state, hasPickedTarget = hasPickedTarget),
                    selectedObjectId = if (promptChanged) null else previous.selectedObjectId,
                    cast = previous.cast.advancedBy(state.prompt),
                )

            // The one place the app decides *when* to answer a priority prompt (§14.1). Asked once per
            // prompt instance, so a re-emission of the same question cannot pass twice.
            val prompt = state.prompt
            if (prompt is GamePrompt.Select && prompt !== policyAskedFor) {
                policyAskedFor = prompt
                if (passPolicy.decide(state) == PassDecision.PassImmediately) sendPass()
            }
        }

        /**
         * How the cast context (§6.4) follows the server's questions.
         *
         * The act ends when priority comes back (`Select`) or when nothing is outstanding — by then the
         * cast has either completed or been rewound, and either way the continuous act is over. Until
         * then the label follows the prompt **kind**, never its prose.
         */
        private fun CastUi?.advancedBy(prompt: GamePrompt?): CastUi? {
            val cast = this ?: return null
            return when (prompt) {
                null, is GamePrompt.Select -> null
                is GamePrompt.Target -> cast.copy(stepLabel = CAST_STEP_TARGETS)
                is GamePrompt.PlayMana -> cast.copy(stepLabel = CAST_STEP_MANA)
                is GamePrompt.PlayXMana -> cast.copy(stepLabel = CAST_STEP_X)
                is GamePrompt.ChooseAbility -> cast.copy(stepLabel = CAST_STEP_MODE)
                else -> cast
            }
        }

        /**
         * Perform one [BoardAction] — the **only** translator from a UI gesture to a server call.
         *
         * Every arm is one client verb, and the two that share a wire message ([BoardAction.CancelPrompt]
         * and [BoardAction.FinishTargeting]) are kept apart because they mean opposite things: one backs
         * out of the cast, the other says "I have chosen enough".
         */
        fun act(action: BoardAction) {
            val id = gameId
            _uiState.value = _uiState.value.copy(actionError = null, selectedObjectId = null)
            when (action) {
                is BoardAction.PlayObject -> {
                    // §6.4: the cast becomes visible as one act from the moment it begins. The name comes
                    // from the board projection, so it is the server's own name for the card.
                    _uiState.value = _uiState.value.copy(cast = CastUi(cardName = nameOf(action.objectId)))
                    send { it.playObject(id, action.objectId) }
                }

                BoardAction.PassPriority -> sendPass()
                BoardAction.UseSpecial -> send { it.useSpecialAction(id) }

                is BoardAction.ChooseTarget -> {
                    // Sent per pick, never batched: the server re-prompts with an updated count and a
                    // narrowed candidate set after each one (§17.2).
                    hasPickedTarget = true
                    _uiState.value = _uiState.value.copy(controls = controlsForLatest())
                    send { it.chooseTarget(id, action.targetId) }
                }

                BoardAction.FinishTargeting -> send { it.cancelPrompt(id) }

                BoardAction.CancelPrompt -> {
                    _uiState.value = _uiState.value.copy(cast = _uiState.value.cast?.copy(isCancelling = true))
                    send { it.cancelPrompt(id) }
                }

                is BoardAction.AnswerAsk -> send { it.answerAsk(id, action.yes) }
                is BoardAction.ChooseAbility -> send { it.chooseAbility(id, action.abilityId) }
                is BoardAction.ChoosePile -> send { it.choosePile(id, action.first) }
                is BoardAction.ChooseChoice -> send { it.chooseChoice(id, action.key, action.special) }
                is BoardAction.PlayManaSource -> send { it.playManaSource(id, action.sourceId) }
                is BoardAction.UnlockMana -> {
                    // Upstream takes the pool's owner explicitly; it is the viewer's own pool here.
                    val playerId = latestState?.viewerPlayerId
                    if (playerId == null) {
                        _uiState.value = _uiState.value.copy(actionError = NO_SEAT_FOR_MANA)
                    } else {
                        send { it.unlockMana(id, playerId, action.manaType) }
                    }
                }

                is BoardAction.AnnounceX -> send { it.announceX(id, action.value) }
                is BoardAction.ChooseAmount -> send { it.chooseAmount(id, action.amount) }
                is BoardAction.DistributeAmounts -> send { it.distributeAmounts(id, action.amounts) }
                BoardAction.Concede -> send { it.concede(id) }
                BoardAction.QuitMatch -> send { it.quitMatch(id) }
            }
        }

        /**
         * **The single pass seam** (§14.1). Both the player's own pass and any future auto-pass arrive
         * here, and this is the only [GameClient.passPriority] call in the feature.
         */
        private fun sendPass() {
            val id = gameId
            send { it.passPriority(id) }
        }

        /** Re-project the controls against the latest snapshot (after a local flag such as a pick). */
        private fun controlsForLatest(): PromptControlsUi? = latestState?.let { controlsFor(it, hasPickedTarget = hasPickedTarget) }

        private fun send(call: suspend (GameClient) -> Result<Unit>) {
            viewModelScope.launch {
                call(gameClient).onFailure { error ->
                    _uiState.value = _uiState.value.copy(actionError = error.message ?: ACTION_DECLINED_FALLBACK)
                }
            }
        }

        /** The server's own name for [objectId], from the board projection; a fallback if it is unknown. */
        private fun nameOf(objectId: String): String {
            val board = _uiState.value.board
            board.hand.cards.firstOrNull { it.objectId == objectId }?.let { return it.card.name }
            (board.opponentSeats + listOfNotNull(board.viewerSeat))
                .flatMap { it.battlefield }
                .firstOrNull { it.objectId == objectId }
                ?.let { return it.card.name }
            return UNNAMED_CAST
        }

        /**
         * Open or close the peek-and-expand hand (§3.2).
         *
         * View state only: it decides how much of the player's own hand is drawn over the board, and
         * sends nothing.
         */
        fun setHandExpanded(expanded: Boolean) {
            _uiState.value = _uiState.value.copy(isHandExpanded = expanded)
        }

        /**
         * Show or hide the floating controls (§16.3).
         *
         * View state only. What it must **never** hide is that the server is waiting on the player — see
         * [GameBoardUiState.areControlsVisible]; the priority banner belongs to the board, not to the
         * controls, and survives this by construction.
         */
        fun setControlsVisible(visible: Boolean) {
            _uiState.value = _uiState.value.copy(areControlsVisible = visible)
        }

        /**
         * Raise a card and show its detail (§5.1 / §11.1), or close the detail when [objectId] is null
         * or is the card already raised. Sends nothing: inspecting is not acting.
         */
        fun selectCard(objectId: String?) {
            val current = _uiState.value.selectedObjectId
            _uiState.value = _uiState.value.copy(selectedObjectId = if (objectId == current) null else objectId)
        }
    }

/** Step labels for the cast context — one per decision the server can ask for inside a cast. */
internal const val CAST_STEP_TARGETS: String = "choosing targets"

internal const val CAST_STEP_MANA: String = "paying mana"

internal const val CAST_STEP_X: String = "announcing X"

internal const val CAST_STEP_MODE: String = "choosing a mode"

/** What the cast context calls a card the board could not name (it should always be able to). */
internal const val UNNAMED_CAST: String = "a spell"

/** What an action failure says when the server declined without a reason. */
internal const val ACTION_DECLINED_FALLBACK: String = "the server declined that action"

/** Why mana cannot be spent from a pool we have no seat for (a spectator, or before the first snapshot). */
internal const val NO_SEAT_FOR_MANA: String = "no seat to spend mana from"
