package magefree.decks.io

/**
 * The payload for sharing/exporting a deck as a file: the serialized [content], a suggested
 * [fileName] (deck name sanitized + the format extension), and the [mimeType]. This is *only* the
 * content + metadata — the platform share intent is wired separately.
 */
data class DeckShareContent(
    val fileName: String,
    val mimeType: String,
    val content: String,
)
