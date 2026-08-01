package magefree.feature.cards

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
import javax.inject.Inject

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
}

/**
 * Immutable UI state for the card search/browse screen. The [CardSearchViewModel] maps the debounced
 * [query] + active [filters] through 0030's [CardCatalog] into this shape: the mapped [results], the
 * derived [phase], and the "showing N" [countSummary].
 */
data class CardSearchUiState(
    val query: String = "",
    val filters: CardBrowseFilters = CardBrowseFilters(),
    val results: List<CardRow> = emptyList(),
    val phase: CardSearchPhase = CardSearchPhase.Idle,
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
 * [CardBrowseFilters] flow, **debounces** the query so typing does not fire a catalog query per
 * keystroke, and runs the search on 0030's fully-offline [CardCatalog]:
 * - blank query + no filters -> [CardSearchPhase.Idle] (no query issued);
 * - a name query with no filters -> the catalog's ranked [CardCatalog.search];
 * - any active filter -> the conjunctive [CardCatalog.filter] (name folded in).
 *
 * [flatMapLatest] means a newer query/filter cancels the in-flight one, so only the latest results
 * ever land. Everything is offline; art is resolved per-row as a [magefree.cards.art.CardArtRequest]
 * (null when a card has no printing -> placeholder). Read-only: no deck-add here (Epic 9).
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class CardSearchViewModel
    @Inject
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

        private val _uiState = MutableStateFlow(CardSearchUiState())
        val uiState: StateFlow<CardSearchUiState> = _uiState.asStateFlow()

        init {
            // Debounce only the query text (filter taps apply immediately); a blank query resets with
            // no delay so clearing the field is instant.
            val debouncedQuery =
                query
                    .debounce { text -> if (text.isBlank()) 0L else debounceMillis }
                    .distinctUntilChanged()

            combine(debouncedQuery, filters) { text, activeFilters -> Request(text, activeFilters) }
                .flatMapLatest { request ->
                    if (request.isIdle) {
                        flowOf(CardSearchUiState(query = request.query, filters = request.filters, phase = CardSearchPhase.Idle))
                    } else {
                        // Emit Loading first (keeping the prior results under it), then the resolved page.
                        flow {
                            emit(loadingState(request))
                            emit(resolve(request))
                        }
                    }
                }.onEach { state -> _uiState.value = state }
                .launchIn(viewModelScope)
        }

        /** Update the search text; the debounced pipeline re-queries. */
        fun onQueryChange(text: String) {
            query.value = text
        }

        /** Clear the search text (instant reset). */
        fun clearQuery() {
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
            _uiState.value.copy(query = request.query, filters = request.filters, phase = CardSearchPhase.Loading)

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
        ) {
            /** Nothing to search: blank query and no active filter. */
            val isIdle: Boolean get() = query.isBlank() && !filters.isActive
        }

        private companion object {
            const val DEFAULT_DEBOUNCE_MILLIS = 300L
        }
    }
