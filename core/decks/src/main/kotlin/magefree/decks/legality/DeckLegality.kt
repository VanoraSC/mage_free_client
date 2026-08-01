package magefree.decks.legality

import magefree.decks.model.Deck
import magefree.decks.model.DeckFormat

/**
 * Offline deck-legality checker. Given a [Deck] and a [DeckFormat], it reports whether the deck is
 * legal and, if not, the structured [LegalityViolation]s the builder (story 0035) renders live. It
 * uses only the bundled format data (`assets/formats.json`) and the offline card catalog
 * (`:core:cards`) — never the network. The XMage server remains the authority at submit-time; this is
 * the local, while-you-build feedback.
 */
interface DeckLegality {
    /** The formats the bundled data covers, for a format picker. */
    suspend fun availableFormats(): List<FormatInfo>

    /** Validate [deck] against [format], returning legality + any violations. */
    suspend fun check(
        deck: Deck,
        format: DeckFormat,
    ): DeckLegalityResult
}

/** A format the bundle covers: its stable [key] and human [displayName]. */
data class FormatInfo(
    val key: String,
    val displayName: String,
)

/** Result of a legality check: [isLegal] is `true` iff [violations] is empty. */
data class DeckLegalityResult(
    val format: DeckFormat,
    val isLegal: Boolean,
    val violations: List<LegalityViolation>,
)

/**
 * A single reason a deck is illegal in a format. Each mirrors a check XMage's `Constructed.validate`
 * performs; [cardName] is set for the card-specific ones so the UI can point at the offending card.
 */
sealed interface LegalityViolation {
    /** Main deck has fewer than the format minimum ([required]) cards. */
    data class DeckTooSmall(
        val actual: Int,
        val required: Int,
    ) : LegalityViolation

    /** Sideboard exceeds the format maximum ([max]) cards. */
    data class SideboardTooLarge(
        val actual: Int,
        val max: Int,
    ) : LegalityViolation

    /** [cardName] is banned in this format. */
    data class BannedCard(
        val cardName: String,
    ) : LegalityViolation

    /** [cardName] is restricted (max 1 copy) but appears [count] times. */
    data class RestrictedOverLimit(
        val cardName: String,
        val count: Int,
    ) : LegalityViolation

    /** [cardName] appears [count] times, over the [max] copies allowed. */
    data class TooManyCopies(
        val cardName: String,
        val count: Int,
        val max: Int,
    ) : LegalityViolation

    /** No printing of [cardName] is in a set legal for this format. */
    data class IllegalSet(
        val cardName: String,
    ) : LegalityViolation

    /** No printing of [cardName] is at a rarity this format allows (e.g. Pauper commons-only). */
    data class InvalidRarity(
        val cardName: String,
    ) : LegalityViolation
}
