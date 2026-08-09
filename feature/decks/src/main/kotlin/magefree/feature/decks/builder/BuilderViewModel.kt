package magefree.feature.decks.builder

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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import magefree.cards.CardCatalog
import magefree.cards.art.CardArtCachePolicy
import magefree.cards.art.PrefetchProgress
import magefree.cards.art.PrefetchStatus
import magefree.cards.model.Card
import magefree.cards.model.CardId
import magefree.decks.DeckRepository
import magefree.decks.io.DeckFileFormat
import magefree.decks.io.DeckIO
import magefree.decks.io.DeckShareContent
import magefree.decks.legality.DeckLegality
import magefree.decks.legality.DeckLegalityResult
import magefree.decks.model.Deck
import magefree.decks.model.DeckEntry
import magefree.decks.model.DeckFormat
import magefree.decks.model.DeckId
import magefree.decks.model.DeckZone
import magefree.feature.cards.ArtCacheController
import magefree.feature.decks.art.DeckArtDownloader
import javax.inject.Inject

/** The distinct surface the builder renders. */
enum class BuilderPhase {
    /** The deck is being loaded from the offline library. */
    Loading,

    /** The deck loaded — the builder is editable. */
    Ready,

    /** No deck with the requested id exists. */
    NotFound,
}

/**
 * Immutable UI state for the deck builder. The deck's main pile is presented as type [groups]; the
 * [sideboard] as a flat list; [manaCurve] and [legality] are derived live from the (offline) catalog +
 * bundled legality. [policy] / [prefetch] surface the 0031 art cache toggle and the deck-scoped
 * pre-download so a player can make the deck viewable offline.
 */
data class BuilderUiState(
    val phase: BuilderPhase = BuilderPhase.Loading,
    val deckId: DeckId? = null,
    val name: String = "",
    val format: DeckFormat? = null,
    val favorite: Boolean = false,
    val groups: List<DeckGroup> = emptyList(),
    val sideboard: List<DeckCardRow> = emptyList(),
    val manaCurve: ManaCurve = ManaCurve(),
    val legality: DeckLegalityResult? = null,
    val availableFormats: List<DeckFormat> = DeckFormat.entries,
    val policy: CardArtCachePolicy = CardArtCachePolicy.DEFAULT,
    val prefetch: PrefetchProgress = PrefetchProgress(),
) {
    val mainCount: Int get() = groups.sumOf { it.count }

    val sideboardCount: Int get() = sideboard.sumOf { it.quantity }

    /** True while a deck-scoped art pre-download is running (drives the progress + cancel affordance). */
    val isPrefetching: Boolean get() = prefetch.status == PrefetchStatus.RUNNING
}

/**
 * MVVM ViewModel for the touch-first deck builder (story 0035). Every deck operation — add/remove,
 * sideboard, quantity, format choice, grouping, mana curve, live legality — is computed **offline**
 * from 0033's [DeckRepository]/[DeckLegality] and 0030's bundled [CardCatalog]; the only networked
 * action is the opt-in, deck-scoped art pre-download via [DeckArtDownloader]. Submitting/playing the
 * deck is EPIC-07 (not wired here).
 */
