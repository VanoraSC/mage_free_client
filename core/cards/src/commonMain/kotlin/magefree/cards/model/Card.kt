package magefree.cards.model

/** Stable identity of an oracle card within the bundled catalog (the SQLite `card.id`). */
@JvmInline
value class CardId(
    val value: Long,
)

/**
 * A single printing of a card: which set it appeared in, its collector number within that set, and
 * the rarity it was printed at. These are the per-printing legality inputs (`setCode` + `rarity`)
 * that a deck validator consumes, and the identity the art pipeline resolves artwork by.
 */
data class CardPrinting(
    val setCode: String,
    val collectorNumber: String,
    val rarity: Rarity,
)

/**
 * An oracle card and all of its printings. Oracle-level fields (name, mana, colors, types, rules,
 * P/T/loyalty, face metadata) are shared across printings; per-printing set/number/rarity live in
 * [printings]. Power/toughness/loyalty/defense are the raw XMage values (a non-creature may still
 * carry `"0"`); the display layer decides what to show based on the type line.
 */
data class Card(
    val id: CardId,
    val name: String,
    val manaCost: ManaCost,
    val manaValue: Int,
    val colors: Set<CardColor>,
    val typeLine: TypeLine,
    val rules: List<String>,
    val power: String? = null,
    val toughness: String? = null,
    val loyalty: String? = null,
    val defense: String? = null,
    val faces: CardFaces = CardFaces(),
    val extraDeckCard: Boolean = false,
    val printings: List<CardPrinting> = emptyList(),
) {
    val isColorless: Boolean get() = colors.isEmpty()

    /** Distinct set codes this card has been printed in — a legality input for deck building. */
    val setCodes: Set<String> get() = printings.mapTo(LinkedHashSet()) { it.setCode }

    /** Distinct rarities this card has been printed at — a legality input for deck building. */
    val rarities: Set<Rarity> get() = printings.mapTo(LinkedHashSet()) { it.rarity }
}
