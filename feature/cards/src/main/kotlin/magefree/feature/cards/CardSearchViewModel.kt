package magefree.feature.cards

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import magefree.cards.CardCatalog
import magefree.cards.model.Card
import magefree.cards.model.CardColor
import magefree.cards.model.Rarity

/** The distinct surface the search screen renders, derived from the query/filters and results. */
enum class CardSearchPhase {
    /** No query and no active filters — the initial "search for cards" prompt. */
    Idle,

    /** A debounced query/filter is being resolved against the catalog. */
    Loading,

    /** At least one card matched — the results grid. */
    Results,

    /** A non-blank query / active filter matched nothing — the "no matches" empty state. */
    Empty,

    /**
     * The catalog read failed. The catalog is a bundled read-only asset, so a failure here is
     * environmental (typically no room to lay down the ~14 MB copy on first use) rather than a bad
     * query — the surface is an error with retry, and the pipeline stays alive for the next query.
     */
    Error,
}

/**
 * Immutable UI state for the card search/browse screen. The [CardSearchViewModel] runs the *debounced*
 * query + active [filters] through 0030's [CardCatalog] into this shape: the mapped [results], the
 * derived [phase], and the "showing N" [countSummary].
 *
 * [query] is the exception, and deliberately so: it is the text the player has typed **right now**,
 * published synchronously by [CardSearchViewModel.onQueryChange], not the debounced text the current
 * [results] were resolved for. It used to be the debounced text, and a field controlled on it could
 * never accumulate a character (story 0049); nothing may publish state about the search box that is up
 * to a debounce window out of date.
 */
data class CardSearchUiState(
    val query: String = "",
    val filters: CardBrowseFilters = CardBrowseFilters(),
    val results: List<CardRow> = emptyList(),
    val phase: CardSearchPhase = CardSearchPhase.Idle,
    /** Set only in [CardSearchPhase.Error]; the human-readable reason the catalog read failed. */
    val errorMessage: String? = null,
) {
    /** The number of results shown (the "N" in "showing N"). */
    val shownCount: Int get() = results.size

    /** A short "showing N" summary for the results header. */
    val countSummary: String get() = "Showing $shownCount"

    /** True when any narrowing filter is active (drives the reset affordance). */
    val hasActiveFilters: Boolean get() = filters.isActive
}

