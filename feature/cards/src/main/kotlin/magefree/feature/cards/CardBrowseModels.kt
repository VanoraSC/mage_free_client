package magefree.feature.cards

import magefree.cards.art.CardArtRequest
import magefree.cards.art.CardArtSize
import magefree.cards.model.Card
import magefree.cards.model.CardColor
import magefree.cards.model.CardFilter
import magefree.cards.model.CardId
import magefree.cards.model.ColorMatch
import magefree.cards.model.Rarity
import magefree.designsystem.card.CardDisplay

/*
 * The presentation models that sit between the catalog [Card] and the design system's card-forward
 * components. Pure/mapping logic only — no Android, no Coil, no image loading — so it is unit
 * tested directly and reused by both the search and inspection ViewModels.
 */

/**
 * One search result, mapped from a catalog [Card] into exactly what a list tile needs: the design
 * system's [CardDisplay] (name / mana / type / oracle) plus the per-printing art identity and a set
 * label. [artRequest] is `null` when the card has no printing to resolve art for — the tile then
 * renders the design-system placeholder and the always-available card text, which is also the
 * offline-art degradation path.
 */
data class CardRow(
    val id: CardId,
    val display: CardDisplay,
    val artRequest: CardArtRequest?,
    val setLabel: String,
    val isMultiFace: Boolean,
)

/** The display fields a card surface needs, mapped from the catalog [Card]. */
fun Card.toCardDisplay(): CardDisplay =
    CardDisplay(
        name = name,
        manaCost = manaCost.symbols.ifBlank { null },
        typeLine = typeLine.toString().ifBlank { null },
        oracleText = rules.joinToString("\n\n").ifBlank { null },
    )

/** A search-result row for this card, resolving its first-printing thumbnail art at [size]. */
fun Card.toCardRow(size: CardArtSize = CardArtSize.SMALL): CardRow =
    CardRow(
        id = id,
        display = toCardDisplay(),
        artRequest = CardArtRequest.frontOf(this, size),
        setLabel = printings.firstOrNull()?.setCode.orEmpty(),
        isMultiFace = faces.isMultiFace,
    )

/**
 * The five color toggles a browser exposes (colorless is the absence of any). Ordered WUBRG.
 */
val BROWSE_COLORS: List<CardColor> = listOf(CardColor.WHITE, CardColor.BLUE, CardColor.BLACK, CardColor.RED, CardColor.GREEN)

/** The card types offered as a filter (the common, catalog-backed ones). */
val BROWSE_CARD_TYPES: List<String> =
    listOf("Creature", "Instant", "Sorcery", "Artifact", "Enchantment", "Land", "Planeswalker", "Battle")

/** The rarities offered as a filter. */
val BROWSE_RARITIES: List<Rarity> = listOf(Rarity.COMMON, Rarity.UNCOMMON, Rarity.RARE, Rarity.MYTHIC)

/** The highest discrete mana-value bucket; the top chip means "this value or more". */
const val MANA_VALUE_MAX_BUCKET: Int = 6

/**
 * The active client-side browse filters, a presentation-facing mirror of the [CardFilter] so the UI
 * never speaks the catalog's shape directly. [manaValue] is a single bucket (the top bucket, 6, means
 * "6 or more"); every axis is optional and conjunctive.
 *
 * @property legalSets a set-restricted format's pool — the catalog's own legality input. Wired through
 *   for deck building's deck builder (which owns format→sets definitions); the browse UI here drives the
 *   simpler [setCode] axis.
 */
data class CardBrowseFilters(
    val colors: Set<CardColor> = emptySet(),
    val cardType: String? = null,
    val manaValue: Int? = null,
    val setCode: String? = null,
    val rarity: Rarity? = null,
    val legalSets: Set<String> = emptySet(),
) {
    /** True when any narrowing axis is active. */
    val isActive: Boolean
        get() =
            colors.isNotEmpty() ||
                cardType != null ||
                manaValue != null ||
                !setCode.isNullOrBlank() ||
                rarity != null ||
                legalSets.isNotEmpty()

    /** The [manaValue] bucket as the catalog's [IntRange] (top bucket is open-ended), or `null`. */
    private fun manaValueRange(): IntRange? =
        when (val mv = manaValue) {
            null -> null
            MANA_VALUE_MAX_BUCKET -> MANA_VALUE_MAX_BUCKET..Int.MAX_VALUE
            else -> mv..mv
        }

    /** Fold these filters (plus the current name [query]) into the conjunctive [CardFilter]. */
    fun toCardFilter(query: String): CardFilter =
        CardFilter(
            nameQuery = query.ifBlank { null },
            colors = colors,
            colorMatch = ColorMatch.ANY,
            cardType = cardType,
            manaValue = manaValueRange(),
            setCode = setCode?.trim()?.ifBlank { null },
            rarity = rarity,
            legalSets = legalSets,
        )
}
