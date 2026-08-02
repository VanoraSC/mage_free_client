package magefree.decks.io

/**
 * A deck **file** format this module can import/export. Distinct from
 * [magefree.decks.model.DeckFormat], which is a constructed *play* format (Modern, Legacy, …); this
 * enum names the on-disk text grammars ported from XMage's importer/exporter family.
 *
 * Each grammar is ported from a specific upstream class (cited on the constant). [extension] is the
 * suggested file suffix and [mimeType] the share-sheet content type (story 0035 wires the actual
 * intent; here it is only metadata). Everything is plain UTF-8 text.
 */
enum class DeckFileFormat(
    val extension: String,
    val mimeType: String,
    val displayName: String,
) {
    /** XMage native `.dck` — `N [SET:NUM] Name`, `NAME:`/`AUTHOR:` headers. Ported from `DckDeckImporter`/`XmageDeckExporter`. */
    DCK("dck", "text/plain", "XMage (.dck)"),

    /** MTGO/deckstats plain text — `[SB:] N Name`, blank-line or `//sideboard` splits sideboard. Ported from `TxtDeckImporter`/`MtgOnlineDeckExporter`. */
    TEXT("txt", "text/plain", "Plain text (.txt)"),

    /** Decked Builder / Apprentice `.dec` — `[SB:] N Name`, `//` comments. Ported from `DecDeckImporter`. */
    DEC("dec", "text/plain", "Apprentice (.dec)"),

    /** MTG Arena export — `N Name (SET) Num`, blank-line/`Sideboard` split. Ported from `MtgaImporter`/`MtgArenaDeckExporter`. */
    MTGA("mtga", "text/plain", "MTG Arena (.mtga)"),
    ;

    companion object {
        /** Resolve a case-insensitive file extension (with or without a leading dot) to a format. */
        fun fromExtension(ext: String?): DeckFileFormat? {
            val normalized = ext?.trim()?.removePrefix(".")?.lowercase() ?: return null
            return entries.firstOrNull { it.extension == normalized }
        }
    }
}
