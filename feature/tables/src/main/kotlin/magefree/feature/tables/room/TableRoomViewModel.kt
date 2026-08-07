package magefree.feature.tables.room

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
import magefree.decks.model.Deck
import magefree.decks.model.DeckId
import magefree.decks.model.DeckSummary
import magefree.feature.tables.TableRole
import magefree.network.table.MatchStarting
import magefree.network.table.Seat
import magefree.network.table.TableClient
import magefree.network.table.TablePhase
import magefree.network.table.TableState
import javax.inject.Inject

/**
 * Immutable UI state for the table room. It wraps the [TableClient.observeTable] fold ([table]) with the
 * viewer's [role] and the small deck [library] the player may submit/update from. The room deliberately
 * stops at match-start: [isMatchStarting] is the terminal Epic-11 hand-off marker.
 *
 * @property table the latest observed [TableState] (seats / phase / one-shot match-start signal).
 * @property role the viewer's [TableRole], deciding the action set.
 * @property library the saved decks a [TableRole.Player] can submit/update (offline).
 * @property isLoading no state has folded in yet (still on the seed).
 * @property actionError a server decline / failure detail from an action, else `null`.
 * @property closed the seat was given up / the table removed — the route should pop.
 */
data class TableRoomUiState(
    val table: TableState,
    val role: TableRole,
    val library: List<DeckSummary> = emptyList(),
    val isLoading: Boolean = true,
    val actionError: String? = null,
    val closed: Boolean = false,
) {
    /** The observed seats, in server order. */
    val seats: List<Seat> get() = table.seats

    /** A short summary of the table's format/options, when the seed/pushes carried one. */
    val optionsSummary: String? get() = table.optionsSummary

    /** The one-shot game-start signal, once the server pushes it (the Epic-11 boundary). */
    val matchStarting: MatchStarting? get() = table.matchStarting

    /** True once the match is starting — the terminal "match starting…" hand-off state. */
    val isMatchStarting: Boolean
        get() = table.matchStarting != null || table.phase == TablePhase.Starting || table.phase == TablePhase.Started

    /** True when a host may start: at least one seat, every known seat readied, not already starting. */
    val canStart: Boolean
        get() = role == TableRole.Host && seats.isNotEmpty() && seats.all { it.isReady } && !isMatchStarting

    /** True when the host's start control is shown (host role, before match-start). */
    val showHostActions: Boolean get() = role == TableRole.Host && !isMatchStarting

    /** True when the player's seat controls are shown (player role, before match-start). */
    val showPlayerActions: Boolean get() = role == TableRole.Player && !isMatchStarting
}

/**
 * MVVM ViewModel for the table room (story 0038). It collects [TableClient.observeTable] seeded from the
 * create/join/watch entry, folding each pushed [TableState] into [TableRoomUiState], and hoists the
 * role-appropriate actions: host [startMatch]/[removeTable]; player [submitDeck]/[updateDeck]/[leaveTable].
 * It renders a terminal "match starting" state on [MatchStarting] (the Epic-11 hand-off) and never wires
 * gameplay. Deck loads for submit/update are offline; only the table verbs touch the network.
 */
@HiltViewModel
class TableRoomViewModel
    @Inject
    constructor(
        private val tableClient: TableClient,
        private val deckRepository: DeckRepository,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(TableRoomUiState(table = TableState(tableId = ""), role = TableRole.Spectator))

        val uiState: StateFlow<TableRoomUiState> = _uiState.asStateFlow()

        private val closeChannel = Channel<Unit>(Channel.BUFFERED)

        /** One-shot: signals the route to pop the room (a leave / remove completed). */
        val close: Flow<Unit> = closeChannel.receiveAsFlow()

        private var tableId: String = ""
        private var started = false

        /** Begin observing [tableId] from [seed] through the viewer's [role]. Idempotent. */
        fun observe(
            tableId: String,
            seed: TableState,
            role: TableRole,
        ) {
            if (started) return
            started = true
            this.tableId = tableId
            _uiState.value = TableRoomUiState(table = seed, role = role)

            // A spectator subscribes to the table as a watcher before folding its pushed state.
            if (role == TableRole.Spectator) {
                viewModelScope.launch {
                    tableClient.watchTable(tableId).onFailure { error ->
                        _uiState.value = _uiState.value.copy(actionError = error.message ?: "Couldn't watch the table.")
                    }
                }
            }

            tableClient
                .observeTable(tableId, seed)
                .onEach { state -> _uiState.value = _uiState.value.copy(table = state, isLoading = false) }
                .launchIn(viewModelScope)

            // A player may submit/update from the offline library.
            if (role == TableRole.Player) {
                deckRepository
                    .observeLibrary()
                    .onEach { decks -> _uiState.value = _uiState.value.copy(library = decks) }
                    .launchIn(viewModelScope)
            }
        }

        /** Host: start the match (gated to [TableRoomUiState.canStart]). */
        fun startMatch() {
            if (!_uiState.value.canStart) return
            runAction { tableClient.startMatch(tableId) }
        }

        /** Host: tear the table down; a success pops the room. */
        fun removeTable() {
            runAction(closeOnSuccess = true) { tableClient.removeTable(tableId) }
        }

        /** Player: leave the table (give up the seat); a success pops the room. */
        fun leaveTable() {
            runAction(closeOnSuccess = true) { tableClient.leaveTable(tableId) }
        }

        /** Player: submit (bind) the picked deck for the seat during construction. */
        fun submitDeck(id: DeckId) = submitLoadedDeck(id) { deck -> tableClient.submitDeck(tableId, deck) }

        /** Player: update the in-progress deck for the seat during construction (a save before submit). */
        fun updateDeck(id: DeckId) = submitLoadedDeck(id) { deck -> tableClient.updateDeck(tableId, deck) }

        private inline fun submitLoadedDeck(
            id: DeckId,
            crossinline block: suspend (Deck) -> Result<Unit>,
        ) {
            viewModelScope.launch {
                val deck = deckRepository.load(id) ?: return@launch
                block(deck).fold(
                    onSuccess = { _uiState.value = _uiState.value.copy(actionError = null) },
                    onFailure = { error -> _uiState.value = _uiState.value.copy(actionError = error.message ?: "Action failed.") },
                )
            }
        }

        private inline fun runAction(
            closeOnSuccess: Boolean = false,
            crossinline block: suspend () -> Result<Unit>,
        ) {
            viewModelScope.launch {
                block().fold(
                    onSuccess = { if (closeOnSuccess) closeChannel.send(Unit) },
                    onFailure = { error -> _uiState.value = _uiState.value.copy(actionError = error.message ?: "Action failed.") },
                )
            }
        }
    }
