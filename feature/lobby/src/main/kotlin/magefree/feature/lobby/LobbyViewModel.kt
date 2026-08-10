package magefree.feature.lobby

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import magefree.model.LobbyLoadState
import magefree.model.LobbySnapshot
import magefree.model.LobbyTable
import magefree.network.LobbyRepository
import javax.inject.Inject

/**
 * How the lobby list is ordered. Client-side over the snapshot ([LobbyRepository] returns the full
 * list); pure ordering, no filtering.
 */
enum class LobbySort(
    val label: String,
) {
    /** Most-recently created tables first. */
    NewestFirst("Newest"),

    /** Oldest tables first. */
    OldestFirst("Oldest"),

    /** Fullest tables first (most seated players). */
    MostPlayers("Most players"),

    /** Tables with the most free seats first. */
    MostOpenSeats("Open seats"),
}

/**
 * The active client-side filter + sort over the lobby snapshot. All fields default to the widest,
 * unsorted-by-recency view. [hasActiveFilters] drives the "reset" affordance and the filtered count.
 *
 * @property gameType when non-null, only tables of this game/format are shown; `null` shows all.
 * @property hidePassworded hide tables that require a password.
 * @property hideFull hide tables with no free seats.
 * @property sort the active [LobbySort] ordering.
 */
data class LobbyFilter(
    val gameType: String? = null,
    val hidePassworded: Boolean = false,
    val hideFull: Boolean = false,
    val sort: LobbySort = LobbySort.NewestFirst,
) {
    /** True when any narrowing filter is active (sort alone does not count as a filter). */
    val hasActiveFilters: Boolean
        get() = gameType != null || hidePassworded || hideFull
}

/** The distinct top-level surface the lobby screen renders, derived from the snapshot's load state. */
enum class LobbyPhase {
    /** Disconnected / never loaded — the "connect to browse" surface. */
    Disconnected,

    /** A first fetch is in progress with no data yet — the full-screen loading surface. */
    Loading,

    /** Data is present (loaded or refreshing over prior data) — the list + controls. */
    Content,

    /** A fetch failed with no data to fall back on — the error + retry surface. */
    Error,
}

/**
 * Immutable UI state for the lobby browser. The [LobbyViewModel] maps a [LobbySnapshot] + the active
 * [filter] into this shape: the already-[tables] (filtered + sorted), the raw/shown counts, a
 * room-users summary, and the derived [phase]/[isRefreshing]/[errorMessage] flags the screen renders.
 *
 * @property phase the top-level surface to show.
 * @property tables the filtered + sorted tables (what the list renders).
 * @property totalTables the raw table count in the snapshot (the "M" in "showing N of M").
 * @property roomUserCount how many users are present in the lobby room.
 * @property gameTypeNames the game/format names available to filter by (for the filter chips).
 * @property filter the active client-side [LobbyFilter].
 * @property isRefreshing a non-destructive re-fetch is in flight over the current [tables].
 * @property errorMessage a refresh failure detail to surface while keeping the current list; `null`
 *   unless a fetch failed. In [LobbyPhase.Error] it is the full-screen error message.
 */
data class LobbyUiState(
    val phase: LobbyPhase = LobbyPhase.Loading,
    val tables: List<LobbyTable> = emptyList(),
    val totalTables: Int = 0,
    val roomUserCount: Int = 0,
    val gameTypeNames: List<String> = emptyList(),
    val filter: LobbyFilter = LobbyFilter(),
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null,
) {
    /** The number of tables shown after filtering (the "N" in "showing N of M"). */
    val shownTables: Int get() = tables.size

    /** True when the snapshot loaded but the server has no open tables at all. */
    val isEmpty: Boolean get() = phase == LobbyPhase.Content && totalTables == 0

    /** True when there are tables, but the active filter hides them all. */
    val isFilteredEmpty: Boolean
        get() = phase == LobbyPhase.Content && totalTables > 0 && tables.isEmpty()

    /** A short "showing N of M" summary for the list header. */
    val countSummary: String get() = "Showing $shownTables of $totalTables"
}

/**
 * Applies [filter]'s narrowing predicates and [LobbySort] ordering to this table list. Pure and
 * client-side — unit-tested directly and reused by the ViewModel's snapshot mapping.
 */
