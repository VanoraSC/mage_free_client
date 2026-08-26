package magefree.decks.model

/*
 * App-schema deck model for `:core:decks`. Deliberately independent of XMage's `mage.*` classes: the
 * card-catalog generator (`tools/card-catalog-generator`) is the only boundary that touches `mage.*`,
 * and it emits the bundled data this module reads. Nothing here needs the network — decks are the
 * user's on-device data (see [magefree.decks.DeckRepository]); the only networked part of the whole
 * deck experience is artwork, which lives elsewhere.
 */

/** Stable identity of a deck in the local library (a UUID string; the persistence primary key). */
@JvmInline
value class DeckId(
    val value: String,
)

/** Which pile of a deck an entry belongs to. */
enum class DeckZone { MAIN, SIDEBOARD }

/**
 * A single line in a deck: a specific printing (identified by set code + collector number, matching
 * the `CardPrinting` identity) plus how many copies. [cardName] is carried alongside so the
 * model round-trips XMage's `DeckCardInfo` losslessly (it stores the name, not a catalog id) and so a
 * deck can reference a card even if it is unknown to the bundled catalog.
 */
data class DeckEntry(
    val cardName: String,
    val setCode: String,
    val collectorNumber: String,
    val quantity: Int,
)

/**
 * A deck in the local library: app metadata (id/name/author/format/favorite/timestamps) plus the
 * ordered [main] and [sideboard] entry lists. The [main] + [sideboard] + [name] + [author] are the
 * fields that round-trip XMage's `DeckCardLists` (via [magefree.decks.model.DeckList]); the rest is
 * app-only library metadata.
 */
data class Deck(
    val id: DeckId,
    val name: String,
    val author: String? = null,
    val format: DeckFormat? = null,
    val main: List<DeckEntry> = emptyList(),
    val sideboard: List<DeckEntry> = emptyList(),
    val favorite: Boolean = false,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
) {
    /** Total card count of the main deck (sum of quantities) — a deck-size legality input. */
    val mainCount: Int get() = main.sumOf { it.quantity }

    /** Total card count of the sideboard (sum of quantities). */
    val sideboardCount: Int get() = sideboard.sumOf { it.quantity }

    fun entries(zone: DeckZone): List<DeckEntry> = if (zone == DeckZone.MAIN) main else sideboard
}
