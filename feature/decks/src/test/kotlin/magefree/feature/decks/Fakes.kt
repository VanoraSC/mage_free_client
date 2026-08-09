package magefree.feature.decks

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import magefree.cards.CardCatalog
import magefree.cards.art.CardArtCachePolicy
import magefree.cards.art.PrefetchProgress
import magefree.cards.art.PrefetchScope
import magefree.cards.art.PrefetchStatus
import magefree.cards.model.Card
import magefree.cards.model.CardColor
import magefree.cards.model.CardFaces
import magefree.cards.model.CardFilter
import magefree.cards.model.CardId
import magefree.cards.model.CardPrinting
import magefree.cards.model.CatalogCounts
import magefree.cards.model.ManaCost
import magefree.cards.model.Rarity
import magefree.cards.model.TypeLine
import magefree.decks.DeckRepository
import magefree.decks.io.DeckFileFormat
import magefree.decks.io.DeckIO
import magefree.decks.io.DeckImportResult
import magefree.decks.io.DeckShareContent
import magefree.decks.legality.DeckLegality
import magefree.decks.legality.DeckLegalityResult
import magefree.decks.legality.FormatInfo
import magefree.decks.model.Deck
import magefree.decks.model.DeckFormat
import magefree.decks.model.DeckId
import magefree.decks.model.DeckSummary
import magefree.decks.model.toDeckList
import magefree.feature.cards.ArtCacheController
import magefree.feature.decks.art.DeckArtDownloader

/**
 * In-memory [DeckRepository] for hermetic ViewModel tests — no Room, no device. Ids are deterministic
 * (`deck-1`, `deck-2`, …) and the library flow re-emits (favorites first, then most-recently updated)
 * on every mutation, mirroring the real repo's contract.
 */
