package magefree.feature.tables.host

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
 * The occupant kinds a host may assign to a non-host seat: another human to wait for, or one of the
 * bundled AIs to seat immediately. [SeatPlayerType.ComputerDraftBot] is deliberately absent — it is a
 * draft-only bot, and drafts are out of this epic's scope — as is [SeatPlayerType.Unknown], which exists
 * only to absorb a server code this build does not recognise.
 */
val HOST_SEAT_TYPES: List<SeatPlayerType> =
    listOf(SeatPlayerType.Human, SeatPlayerType.ComputerMonteCarlo, SeatPlayerType.ComputerMad)

/**
 * The editable create-table form, one-to-one with the subset of 0037's [CreateTableOptions] a host tunes.
 * Defaults are sensible for a casual two-player duel; [toOptions] projects it onto the client options.
 *
 * @property name the table's display name.
 * @property gameType the game/format label (a [HOST_GAME_TYPES] entry).
 * @property deckType the deck/construction label (a [HOST_DECK_TYPES] entry).
 * @property opponents the **non-host** seats, in order, each either a human to wait for or an AI to seat
 *   (story 0041). The host always takes the first seat, so the table's seat count is this plus one.
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
    val opponents: List<SeatPlayerType> = listOf(SeatPlayerType.Human),
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
    /** The table's seat count: the host's seat plus one per configured [opponents] entry. */
    val seats: Int get() = opponents.size + 1

    /** True when the form is valid to submit (non-blank name, at least two seats, at least one win). */
    val isValid: Boolean
        get() = name.isNotBlank() && seats >= 2 && winsNeeded >= 1

    /** The full seat list the table is created with: the host's human seat, then the [opponents]. */
    val seatTypes: List<SeatPlayerType> get() = listOf(SeatPlayerType.Human) + opponents

    /** Project the form onto 0037's app-schema [CreateTableOptions] (host seat + the configured [opponents]). */
    fun toOptions(): CreateTableOptions =
        CreateTableOptions(
            name = name.trim(),
            gameType = gameType,
            deckType = deckType,
            players = seatTypes,
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
 * A host **occupies a seat**, so — exactly like the join flow — it picks and validates a deck first, all
 * of it offline: [library] comes straight from the local [DeckRepository] and [legality] from the offline
 * [DeckLegality] against the [format] resolved from the table's own deck/game type. The network is not
 * touched until [HostTableViewModel.create].
 *
 * @property form the editable [HostTableForm].
 * @property library the saved decks to choose from (empty ⇒ the "build a legal deck" path).
 * @property format the table's resolved bundled format, or `null` when unrecognised (no legality gate).
 * @property seatName the display name the host sits under.
 * @property selectedDeckId the picked deck, or `null` until one is chosen.
 * @property legality the offline legality of the picked deck for [format], or `null` when not yet
 *   checked / no known format.
 * @property isSubmitting the create→seat sequence is in flight.
 * @property errorMessage a server decline / failure detail to surface, else `null`.
 */
data class HostTableUiState(
    val form: HostTableForm = HostTableForm(),
    val library: List<DeckSummary> = emptyList(),
    val format: DeckFormat? = null,
    val seatName: String = "Player",
    val selectedDeckId: DeckId? = null,
    val legality: DeckLegalityResult? = null,
    val isSubmitting: Boolean = false,
    val errorMessage: String? = null,
) {
    /** True when the library has no decks at all — the "no deck / build one" path. */
    val hasNoDecks: Boolean get() = library.isEmpty()

    /** True when a deck is picked and it is legal for a known format (or the format is unrecognised). */
    val isSelectedLegal: Boolean
        get() = selectedDeckId != null && (format == null || legality?.isLegal == true)

    /**
     * The create action is enabled only for a valid form with a picked, legal deck, and no sequence in
     * flight. The deck gate is not cosmetic: hosting *seats* the host, and a seat without a deck is
     * exactly the half-finished table story 0041 fixes.
     */
    val canCreate: Boolean get() = form.isValid && isSelectedLegal && !isSubmitting
}

/**
 * MVVM ViewModel for hosting a table (story 0038, completed by 0041).
 *
 * **The defect it fixes.** Hosting used to stop at `createTable`: the host held no seat, submitted no
 * deck, and the AI players it configured were never occupied — so the table could never reach the
 * server's ready state and the match could never start (0039's live coverage proves a freshly created
 * table has `seatsFilled = 0`; the server does not seat its creator).
 *
 * **The sequence**, mirroring `NewTableDialog.btnOKActionPerformed` upstream:
 * 1. [TableClient.createTable] — a decline stops here, with nothing to clean up.
 * 2. For each configured **non-human** seat, in seat order, [TableClient.joinTable] carrying *that seat's*
 *    [SeatPlayerType] (upstream matches a join to a seat by player type) and no password — upstream sends
 *    `""` for an AI, since the password guard applies to a human join.
 * 3. [TableClient.joinTable] for the host itself as [SeatPlayerType.Human], carrying the chosen deck and
 *    the table's password.
 * 4. Only then is [created] announced, so the room is opened on a fully seated table.
 *
 * **Any failure after step 1 removes the table** ([TableClient.removeTable]) before surfacing the reason.
 * That is not politeness: a half-seated table left behind is an orphan in the lobby that nobody — not even
 * its host, who has navigated away — can start or remove.
 *
 * **The AI's deck.** An AI seat must submit a deck at join like any other, and this client has no deck
 * *generator*. Rather than invent an unplayable stub the server would reject, an AI seat is seated with
 * **the host's own chosen deck** — already picked, already legality-checked, and guaranteed valid for the
 * table's format. It is the least surprising option (you get a mirror match against the AI) and it is
 * honest about the capability: choosing a *different* deck per AI seat needs a per-seat deck picker, which
 * is deliberately not in this story's scope.
 *
 * The deck pick and its legality check are entirely offline ([DeckRepository] + [DeckLegality]); only the
 * create/join/remove calls touch the network.
 */
@HiltViewModel
class HostTableViewModel
    @Inject
    constructor(
        private val tableClient: TableClient,
        private val deckRepository: DeckRepository,
        private val deckLegality: DeckLegality,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(HostTableUiState(format = deckFormatFromLabel(HostTableForm().deckType)))
        val uiState: StateFlow<HostTableUiState> = _uiState.asStateFlow()

        private val createdChannel = Channel<TableRef>(Channel.BUFFERED)

        /** One-shot: the [TableRef] of a fully seated, just-created table, for the route to open the room on. */
        val created: Flow<TableRef> = createdChannel.receiveAsFlow()

        private val selectedDeck = MutableStateFlow<Deck?>(null)
        private var started = false

        /** Begin observing the offline deck library so the host can pick a deck. Idempotent. */
        fun start() {
            if (started) return
            started = true
            deckRepository
                .observeLibrary()
                .onEach { decks -> _uiState.value = _uiState.value.copy(library = decks) }
                .launchIn(viewModelScope)
        }

        private inline fun editForm(transform: (HostTableForm) -> HostTableForm) {
            _uiState.value = _uiState.value.copy(form = transform(_uiState.value.form), errorMessage = null)
        }

        fun setName(name: String) = editForm { it.copy(name = name) }

        /** Set the game type; the table's resolved format (and the picked deck's legality) follow it. */
        fun setGameType(gameType: String) {
            editForm { it.copy(gameType = gameType) }
            reresolveFormat()
        }

        /** Set the deck type; the table's resolved format (and the picked deck's legality) follow it. */
        fun setDeckType(deckType: String) {
            editForm { it.copy(deckType = deckType) }
            reresolveFormat()
        }

        /**
         * Resize the table to [seats] **total** seats (host included), keeping the seat kinds already
         * configured and filling any new seat with a human.
         */
        fun setSeats(seats: Int) =
            editForm { form ->
                val opponents = (seats.coerceIn(MIN_SEATS, MAX_SEATS)) - 1
                form.copy(
                    opponents = List(opponents) { index -> form.opponents.getOrElse(index) { SeatPlayerType.Human } },
                )
            }

        /** Set the kind of occupant for non-host seat [index] (0-based over [HostTableForm.opponents]). */
        fun setOpponentType(
            index: Int,
            type: SeatPlayerType,
        ) = editForm { form ->
            if (index !in form.opponents.indices) {
                form
            } else {
                form.copy(opponents = form.opponents.toMutableList().also { it[index] = type })
            }
        }

        fun setRated(rated: Boolean) = editForm { it.copy(rated = rated) }

        fun setFreeMulligans(count: Int) = editForm { it.copy(freeMulligans = count.coerceIn(0, MAX_FREE_MULLIGANS)) }

        fun setWinsNeeded(wins: Int) = editForm { it.copy(winsNeeded = wins.coerceIn(1, MAX_WINS)) }

        fun setSkillLevel(level: SkillLevel) = editForm { it.copy(skillLevel = level) }

        fun setRange(range: RangeOfInfluence) = editForm { it.copy(range = range) }

        fun setSpectatorsAllowed(allowed: Boolean) = editForm { it.copy(spectatorsAllowed = allowed) }

        fun setPassword(password: String) = editForm { it.copy(password = password) }

        /** Set the name the host will sit under. */
        fun setSeatName(name: String) {
            _uiState.value = _uiState.value.copy(seatName = name, errorMessage = null)
        }

        /** Pick [id]: load the full deck offline and (when the format is known) check its legality. */
        fun selectDeck(id: DeckId) {
            viewModelScope.launch {
                val deck = deckRepository.load(id)
                selectedDeck.value = deck
                _uiState.value =
                    _uiState.value.copy(
                        selectedDeckId = id,
                        legality = checkLegality(deck, _uiState.value.format),
                        errorMessage = null,
                    )
            }
        }

        /**
         * Create the table and seat it, in upstream's order: create → each configured AI seat → the host.
         * A failure at any step after the create removes the table and surfaces the reason; only a fully
         * seated table announces itself on [created].
         */
        fun create() {
            val state = _uiState.value
            val deck = selectedDeck.value
            if (!state.canCreate || deck == null) return
            _uiState.value = state.copy(isSubmitting = true, errorMessage = null)
            viewModelScope.launch {
                val form = state.form
                val ref =
                    tableClient.createTable(form.toOptions()).getOrElse { error ->
                        // Nothing was created, so there is nothing to remove.
                        fail(error, "Couldn't create the table.")
                        return@launch
                    }

                // Seat the AI players first — a table is only ready once every seat is filled, and the
                // host's own join is the last thing that can fail cheaply.
                form.opponents.forEachIndexed { index, type ->
                    if (type == SeatPlayerType.Human) return@forEachIndexed
                    val seated =
                        tableClient.joinTable(
                            tableId = ref.tableId,
                            seatName = aiSeatName(index),
                            deck = deck,
                            // Upstream sends no password for an AI seat: the guard is on the human join.
                            password = null,
                            playerType = type,
                        )
                    seated.onFailure { error ->
                        abort(ref.tableId, error, "Couldn't seat the computer player.")
                        return@launch
                    }
                }

                // Then take our own seat, with the picked deck and the table's password.
                tableClient
                    .joinTable(
                        tableId = ref.tableId,
                        seatName = state.seatName.trim().ifBlank { "Player" },
                        deck = deck,
                        password = form.password.ifBlank { null },
                        playerType = SeatPlayerType.Human,
                    ).onFailure { error ->
                        abort(ref.tableId, error, "Couldn't take your seat.")
                        return@launch
                    }

                _uiState.value = _uiState.value.copy(isSubmitting = false)
                createdChannel.send(ref)
            }
        }

        /**
         * Tear the half-seated table down, then surface the reason the sequence stopped. The removal's own
         * result is deliberately ignored: it is a best-effort cleanup, and letting it fail would replace
         * the *real* reason the host needs to see with a second, less useful one.
         */
        private suspend fun abort(
            tableId: String,
            error: Throwable,
            fallback: String,
        ) {
            tableClient.removeTable(tableId)
            fail(error, fallback)
        }

        private fun fail(
            error: Throwable,
            fallback: String,
        ) {
            _uiState.value = _uiState.value.copy(isSubmitting = false, errorMessage = error.message ?: fallback)
        }

        /** Re-resolve the table's format from the current labels and re-check the picked deck against it. */
        private fun reresolveFormat() {
            val form = _uiState.value.form
            val format = deckFormatFromLabel(form.deckType, form.gameType)
            viewModelScope.launch {
                _uiState.value =
                    _uiState.value.copy(
                        format = format,
                        legality = checkLegality(selectedDeck.value, format),
                    )
            }
        }

        private suspend fun checkLegality(
            deck: Deck?,
            format: DeckFormat?,
        ): DeckLegalityResult? = if (deck != null && format != null) deckLegality.check(deck, format) else null

        /** The name an AI takes its seat under — the seat's own slot, so two AIs are distinguishable. */
        private fun aiSeatName(index: Int): String = "Computer ${index + 1}"

        private companion object {
            const val MIN_SEATS = 2
            const val MAX_SEATS = 8
            const val MAX_FREE_MULLIGANS = 3
            const val MAX_WINS = 5
        }
    }
