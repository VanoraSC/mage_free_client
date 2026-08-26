package magefree.decks.model

/**
 * The constructed formats the bundled legality data (`assets/formats.json`) covers. Each [key] matches
 * a format `key` in that bundle and is the stable string persisted with a deck; [displayName] is for
 * the UI. This enum is intentionally the closed set of *bundled* formats — adding a
 * format means regenerating the bundle (see the generator README) and adding an entry here.
 */
enum class DeckFormat(
    val key: String,
    val displayName: String,
) {
    STANDARD("standard", "Standard"),
    PIONEER("pioneer", "Pioneer"),
    MODERN("modern", "Modern"),
    LEGACY("legacy", "Legacy"),
    VINTAGE("vintage", "Vintage"),
    PAUPER("pauper", "Pauper"),
    ;

    companion object {
        /** Resolve a persisted/bundle [key] back to a [DeckFormat], or `null` if unrecognized. */
        fun fromKey(key: String?): DeckFormat? = entries.firstOrNull { it.key == key }
    }
}