class FakeDeckRepository(
    initial: List<Deck> = emptyList(),
) : DeckRepository {
    private val decks = LinkedHashMap<String, Deck>()
    private var seq = 0
    private var clock = 0L
    private val library = MutableStateFlow<List<DeckSummary>>(emptyList())

    init {
        initial.forEach { decks[it.id.value] = it }
        // Continue generated ids past any preloaded decks so duplicate/create never collide with them.
        seq = initial.size
        publish()
    }

    override fun observeLibrary(): Flow<List<DeckSummary>> = library.asStateFlow()

    override suspend fun load(id: DeckId): Deck? = decks[id.value]

    override suspend fun create(
        name: String,
        format: DeckFormat?,
    ): Deck {
        val ts = ++clock
        val deck = Deck(id = DeckId("deck-${++seq}"), name = name, format = format, createdAt = ts, updatedAt = ts)
        decks[deck.id.value] = deck
        publish()
        return deck
    }

    override suspend fun save(deck: Deck) {
        decks[deck.id.value] = deck.copy(updatedAt = ++clock)
        publish()
    }

    override suspend fun duplicate(id: DeckId): Deck? {
        val source = decks[id.value] ?: return null
        val ts = ++clock
        val copy =
            source.copy(
                id = DeckId("deck-${++seq}"),
                name = "${source.name} (copy)",
                favorite = false,
                createdAt = ts,
                updatedAt = ts,
            )
        decks[copy.id.value] = copy
        publish()
        return copy
    }

    override suspend fun rename(
        id: DeckId,
        name: String,
    ) {
        decks[id.value]?.let { decks[id.value] = it.copy(name = name, updatedAt = ++clock) }
        publish()
    }

    override suspend fun delete(id: DeckId) {
        decks.remove(id.value)
        publish()
    }

    override suspend fun setFavorite(
        id: DeckId,
        favorite: Boolean,
    ) {
        decks[id.value]?.let { decks[id.value] = it.copy(favorite = favorite, updatedAt = ++clock) }
        publish()
    }

    fun snapshot(): List<Deck> = decks.values.toList()

    private fun publish() {
        library.value =
            decks.values
                .sortedWith(compareByDescending<Deck> { it.favorite }.thenByDescending { it.updatedAt })
                .map { it.toSummary() }
    }

    private fun Deck.toSummary() =
        DeckSummary(
            id = id,
            name = name,
            author = author,
            format = format,
            favorite = favorite,
            mainCount = mainCount,
            sideboardCount = sideboardCount,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
}

/** Scriptable [DeckIO]: [import] returns [importResult]; export/share round-trip the deck's text. */
class FakeDeckIO(
    private val importResult: DeckImportResult,
) : DeckIO {
    var importCalls = 0

    override suspend fun import(
        text: String,
        format: DeckFileFormat?,
    ): DeckImportResult {
        importCalls++
        return importResult
    }

    override suspend fun export(
        deck: Deck,
        format: DeckFileFormat,
    ): String = deck.toDeckList().cards.joinToString("\n") { "${it.amount} ${it.cardName}" }

    override suspend fun share(
        deck: Deck,
        format: DeckFileFormat,
    ): DeckShareContent =
        DeckShareContent(fileName = "${deck.name}.${format.extension}", mimeType = format.mimeType, content = export(deck, format))
}

/** Scriptable [DeckLegality]: [check] returns whatever [resultFor] maps a format to. */
class FakeDeckLegality(
    private val resultFor: (DeckFormat) -> DeckLegalityResult,
) : DeckLegality {
    val checkedFormats: MutableList<DeckFormat> = mutableListOf()

    override suspend fun availableFormats(): List<FormatInfo> = DeckFormat.entries.map { FormatInfo(it.key, it.displayName) }

    override suspend fun check(
        deck: Deck,
        format: DeckFormat,
    ): DeckLegalityResult {
        checkedFormats += format
        return resultFor(format)
    }
}

/**
 * In-memory [CardCatalog] keyed by id and name for hermetic builder tests.
 *
 * [cardLookupDelayMillis] makes [card] genuinely **suspend** (virtual time under `runTest`), which is
 * what lets a test open the read-suspend-write window two rapid `addCard` taps race through.
 * [failWith] makes every read throw, for the fail-soft error-injection tests.
 */
class FakeCardCatalog(
    cards: List<Card> = emptyList(),
    private val cardLookupDelayMillis: Long = 0L,
    private val failWith: (() -> Throwable)? = null,
) : CardCatalog {
    private val byId = cards.associateBy { it.id }
    private val all = cards

    /** Names passed to [cardsByName] — proves the indexable exact-name path is the one taken. */
    val exactNameLookups: MutableList<List<String>> = mutableListOf()

    /** Queries passed to the substring [search] — must stay empty on the deck-resolution paths. */
    val searchQueries: MutableList<String> = mutableListOf()

    override suspend fun card(id: CardId): Card? {
        failWith?.let { throw it() }
        if (cardLookupDelayMillis > 0L) delay(cardLookupDelayMillis)
        return byId[id]
    }

    override suspend fun search(
        query: String,
        limit: Int,
    ): List<Card> {
        failWith?.let { throw it() }
        searchQueries += query
        val exact = all.filter { it.name.equals(query, ignoreCase = true) }
        return exact.ifEmpty { all.filter { it.name.contains(query, ignoreCase = true) } }
    }

    override suspend fun cardsByName(names: Collection<String>): Map<String, Card> {
        failWith?.let { throw it() }
        exactNameLookups += names.toList()
        return all
            .filter { card -> names.any { it.equals(card.name, ignoreCase = true) } }
            .associateBy { it.name.lowercase() }
    }

    override suspend fun filter(
        criteria: CardFilter,
        limit: Int,
    ): List<Card> {
        failWith?.let { throw it() }
        return all
    }

    override suspend fun counts(): CatalogCounts = CatalogCounts(cardCount = all.size, printingCount = all.size)
}

/** Records the deck-scoped download requests and exposes a settable progress flow. */
class FakeDeckArtDownloader : DeckArtDownloader {
    val progressState = MutableStateFlow(PrefetchProgress())
    val downloadedDecks: MutableList<Deck> = mutableListOf()
    var cancelled = false

    override val progress: StateFlow<PrefetchProgress> get() = progressState.asStateFlow()

    override fun download(deck: Deck) {
        downloadedDecks += deck
        progressState.value = PrefetchProgress(status = PrefetchStatus.RUNNING, total = deck.main.size + deck.sideboard.size)
    }

    override fun cancel() {
        cancelled = true
        progressState.value = progressState.value.copy(status = PrefetchStatus.CANCELLED)
    }
}

/** Fake [ArtCacheController] (feature:cards) for the builder's policy toggle. */
class FakeArtCacheController : ArtCacheController {
    val policyState = MutableStateFlow(CardArtCachePolicy.PERSISTENT)
    private val progressState = MutableStateFlow(PrefetchProgress())
    var startedScope: PrefetchScope? = null
    var cancelled = false

    override val policy: Flow<CardArtCachePolicy> get() = policyState
    override val prefetchProgress: StateFlow<PrefetchProgress> get() = progressState.asStateFlow()

    override suspend fun setPolicy(policy: CardArtCachePolicy) {
        policyState.value = policy
    }

    override fun startPrefetch(scope: PrefetchScope) {
        startedScope = scope
    }

    override fun cancelPrefetch() {
        cancelled = true
    }
}

/** Build a minimal oracle [Card] for tests. */
fun testCard(
    id: Long,
    name: String,
    manaValue: Int = 1,
    cardTypes: List<String> = listOf("Creature"),
    colors: Set<CardColor> = emptySet(),
    faces: CardFaces = CardFaces(),
    printings: List<CardPrinting> = listOf(CardPrinting(setCode = "TST", collectorNumber = id.toString(), rarity = Rarity.COMMON)),
): Card =
    Card(
        id = CardId(id),
        name = name,
        manaCost = ManaCost(""),
        manaValue = manaValue,
        colors = colors,
        typeLine = TypeLine(superTypes = emptyList(), cardTypes = cardTypes, subTypes = emptyList()),
        rules = emptyList(),
        faces = faces,
        printings = printings,
    )