/**
 * MVVM ViewModel for the read-only card search/browse screen (story 0032). It owns a [query] and a
 * [CardBrowseFilters] flow, **debounces the catalog query** — not the text the field shows (story
 * 0049) — so typing does not fire a catalog query per keystroke, and runs the search on 0030's
 * fully-offline [CardCatalog]:
 * - blank query + no filters -> [CardSearchPhase.Idle] (no query issued);
 * - a name query with no filters -> the catalog's ranked [CardCatalog.search];
 * - any active filter -> the conjunctive [CardCatalog.filter] (name folded in).
 *
 * [flatMapLatest] means a newer query/filter cancels the in-flight one, so only the latest results
 * ever land. Everything is offline; art is resolved per-row as a [magefree.cards.art.CardArtRequest]
 * (null when a card has no printing -> placeholder). Read-only: no deck-add here (Epic 9).
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class CardSearchViewModel
    constructor(
        private val catalog: CardCatalog,
    ) : ViewModel() {
        /**
         * Search-text debounce window. A property (not an injected constructor arg, which Hilt cannot
         * provide for a primitive) so tests can shorten/zero it via the internal secondary constructor.
         */
        private var debounceMillis: Long = DEFAULT_DEBOUNCE_MILLIS

        /** Test-only constructor that overrides the debounce window; the flow reads it at emission time. */
        internal constructor(catalog: CardCatalog, debounceMillis: Long) : this(catalog) {
            this.debounceMillis = debounceMillis
        }

        private val query = MutableStateFlow("")
        private val filters = MutableStateFlow(CardBrowseFilters())

        /** Bumped by [retry]; re-fires the current request without the user having to retype it. */
        private val retries = MutableStateFlow(0)

        private val _uiState = MutableStateFlow(CardSearchUiState())
        val uiState: StateFlow<CardSearchUiState> = _uiState.asStateFlow()

        init {
            // Debounce only the query text (filter taps apply immediately); a blank query resets with
            // no delay so clearing the field is instant.
            val debouncedQuery =
                query
                    .debounce { text -> if (text.isBlank()) 0L else debounceMillis }
                    .distinctUntilChanged()

            combine(debouncedQuery, filters, retries) { text, activeFilters, attempt ->
                Request(text, activeFilters, attempt)
            }.flatMapLatest { request ->
                if (request.isIdle) {
                    flowOf(CardSearchUiState(query = request.query, filters = request.filters, phase = CardSearchPhase.Idle))
                } else {
                    // Emit Loading first (keeping the prior results under it), then the resolved page.
                    // The catch is *inside* flatMapLatest on purpose: a catalog failure ends this one
                    // request as an error state and leaves the outer pipeline running, so the next
                    // keystroke, filter tap, or retry is still served. Without it the throw escapes
                    // through launchIn to viewModelScope, whose SupervisorJob has no
                    // CoroutineExceptionHandler — the default handler crashes the process and the
                    // combine/flatMapLatest pipeline is gone for good (story 0042, defect B).
                    flow {
                        emit(loadingState(request))
                        emit(resolve(request))
                    }.catch { error -> emit(errorState(request, error)) }
                }
            }.onEach { state ->
                // `state.query` is the *debounced* text this result was resolved for; the field must
                // keep showing whatever the player has typed since, so the live raw query wins here.
                // (Story 0049: publishing the debounced text as `uiState.query` is what reverted every
                // keystroke.)
                _uiState.value = state.copy(query = query.value)
            }.launchIn(viewModelScope)
        }

        /** Re-run the current query/filters after a catalog failure. */
        fun retry() {
            retries.update { it + 1 }
        }

        /**
         * Update the search text; the debounced pipeline re-queries behind it.
         *
         * **Reachability (verification standard 2).** The glyph a keystroke draws is produced by
         * [CardSearchField] itself, synchronously — this ViewModel is not in that path at all, and a
         * field controlled on state that only arrives after the debounce is exactly what shipped dead
         * (story 0049). What is published *here*, synchronously on the caller's thread, is
         * [CardSearchUiState.query]: so the state describing the search box always matches what is on
         * screen instead of trailing it by a debounce window. The debounce below still governs the one
         * thing it was ever meant to: catalog queries.
         */
        fun onQueryChange(text: String) {
            _uiState.update { it.copy(query = text) }
            query.value = text
        }

        /** Clear the search text (instant reset, no debounce delay). */
        fun clearQuery() {
            _uiState.update { it.copy(query = "") }
            query.value = ""
        }

        fun toggleColor(color: CardColor) =
            updateFilters { current ->
                current.copy(colors = if (color in current.colors) current.colors - color else current.colors + color)
            }

        fun setCardType(type: String?) = updateFilters { it.copy(cardType = if (it.cardType == type) null else type) }

        fun setManaValue(bucket: Int?) = updateFilters { it.copy(manaValue = if (it.manaValue == bucket) null else bucket) }

        fun setRarity(rarity: Rarity?) = updateFilters { it.copy(rarity = if (it.rarity == rarity) null else rarity) }

        fun setSetCode(setCode: String?) = updateFilters { it.copy(setCode = setCode?.ifBlank { null }) }

        /** Clear every narrowing filter (the query is kept). */
        fun resetFilters() = updateFilters { CardBrowseFilters() }

        private inline fun updateFilters(transform: (CardBrowseFilters) -> CardBrowseFilters) {
            filters.update(transform)
        }

        private fun loadingState(request: Request): CardSearchUiState =
            _uiState.value.copy(
                query = request.query,
                filters = request.filters,
                phase = CardSearchPhase.Loading,
                errorMessage = null,
            )

        private fun errorState(
            request: Request,
            error: Throwable,
        ): CardSearchUiState =
            CardSearchUiState(
                query = request.query,
                filters = request.filters,
                results = emptyList(),
                phase = CardSearchPhase.Error,
                errorMessage = error.message?.takeIf { it.isNotBlank() }?.let { "$CATALOG_ERROR: $it" } ?: CATALOG_ERROR,
            )

        private suspend fun resolve(request: Request): CardSearchUiState {
            val cards: List<Card> =
                if (!request.filters.isActive) {
                    catalog.search(request.query)
                } else {
                    catalog.filter(request.filters.toCardFilter(request.query))
                }
            val rows = cards.map(Card::toCardRow)
            return CardSearchUiState(
                query = request.query,
                filters = request.filters,
                results = rows,
                phase = if (rows.isEmpty()) CardSearchPhase.Empty else CardSearchPhase.Results,
            )
        }

        private data class Request(
            val query: String,
            val filters: CardBrowseFilters,
            /** Retry counter; only there so an identical query/filter pair re-fires on [retry]. */
            val attempt: Int = 0,
        ) {
            /** Nothing to search: blank query and no active filter. */
            val isIdle: Boolean get() = query.isBlank() && !filters.isActive
        }

        private companion object {
            const val DEFAULT_DEBOUNCE_MILLIS = 300L
            const val CATALOG_ERROR = "Couldn't read the card catalog"
        }
    }