@HiltViewModel
class BuilderViewModel
    @Inject
    constructor(
        private val repository: DeckRepository,
        private val catalog: CardCatalog,
        private val legality: DeckLegality,
        private val deckIO: DeckIO,
        private val artDownloader: DeckArtDownloader,
        private val artCache: ArtCacheController,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(BuilderUiState())
        val uiState: StateFlow<BuilderUiState> = _uiState.asStateFlow()

        private val shareChannel = Channel<DeckShareContent>(Channel.BUFFERED)

        /** One-shot share payloads for the route to hand to the platform share sheet. */
        val shareEvents: Flow<DeckShareContent> = shareChannel.receiveAsFlow()

        /**
         * The authoritative in-memory deck; every mutation persists it and re-derives the UI.
         *
         * Only ever read or written inside [mutationLock], so a mutation that had to suspend (a catalog
         * lookup) cannot commit a transform computed against a deck another mutation has since replaced.
         */
        private var deck: Deck? = null

        /**
         * Serialises `read current deck → transform → save → re-derive`.
         *
         * The builder's mutations are not all synchronous: `addCard` must resolve the card in the
         * offline catalog first, and that suspends. Without this lock two taps inside that window both
         * read the same deck and the second write silently discards the first (story 0042, defect A).
         * Holding the lock across the whole critical section — and re-reading [deck] *inside* it, after
         * every suspension — makes each mutation atomic with respect to the others.
         */
        private val mutationLock = Mutex()

        init {
            artDownloader.progress
                .onEach { progress -> _uiState.update { it.copy(prefetch = progress) } }
                .launchIn(viewModelScope)
            artCache.policy
                .onEach { policy -> _uiState.update { it.copy(policy = policy) } }
                .launchIn(viewModelScope)
        }

        /** Load the deck with [id] from the offline library and derive the builder state. */
        fun load(id: DeckId) {
            _uiState.update { it.copy(phase = BuilderPhase.Loading, deckId = id) }
            viewModelScope.launch {
                val loaded = repository.load(id)
                mutationLock.withLock {
                    deck = loaded
                    if (loaded == null) {
                        _uiState.update { it.copy(phase = BuilderPhase.NotFound) }
                    } else {
                        rebuild(loaded)
                    }
                }
            }
        }

        /**
         * Add one copy of the catalog card [cardId] to [zone] (merging with an existing line).
         *
         * The catalog lookup suspends, so the deck is read **after** it, inside the serialised section —
         * never snapshotted before the launch.
         */
        fun addCard(
            cardId: CardId,
            zone: DeckZone,
        ) {
            viewModelScope.launch {
                val card = catalog.card(cardId) ?: return@launch
                mutate { it.withCardAdded(card, zone) }
            }
        }

        /** Change the copies of the line [key] in [zone] by [delta]; removes the line at zero. */
        fun changeQuantity(
            key: String,
            zone: DeckZone,
            delta: Int,
        ) {
            mutateAsync { it.withQuantityChanged(key, zone, delta) }
        }

        /** Remove the line [key] from [zone] entirely. */
        fun removeRow(
            key: String,
            zone: DeckZone,
        ) {
            mutateAsync { it.withRowRemoved(key, zone) }
        }

        /** Pick the play format; legality feedback recomputes for it. */
        fun setFormat(format: DeckFormat?) {
            mutateAsync { it.copy(format = format) }
        }

        /** Rename the deck from the builder. */
        fun rename(name: String) {
            val trimmed = name.trim()
            if (trimmed.isEmpty()) return
            mutateAsync { it.copy(name = trimmed) }
        }

        /** Produce a shareable file of the current deck in [format] for the platform share sheet. */
        fun shareDeck(format: DeckFileFormat = DeckFileFormat.DCK) {
            viewModelScope.launch {
                val current = mutationLock.withLock { deck } ?: return@launch
                shareChannel.send(deckIO.share(current, format))
            }
        }

        /** Start the opt-in, deck-scoped art pre-download for the current deck's printings. */
        fun downloadDeckArt() {
            val current = deck ?: return
            artDownloader.download(current)
        }

        /** Cancel an in-progress deck-scoped art pre-download. */
        fun cancelDownload() {
            artDownloader.cancel()
        }

        /** Switch the art cache policy (persistent on-disk vs session-only). */
        fun setPolicy(policy: CardArtCachePolicy) {
            viewModelScope.launch { artCache.setPolicy(policy) }
        }

        /** Apply [transform] to the current deck, persist, and re-derive — off the main flow. */
        private fun mutateAsync(transform: (Deck) -> Deck) {
            viewModelScope.launch { mutate(transform) }
        }

        /**
         * The single serialised mutation path: take the lock, read the authoritative deck, apply
         * [transform] to *that* value, publish, persist, re-derive. Concurrent mutations queue here
         * rather than interleaving, so none is computed against a deck that has already been replaced.
         */
        private suspend fun mutate(transform: (Deck) -> Deck) {
            mutationLock.withLock {
                val current = deck ?: return
                val next = transform(current)
                deck = next
                repository.save(next)
                rebuild(next)
            }
        }

        /** Resolve every line against the offline catalog and compute groups, curve, and legality. */
        private suspend fun rebuild(source: Deck) {
            val cache = HashMap<String, Card?>()
            val mainRows = source.main.map { deckCardRow(it, DeckZone.MAIN, resolve(it, cache)) }
            val sideRows = source.sideboard.map { deckCardRow(it, DeckZone.SIDEBOARD, resolve(it, cache)) }
            val legalityResult = source.format?.let { legality.check(source, it) }
            _uiState.update {
                it.copy(
                    phase = BuilderPhase.Ready,
                    deckId = source.id,
                    name = source.name,
                    format = source.format,
                    favorite = source.favorite,
                    groups = groupByType(mainRows),
                    sideboard = sideRows,
                    manaCurve = manaCurve(mainRows),
                    legality = legalityResult,
                )
            }
        }

        /** Resolve an entry's catalog card by exact name (cached within a rebuild); null if unknown. */
        private suspend fun resolve(
            entry: DeckEntry,
            cache: MutableMap<String, Card?>,
        ): Card? {
            val nameKey = entry.cardName.lowercase()
            if (cache.containsKey(nameKey)) return cache[nameKey]
            val card = catalog.search(entry.cardName).firstOrNull { it.name.equals(entry.cardName, ignoreCase = true) }
            cache[nameKey] = card
            return card
        }
    }

/** Add one copy of [card] to [zone], preferring its first printing; merges with an existing line. */
private fun Deck.withCardAdded(
    card: Card,
    zone: DeckZone,
): Deck {
    val printing = card.printings.firstOrNull()
    val entry =
        DeckEntry(
            cardName = card.name,
            setCode = printing?.setCode.orEmpty(),
            collectorNumber = printing?.collectorNumber.orEmpty(),
            quantity = 1,
        )
    val zoneEntries = entries(zone)
    val key = entry.rowKey()
    val existing = zoneEntries.firstOrNull { it.rowKey() == key }
    val updated =
        if (existing == null) {
            zoneEntries + entry
        } else {
            zoneEntries.map { if (it.rowKey() == key) it.copy(quantity = it.quantity + 1) else it }
        }
    return replaceZone(zone, updated)
}

private fun Deck.withQuantityChanged(
    key: String,
    zone: DeckZone,
    delta: Int,
): Deck {
    val updated =
        entries(zone).mapNotNull { entry ->
            if (entry.rowKey() != key) {
                entry
            } else {
                val next = entry.quantity + delta
                if (next <= 0) null else entry.copy(quantity = next)
            }
        }
    return replaceZone(zone, updated)
}

private fun Deck.withRowRemoved(
    key: String,
    zone: DeckZone,
): Deck = replaceZone(zone, entries(zone).filterNot { it.rowKey() == key })

private fun Deck.replaceZone(
    zone: DeckZone,
    entries: List<DeckEntry>,
): Deck = if (zone == DeckZone.MAIN) copy(main = entries) else copy(sideboard = entries)
