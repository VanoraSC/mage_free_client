package magefree.cards.model

/*
 * App-schema card model for `:core:cards`. These types are deliberately independent of XMage's
 * `mage.*` classes — the generator (`tools/card-catalog-generator`) is the only boundary that touches
 * `mage.*`, and it emits the bundled SQLite this model is loaded from. Nothing here depends on the
 * network/bridge; the catalog is a bundled, read-only, fully-offline dataset.
 */

/** One of the five colors of Magic. Colorless is the empty [Set] of these. */
enum class CardColor { WHITE, BLUE, BLACK, RED, GREEN }

/**
 * A card's mana cost as the raw XMage symbol string (e.g. `"{1}{R}"`, or `""` for no cost). Split /
 * modal-double-faced / adventure cards carry both halves joined by a `{*}` separator, mirroring how
 * XMage renders them.
 */
@JvmInline
value class ManaCost(
    val symbols: String,
) {
    val isEmpty: Boolean get() = symbols.isEmpty()
}

/** Rarity of a specific printing. [UNKNOWN] guards against future XMage additions. */
enum class Rarity {
    COMMON,
    UNCOMMON,
    RARE,
    MYTHIC,
    SPECIAL,
    BONUS,
    LAND,
    UNKNOWN,
    ;

    companion object {
        fun fromRaw(raw: String?): Rarity = entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: UNKNOWN
    }
}

/**
 * The parsed type line: super types (`Legendary`, `Basic`, `Snow`, …), card types (`Creature`,
 * `Instant`, `Land`, `Planeswalker`, …) and sub types (`Human`, `Warrior`, `Aura`, …). Rendered as
 * `"Legendary Creature — Human Warrior"`.
 */
data class TypeLine(
    val superTypes: List<String>,
    val cardTypes: List<String>,
    val subTypes: List<String>,
) {
    fun hasCardType(type: String): Boolean = cardTypes.any { it.equals(type, ignoreCase = true) }

    fun hasSubType(type: String): Boolean = subTypes.any { it.equals(type, ignoreCase = true) }

    override fun toString(): String {
        val left = (superTypes + cardTypes).joinToString(" ")
        return if (subTypes.isEmpty()) left else "$left — ${subTypes.joinToString(" ")}"
    }
}

/**
 * Multi-face metadata carried straight from XMage's `CardInfo`, enough for a browser to know a card
 * is a split / flip / transforming double-faced / meld / adventure card and to name the other face.
 * (Artwork for the faces is out of scope here — story 0031.)
 */
data class CardFaces(
    val splitCard: Boolean = false,
    val splitFuse: Boolean = false,
    val splitAftermath: Boolean = false,
    /** True for the individual half rows of a split card (e.g. `Fire` / `Ice`). */
    val splitHalf: Boolean = false,
    val flipCard: Boolean = false,
    val flipCardName: String? = null,
    /** A transforming double-faced card (front face). */
    val doubleFaced: Boolean = false,
    /** A modal double-faced card. */
    val modalDoubleFaced: Boolean = false,
    val secondSideName: String? = null,
    val nightCard: Boolean = false,
    val meldCard: Boolean = false,
    val meldsToCardName: String? = null,
    val hasSpellOption: Boolean = false,
    val spellOptionCardName: String? = null,
) {
    /** True when this card has any kind of second face / half worth surfacing. */
    val isMultiFace: Boolean
        get() = splitCard || flipCard || doubleFaced || modalDoubleFaced || meldCard || hasSpellOption

    /** The name of the "other" face/half, when there is a single natural one. */
    val otherFaceName: String?
        get() = secondSideName ?: flipCardName ?: meldsToCardName ?: spellOptionCardName
}
