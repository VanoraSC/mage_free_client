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
import magefree.network.game.GameState
import javax.inject.Inject

/**
 * Immutable UI state for the read-only board (story 0055).
 *
 * @property board the projected snapshot — see [BoardUi] for the field-by-field reachability record.
 * @property isJoining true until the join has been answered; the board itself renders throughout
 *   (requirements §1.2 — the board appears before the hand exists), so this only drives a small status
 *   line, never a gate on the board.
 * @property joinError the server's own reason when `joinGame` was declined, else null.
 * @property isHandExpanded whether the peek-and-expand hand (§3.2) is open. **View state, not game
 *   state**: opening the hand looks at cards the player already holds and sends nothing to the server.
 */
data class GameBoardUiState(
    val board: BoardUi,
    val isJoining: Boolean = true,
    val joinError: String? = null,
    val isHandExpanded: Boolean = false,
)

/**
 * MVVM ViewModel for the read-only board: it collects [GameClient.observeGame] for one game and projects
 * each snapshot through [BoardUi.from]. **It has no game verb but [GameClient.joinGame]** — deliberately.
 * Every other verb on the client is an action, and this story renders a game rather than plays one
 * (story 0056 owns acting), so there is nothing here that could answer a prompt, play a card or pass
 * priority even by accident.
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
 * card the server has stopped sending.
 */
@HiltViewModel
class GameBoardViewModel
    @Inject
    constructor(
        private val gameClient: GameClient,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(GameBoardUiState(board = BoardUi(gameId = "")))

        val uiState: StateFlow<GameBoardUiState> = _uiState.asStateFlow()

        private var started = false

        /**
         * Begin observing [gameId] and take our seat in it. Idempotent — a recomposition or a
         * configuration change must not open a second subscription or re-join.
         */
        fun observe(gameId: String) {
            if (started) return
            started = true
            _uiState.value = GameBoardUiState(board = BoardUi(gameId = gameId))

            gameClient
                .observeGame(gameId, GameState(gameId))
                .onEach { state -> _uiState.value = _uiState.value.copy(board = BoardUi.from(state)) }
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
         * Open or close the peek-and-expand hand (§3.2).
         *
         * This is the board's **only** state-changing entry point, and it changes nothing about the
         * game: it decides how much of the player's own hand is drawn over the board. No server call.
         */
        fun setHandExpanded(expanded: Boolean) {
            _uiState.value = _uiState.value.copy(isHandExpanded = expanded)
        }
    }
