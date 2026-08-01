package magefree.decks.legality

import kotlinx.serialization.Serializable

/*
 * Parse types for the bundled `assets/formats.json` legality asset (generated offline from XMage by
 * `tools/card-catalog-generator`'s FormatGenerator). Internal to `:core:decks` — the checker exposes
 * app-schema results, never these raw shapes.
 */

/** Encoding of "unlimited copies" (Integer.MAX_VALUE in XMage's maxCopiesMap) in the bundle. */
internal const val UNLIMITED_COPIES = -1

@Serializable
internal data class LegalityBundle(
    val schemaVersion: Int = 0,
    val xmageVersion: String = "",
    val xmageRef: String = "",
    val generatedAt: String = "",
    val sideboardMax: Int = 15,
    val maxCopiesDefault: Int = 4,
    /** Per-card copy limits (basic lands + deck-of-N cards); [UNLIMITED_COPIES] means no limit. */
    val maxCopiesOverrides: Map<String, Int> = emptyMap(),
    val formats: List<FormatRules> = emptyList(),
)

@Serializable
internal data class FormatRules(
    val key: String,
    val displayName: String,
    val validatorName: String = "",
    val deckMinSize: Int = 60,
    val sideboardMax: Int = 15,
    val maxCopiesDefault: Int = 4,
    /** `null` means the format imposes no rarity restriction; otherwise the allowed rarity names. */
    val allowedRarities: List<String>? = null,
    val legalSetCodes: List<String> = emptyList(),
    val banned: List<String> = emptyList(),
    val restricted: List<String> = emptyList(),
)
