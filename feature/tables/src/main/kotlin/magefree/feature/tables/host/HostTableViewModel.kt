package magefree.feature.tables.host

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import magefree.model.SkillLevel
import magefree.network.table.CreateTableOptions
import magefree.network.table.RangeOfInfluence
import magefree.network.table.SeatPlayerType
import magefree.network.table.TableClient
import magefree.network.table.TableRef
import javax.inject.Inject

/** The preset game types a host may pick, mirroring the labels XMage's create-table dialog exposes. */
val HOST_GAME_TYPES: List<String> =
    listOf("Two Player Duel", "Commander Free For All", "Free For All")

/** The preset deck/construction types a host may pick. */
val HOST_DECK_TYPES: List<String> =
    listOf(
        "Constructed - Standard",
        "Constructed - Pioneer",
        "Constructed - Modern",
        "Constructed - Legacy",
        "Constructed - Vintage",
        "Constructed - Pauper",
        "Limited",
    )

/**
 * The editable create-table form, one-to-one with the subset of 0037's [CreateTableOptions] a host tunes.
 * Defaults are sensible for a casual two-player duel; [toOptions] projects it onto the client options.
 *
 * @property name the table's display name.
 * @property gameType the game/format label (a [HOST_GAME_TYPES] entry).
 * @property deckType the deck/construction label (a [HOST_DECK_TYPES] entry).
 * @property seats the seat count including the host (>= 2).
 * @property rated whether the match is rated.
 * @property freeMulligans the number of free mulligans allowed.
 * @property winsNeeded wins to take the match (best-of-N ⇒ this is ⌈N/2⌉).
 * @property skillLevel the advertised skill level.
 * @property range the multiplayer range of influence.
 * @property matchTimeLimitSeconds the per-player priority time budget (0 = none).
 * @property matchBufferTimeSeconds the per-priority buffer time (0 = none).
 * @property spectatorsAllowed whether spectators may watch.
 * @property password the join password, or blank for an open table.
 */
data class HostTableForm(
    val name: String = "My table",
    val gameType: String = HOST_GAME_TYPES.first(),
    val deckType: String = HOST_DECK_TYPES.first(),
    val seats: Int = 2,
    val rated: Boolean = false,
    val freeMulligans: Int = 0,
    val winsNeeded: Int = 1,
    val skillLevel: SkillLevel = SkillLevel.Casual,
    val range: RangeOfInfluence = RangeOfInfluence.All,
    val matchTimeLimitSeconds: Int = 0,
    val matchBufferTimeSeconds: Int = 0,
    val spectatorsAllowed: Boolean = true,
    val password: String = "",
) {
    /** True when the form is valid to submit (non-blank name, at least two seats, at least one win). */
    val isValid: Boolean
        get() = name.isNotBlank() && seats >= 2 && winsNeeded >= 1

    /** Project the form onto 0037's app-schema [CreateTableOptions] (host seat + [seats]-1 open humans). */
    fun toOptions(): CreateTableOptions =
        CreateTableOptions(
            name = name.trim(),
            gameType = gameType,
            deckType = deckType,
            players = List(seats) { SeatPlayerType.Human },
            rated = rated,
            winsNeeded = winsNeeded,
            freeMulligans = freeMulligans,
            skillLevel = skillLevel,
            range = range,
            matchTimeLimitSeconds = matchTimeLimitSeconds,
            matchBufferTimeSeconds = matchBufferTimeSeconds,
            spectatorsAllowed = spectatorsAllowed,
            password = password.ifBlank { null },
        )
}

/**
 * Immutable UI state for the host-a-table form.
 *
 * @property form the editable [HostTableForm].
 * @property isSubmitting a `createTable` call is in flight.
 * @property errorMessage a server decline / failure detail to surface, else `null`.
 */
data class HostTableUiState(
    val form: HostTableForm = HostTableForm(),
    val isSubmitting: Boolean = false,
    val errorMessage: String? = null,
) {
    /** The create action is enabled only for a valid form that is not mid-submit. */
    val canCreate: Boolean get() = form.isValid && !isSubmitting
}

/**
 * MVVM ViewModel for hosting a table (story 0038). Holds the create-table [HostTableForm], validates it,
 * and on submit maps it onto 0037's [CreateTableOptions] and calls [TableClient.createTable]. A success
 * announces the new [TableRef] on [created] so the route can seed the table room; a decline surfaces as
 * [HostTableUiState.errorMessage]. Only the create call touches the network.
 */
@HiltViewModel
class HostTableViewModel
    @Inject
    constructor(
        private val tableClient: TableClient,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(HostTableUiState())
        val uiState: StateFlow<HostTableUiState> = _uiState.asStateFlow()

        private val createdChannel = Channel<TableRef>(Channel.BUFFERED)

        /** One-shot: the [TableRef] of a just-created table, for the route to open the room on. */
        val created: Flow<TableRef> = createdChannel.receiveAsFlow()

        private inline fun editForm(transform: (HostTableForm) -> HostTableForm) {
            _uiState.value = _uiState.value.copy(form = transform(_uiState.value.form), errorMessage = null)
        }

        fun setName(name: String) = editForm { it.copy(name = name) }

        fun setGameType(gameType: String) = editForm { it.copy(gameType = gameType) }

        fun setDeckType(deckType: String) = editForm { it.copy(deckType = deckType) }

        fun setSeats(seats: Int) = editForm { it.copy(seats = seats.coerceIn(2, MAX_SEATS)) }

        fun setRated(rated: Boolean) = editForm { it.copy(rated = rated) }

        fun setFreeMulligans(count: Int) = editForm { it.copy(freeMulligans = count.coerceIn(0, MAX_FREE_MULLIGANS)) }

        fun setWinsNeeded(wins: Int) = editForm { it.copy(winsNeeded = wins.coerceIn(1, MAX_WINS)) }

        fun setSkillLevel(level: SkillLevel) = editForm { it.copy(skillLevel = level) }

        fun setRange(range: RangeOfInfluence) = editForm { it.copy(range = range) }

        fun setSpectatorsAllowed(allowed: Boolean) = editForm { it.copy(spectatorsAllowed = allowed) }

        fun setPassword(password: String) = editForm { it.copy(password = password) }

        /** Validate then create the table; a success seeds the room, a decline surfaces its reason. */
        fun create() {
            val state = _uiState.value
            if (!state.form.isValid || state.isSubmitting) return
            _uiState.value = state.copy(isSubmitting = true, errorMessage = null)
            viewModelScope.launch {
                val result = tableClient.createTable(state.form.toOptions())
                result.fold(
                    onSuccess = { ref ->
                        _uiState.value = _uiState.value.copy(isSubmitting = false)
                        createdChannel.send(ref)
                    },
                    onFailure = { error ->
                        _uiState.value =
                            _uiState.value.copy(
                                isSubmitting = false,
                                errorMessage = error.message ?: "Couldn't create the table.",
                            )
                    },
                )
            }
        }

        private companion object {
            const val MAX_SEATS = 8
            const val MAX_FREE_MULLIGANS = 3
            const val MAX_WINS = 5
        }
    }
