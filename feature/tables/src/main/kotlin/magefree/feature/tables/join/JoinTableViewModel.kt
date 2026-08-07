package magefree.feature.tables.join

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import magefree.decks.DeckRepository
import magefree.decks.legality.DeckLegality
import magefree.decks.legality.DeckLegalityResult
import magefree.decks.model.Deck
import magefree.decks.model.DeckFormat
import magefree.decks.model.DeckId
import magefree.decks.model.DeckSummary
import magefree.feature.tables.deckFormatFromLabel
import magefree.network.table.TableClient
import javax.inject.Inject

/** The immutable target a [JoinTableViewModel] is bound to — the lobby table the user tapped Join on. */
data class JoinTarget(
    val tableId: String,
    val tableName: String,
    val gameType: String,
    val deckType: String,
    val passworded: Boolean,
)

/**
 * Immutable UI state for the join-a-table flow. The deck [library] comes straight from the offline
 * [DeckRepository]; picking a deck loads it and runs the offline [DeckLegality] against the table's
 * resolved [format] (when one is recognised) — all before the single networked [TableClient.joinTable]
 * call at submit-time.
 *
 * @property target the table being joined.
 * @property library the saved decks to choose from (empty ⇒ the "build a legal deck" path).
 * @property format the table's resolved bundled format, or `null` when unrecognised (no legality gate).
 * @property seatName the display name to sit under.
 * @property selectedDeckId the picked deck, or `null` until one is chosen.
 * @property legality the offline legality of the picked deck for [format], or `null` when not yet
 *   checked / no known format.
 * @property password the entered join password (used only when [JoinTarget.passworded]).
 * @property isSubmitting a `joinTable` call is in flight.
 * @property errorMessage a server decline / failure detail to surface, else `null`.
 */
data class JoinTableUiState(
    val target: JoinTarget = JoinTarget(tableId = "", tableName = "", gameType = "", deckType = "", passworded = false),
    val library: List<DeckSummary> = emptyList(),
    val format: DeckFormat? = null,
    val seatName: String = "Player",
    val selectedDeckId: DeckId? = null,
    val legality: DeckLegalityResult? = null,
    val password: String = "",
    val isSubmitting: Boolean = false,
    val errorMessage: String? = null,
) {
    /** True when the library has no decks at all — the "no deck / build one" path. */
    val hasNoDecks: Boolean get() = library.isEmpty()

    /** True when a deck is picked and it is legal for a known format (or the format is unrecognised). */
    val isSelectedLegal: Boolean
        get() = selectedDeckId != null && (format == null || legality?.isLegal == true)

    /** True when a password is required by the table and one has been entered. */
    private val passwordSatisfied: Boolean get() = !target.passworded || password.isNotBlank()

    /** The join action is enabled only for a legal, deck-picked, password-satisfied, not-in-flight form. */
    val canJoin: Boolean get() = isSelectedLegal && passwordSatisfied && !isSubmitting
}

/**
 * MVVM ViewModel for joining a table (story 0038). It observes the offline deck [DeckRepository] library,
 * resolves the table's format for the offline [DeckLegality] gate, and — on a legal pick with any required
 * password — submits the chosen [Deck] via [TableClient.joinTable]. A success announces on [joined] so the
 * route can open the table room; a decline surfaces as [JoinTableUiState.errorMessage]. Deck pick + legality
 * are entirely offline; only the join call touches the network.
 */
@HiltViewModel
class JoinTableViewModel
    @Inject
    constructor(
        private val tableClient: TableClient,
        private val deckRepository: DeckRepository,
        private val deckLegality: DeckLegality,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(JoinTableUiState())

        val uiState: StateFlow<JoinTableUiState> = _uiState.asStateFlow()

        private val joinedChannel = Channel<String>(Channel.BUFFERED)

        /** One-shot: the joined table's id, for the route to open the room on. */
        val joined: Flow<String> = joinedChannel.receiveAsFlow()

        private val selectedDeck = MutableStateFlow<Deck?>(null)
        private var started = false

        /** Bind the flow to a lobby [target] and begin observing the offline deck library. Idempotent. */
        fun start(target: JoinTarget) {
            if (started) return
            started = true
            _uiState.value =
                _uiState.value.copy(
                    target = target,
                    format = deckFormatFromLabel(target.deckType, target.gameType),
                )
            deckRepository
                .observeLibrary()
                .onEach { decks -> _uiState.value = _uiState.value.copy(library = decks) }
                .launchIn(viewModelScope)
        }

        fun setSeatName(name: String) {
            _uiState.value = _uiState.value.copy(seatName = name)
        }

        fun setPassword(password: String) {
            _uiState.value = _uiState.value.copy(password = password, errorMessage = null)
        }

        /** Pick [id]: load the full deck offline and (when the format is known) check its legality. */
        fun selectDeck(id: DeckId) {
            viewModelScope.launch {
                val deck = deckRepository.load(id)
                selectedDeck.value = deck
                val format = _uiState.value.format
                val legality = if (deck != null && format != null) deckLegality.check(deck, format) else null
                _uiState.value =
                    _uiState.value.copy(
                        selectedDeckId = id,
                        legality = legality,
                        errorMessage = null,
                    )
            }
        }

        /** Submit the picked deck to the table; a success opens the room, a decline surfaces its reason. */
        fun join() {
            val state = _uiState.value
            val deck = selectedDeck.value
            if (!state.canJoin || deck == null) return
            _uiState.value = state.copy(isSubmitting = true, errorMessage = null)
            viewModelScope.launch {
                val result =
                    tableClient.joinTable(
                        tableId = state.target.tableId,
                        seatName = state.seatName.trim().ifBlank { "Player" },
                        deck = deck,
                        password = state.password.ifBlank { null },
                    )
                result.fold(
                    onSuccess = {
                        _uiState.value = _uiState.value.copy(isSubmitting = false)
                        joinedChannel.send(state.target.tableId)
                    },
                    onFailure = { error ->
                        _uiState.value =
                            _uiState.value.copy(
                                isSubmitting = false,
                                errorMessage = error.message ?: "Couldn't join the table.",
                            )
                    },
                )
            }
        }
    }
