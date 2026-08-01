package magefree.cards.model

/** How a card's colors must relate to a requested color set in [CardFilter]. */
enum class ColorMatch {
    /** The card has at least one of the requested colors. */
    ANY,

    /** The card has all of the requested colors (may have more). */
    ALL,

    /** The card's color set is exactly the requested set. */
    EXACT,
}

/**
 * A conjunctive filter over the catalog (all non-null / non-empty axes must match). Every axis maps
 * to the browse/build fields bundled on device, so filtering is fully offline. Legality is expressed
 * via the per-card inputs surfaced here — [setCode] and [rarity] match a specific printing, and
 * [legalSets] matches a card that has *any* printing in one of the given sets (exactly the
 * "is this card legal in a set-restricted format?" input XMage's `Constructed` validator uses).
 */
data class CardFilter(
    val nameQuery: String? = null,
    val colors: Set<CardColor> = emptySet(),
    val colorMatch: ColorMatch = ColorMatch.ANY,
    /** A card type token, e.g. `"Creature"`, `"Instant"`, `"Land"`. Matched case-insensitively. */
    val cardType: String? = null,
    val subType: String? = null,
    val manaValue: IntRange? = null,
    /** Restrict to cards with a printing in this exact set. */
    val setCode: String? = null,
    /** Restrict to cards with a printing at this exact rarity. */
    val rarity: Rarity? = null,
    /** Legality input: cards with a printing in any of these sets (a set-legal format's pool). */
    val legalSets: Set<String> = emptySet(),
) {
    val isEmpty: Boolean
        get() =
            nameQuery.isNullOrBlank() &&
                colors.isEmpty() &&
                cardType == null &&
                subType == null &&
                manaValue == null &&
                setCode == null &&
                rarity == null &&
                legalSets.isEmpty()
}

/** Coverage counts for the loaded catalog (used by sanity checks and diagnostics). */
data class CatalogCounts(
    val cardCount: Int,
    val printingCount: Int,
)