internal fun List<LobbyTable>.applyFilter(filter: LobbyFilter): List<LobbyTable> {
    val narrowed =
        filter { table ->
            (filter.gameType == null || table.gameType == filter.gameType) &&
                (!filter.hidePassworded || !table.isPassworded) &&
                (!filter.hideFull || table.seatsFilled < table.seatsTotal)
        }
    return when (filter.sort) {
        LobbySort.NewestFirst -> narrowed.sortedByDescending { it.createdAtEpochMs }
        LobbySort.OldestFirst -> narrowed.sortedBy { it.createdAtEpochMs }
        LobbySort.MostPlayers -> narrowed.sortedByDescending { it.seatsFilled }
        LobbySort.MostOpenSeats -> narrowed.sortedByDescending { it.seatsTotal - it.seatsFilled }
    }
}

/**
 * Maps a raw [LobbySnapshot] plus the active [filter] into the immutable [LobbyUiState] the screen
 * renders. Pure (no coroutines / no side effects) so it is exhaustively unit-tested. The game-type
 * chips prefer the server's advertised formats, falling back to the distinct types present on the
 * loaded tables when the server sent none.
 */
internal fun LobbySnapshot.toLobbyUiState(filter: LobbyFilter): LobbyUiState {
    val phase =
        when (status) {
            LobbyLoadState.Idle -> LobbyPhase.Disconnected
            LobbyLoadState.Loading -> LobbyPhase.Loading
            LobbyLoadState.Refreshing, LobbyLoadState.Loaded -> LobbyPhase.Content
            // Error is non-destructive: fall back to the list when prior data survives, else the
            // full-screen error surface.
            LobbyLoadState.Error -> if (tables.isEmpty()) LobbyPhase.Error else LobbyPhase.Content
        }
    val gameTypeNames =
        gameTypes.map { it.name }.ifEmpty { tables.map { it.gameType }.distinct() }
    return LobbyUiState(
        phase = phase,
        tables = tables.applyFilter(filter),
        totalTables = tables.size,
        roomUserCount = roomUsers.size,
        gameTypeNames = gameTypeNames,
        filter = filter,
        isRefreshing = status == LobbyLoadState.Refreshing,
        errorMessage = if (status == LobbyLoadState.Error) error else null,
    )
}

/**
 * MVVM ViewModel for the lobby browser (story 0029). It owns browsing only — the table actions the
 * browser offers (host / join / watch) are hoisted out of the screen to `:feature:tables`, so nothing
 * here mutates server state. Observes story 0028's
 * [LobbyRepository] `snapshot` and combines it with the client-side [LobbyFilter] into a single
 * immutable [LobbyUiState]. Filtering/sorting live entirely here (the repository exposes the raw
 * snapshot); [refresh] drives [LobbyRepository.refresh] (manual / pull-to-refresh — there is no
 * server push and no auto-refresh loop). The lobby is meaningful only while connected, so a
 * disconnected snapshot maps to [LobbyPhase.Disconnected] rather than an error.
 */
@HiltViewModel
class LobbyViewModel
    @Inject
    constructor(
        private val lobbyRepository: LobbyRepository,
    ) : ViewModel() {
        private val filter = MutableStateFlow(LobbyFilter())

        val uiState: StateFlow<LobbyUiState> =
            combine(lobbyRepository.snapshot, filter) { snapshot, activeFilter ->
                snapshot.toLobbyUiState(activeFilter)
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = LobbyUiState(),
            )

        /** Manual / pull-to-refresh trigger. No-ops to idle when not connected (per the repository). */
        fun refresh() {
            lobbyRepository.refresh()
        }

        /** Filter to a single [gameType], or clear the format filter when `null`. */
        fun setGameTypeFilter(gameType: String?) = updateFilter { it.copy(gameType = gameType) }

        /** Toggle hiding password-protected tables. */
        fun setHidePassworded(hide: Boolean) = updateFilter { it.copy(hidePassworded = hide) }

        /** Toggle hiding tables with no free seats. */
        fun setHideFull(hide: Boolean) = updateFilter { it.copy(hideFull = hide) }

        /** Change the list ordering. */
        fun setSort(sort: LobbySort) = updateFilter { it.copy(sort = sort) }

        /** Clear every narrowing filter, keeping the current [LobbySort]. */
        fun resetFilters() = updateFilter { LobbyFilter(sort = it.sort) }

        private inline fun updateFilter(transform: (LobbyFilter) -> LobbyFilter) {
            filter.update(transform)
        }

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }
