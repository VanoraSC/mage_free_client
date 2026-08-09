package magefree.feature.tables

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import magefree.decks.DeckRepository
import magefree.decks.legality.DeckLegality
import magefree.decks.legality.DeckLegalityResult
import magefree.decks.legality.FormatInfo
import magefree.decks.model.Deck
import magefree.decks.model.DeckFormat
import magefree.decks.model.DeckId
import magefree.decks.model.DeckSummary

/**
 * In-memory [DeckRepository] for hermetic ViewModel tests — no Room, no device, no network. The library
 * flow re-emits (favorites first, then most-recently updated) on every mutation. Mirrors the small slice
 * of the real repo the tables ViewModels use (`observeLibrary` + `load`).
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

    override suspend fun duplicate(id: DeckId): Deck? = null

    override suspend fun rename(
        id: DeckId,
        name: String,
    ) = Unit

    override suspend fun delete(id: DeckId) = Unit

    override suspend fun setFavorite(
        id: DeckId,
        favorite: Boolean,
    ) = Unit

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

/**
 * Scriptable [DeckLegality]: [check] returns whatever [resultFor] maps a format to, and records calls.
 *
 * [failWith] makes [check] throw — the shape a bundled-catalog read failure takes on the join path. A
 * `var` so a test can fail once, then recover and prove the ViewModel still serves a later pick.
 */
class FakeDeckLegality(
    private val resultFor: (DeckFormat) -> DeckLegalityResult,
) : DeckLegality {
    val checkedFormats: MutableList<DeckFormat> = mutableListOf()

    /** When non-null, [check] throws it instead of answering. */
    var failWith: (() -> Throwable)? = null

    override suspend fun availableFormats(): List<FormatInfo> = DeckFormat.entries.map { FormatInfo(it.key, it.displayName) }

    override suspend fun check(
        deck: Deck,
        format: DeckFormat,
    ): DeckLegalityResult {
        failWith?.let { throw it() }
        checkedFormats += format
        return resultFor(format)
    }
}

/** A legal result for [format]. */
fun legal(format: DeckFormat): DeckLegalityResult = DeckLegalityResult(format = format, isLegal = true, violations = emptyList())

/** An illegal result for [format] (one violation is enough to gate the join). */
fun illegal(
    format: DeckFormat,
    violations: List<magefree.decks.legality.LegalityViolation> =
        listOf(
            magefree.decks.legality.LegalityViolation
                .DeckTooSmall(actual = 0, required = 60),
        ),
): DeckLegalityResult = DeckLegalityResult(format = format, isLegal = false, violations = violations)
