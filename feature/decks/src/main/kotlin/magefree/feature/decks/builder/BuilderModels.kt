package magefree.feature.decks.builder

import magefree.cards.art.CardArtRequest
import magefree.cards.art.CardArtSize
import magefree.cards.model.Card
import magefree.cards.model.CardId
import magefree.cards.model.CardPrinting
import magefree.cards.model.Rarity
import magefree.decks.model.DeckEntry
import magefree.decks.model.DeckZone
import magefree.designsystem.card.CardDisplay
import magefree.feature.cards.toCardDisplay

/*
 * Pure presentation models + derivations for the deck builder. No Android, no Coil, no persistence —
 * just the mapping from 0033's [DeckEntry] (+ an optionally-resolved 0030 [Card]) into what the
 * builder list, the type grouping, and the mana-curve panel render. Kept pure so it is unit-tested
 * directly and reused by [BuilderViewModel] without a device.
 */

/**
 * One resolved line of a deck for the builder list: the 0033 entry identity (name + printing +
 * quantity) plus the design-system [display], the per-printing [artRequest] (null when the entry names
 * no resolvable printing — the tile then shows the placeholder), and the legality/curve inputs
 * ([manaValue], [primaryType]) resolved from the offline catalog. [cardId] is null when the catalog
 * does not know the card (an imported deck may reference cards outside the bundle); the row still
 * renders from its stored name so nothing is lost offline.
 */
data class DeckCardRow(
    val key: String,
    val cardId: CardId?,
    val cardName: String,
    val setCode: String,
    val collectorNumber: String,
    val quantity: Int,
    val zone: DeckZone,
    val display: CardDisplay,
    val artRequest: CardArtRequest?,
    val manaValue: Int?,
    val primaryType: String,
    val isDoubleFaced: Boolean,
) {
    val isLand: Boolean get() = primaryType.equals(TYPE_LAND, ignoreCase = true)
}

/** A single mana-value column of the curve: its [label] (0..5, then "6+") and the summed [count]. */
data class ManaCurveBucket(
    val label: String,
    val count: Int,
)

/**
 * The mana curve of a deck's main, non-land spells bucketed by mana value (0..5, then 6+). [maxCount]
 * is the tallest column, so the UI can scale bars without another pass; [total] is the spell count.
 */
data class ManaCurve(
    val buckets: List<ManaCurveBucket> = emptyList(),
) {
    val maxCount: Int get() = buckets.maxOfOrNull { it.count } ?: 0

    val total: Int get() = buckets.sumOf { it.count }
}

/** A grouping of deck rows under a card-type heading, with the total copies in that group. */
data class DeckGroup(
    val type: String,
    val rows: List<DeckCardRow>,
) {
    val count: Int get() = rows.sumOf { it.quantity }
}

const val TYPE_LAND: String = "Land"
private const val TYPE_OTHER: String = "Other"
private const val CURVE_TOP_BUCKET: Int = 6

/** The card-type buckets a deck is grouped into, in the order they are shown. */
private val TYPE_ORDER =
    listOf("Creature", "Planeswalker", "Instant", "Sorcery", "Artifact", "Enchantment", "Battle", TYPE_LAND, TYPE_OTHER)

/** Stable per-line key (name + printing) used to address a line for quantity/remove edits. */
fun DeckEntry.rowKey(): String = "$cardName|$setCode|$collectorNumber"

/**
 * Build a [DeckCardRow] from a 0033 [entry] in [zone] and its (optionally resolved) catalog [card].
 * Everything is derived offline: art from the entry's own printing, mana value / primary type / DFC
 * flag from the catalog card when known, else sensible fallbacks that keep the row renderable.
 */
fun deckCardRow(
    entry: DeckEntry,
    zone: DeckZone,
    card: Card?,
): DeckCardRow {
    val display =
        card?.toCardDisplay()
            ?: CardDisplay(name = entry.cardName, manaCost = null, typeLine = null, oracleText = null)
    return DeckCardRow(
        key = entry.rowKey(),
        cardId = card?.id,
        cardName = entry.cardName,
        setCode = entry.setCode,
        collectorNumber = entry.collectorNumber,
        quantity = entry.quantity,
        zone = zone,
        display = display,
        artRequest = entry.artRequest(),
        manaValue = card?.manaValue,
        primaryType = card.primaryType(),
        isDoubleFaced = card?.faces?.let { it.doubleFaced || it.modalDoubleFaced } ?: false,
    )
}

/** The large-size front-art request for an entry's printing, or null when it carries no printing. */
private fun DeckEntry.artRequest(): CardArtRequest? {
    if (setCode.isBlank() && collectorNumber.isBlank()) return null
    return CardArtRequest.of(
        CardPrinting(setCode = setCode, collectorNumber = collectorNumber, rarity = Rarity.UNKNOWN),
        size = CardArtSize.LARGE,
    )
}

/** The grouping bucket for a card: its first recognized card type, else "Other". */
private fun Card?.primaryType(): String {
    val types = this?.typeLine?.cardTypes ?: return TYPE_OTHER
    return TYPE_ORDER.firstOrNull { bucket -> types.any { it.equals(bucket, ignoreCase = true) } } ?: TYPE_OTHER
}

/** Group [rows] by primary card type in the canonical [TYPE_ORDER]; empty groups are omitted. */
fun groupByType(rows: List<DeckCardRow>): List<DeckGroup> =
    TYPE_ORDER.mapNotNull { type ->
        val inType = rows.filter { it.primaryType.equals(type, ignoreCase = true) }
        if (inType.isEmpty()) null else DeckGroup(type = type, rows = inType)
    }

/**
 * The mana curve over [rows]: the main-zone, non-land spells bucketed by mana value into 0..5 and a
 * final "6+" column (each column sums copies). Land and unresolved (unknown mana value) rows are
 * excluded — a curve is a spell-cost distribution.
 */
fun manaCurve(rows: List<DeckCardRow>): ManaCurve {
    val counts = IntArray(CURVE_TOP_BUCKET + 1)
    rows
        .filter { it.zone == DeckZone.MAIN && !it.isLand }
        .forEach { row ->
            val mv = row.manaValue ?: return@forEach
            counts[mv.coerceIn(0, CURVE_TOP_BUCKET)] += row.quantity
        }
    val buckets =
        counts.mapIndexed { value, count ->
            val label = if (value == CURVE_TOP_BUCKET) "$CURVE_TOP_BUCKET+" else value.toString()
            ManaCurveBucket(label = label, count = count)
        }
    return ManaCurve(buckets = buckets)
}
