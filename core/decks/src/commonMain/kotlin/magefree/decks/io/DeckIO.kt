package magefree.decks.io

import magefree.decks.model.Deck

/**
 * The pure, fully-offline import/export surface for decks. Imports parse XMage-compatible text into a
 * 0033 [Deck] (resolving names → printings via the `:core:cards` catalog); exports serialize a [Deck]
 * back to text. All work runs on an injected IO dispatcher — nothing here touches the network.
 *
 * See [magefree.decks.io.internal.DefaultDeckIO] for the implementation; formats are the grammars in
 * [DeckFileFormat], each ported from an upstream XMage importer/exporter.
 */
interface DeckIO {
    /**
     * Parse [text] into a [DeckImportResult]. When [format] is `null` the format is auto-detected from
     * the content ([DeckFileFormat.DCK] by its `[SET:NUM]` lines, [DeckFileFormat.MTGA] by its headers/
     * `(SET)` marks, else [DeckFileFormat.TEXT]). Unresolved/ambiguous/malformed lines are reported in
     * the result, never silently dropped.
     */
    suspend fun import(
        text: String,
        format: DeckFileFormat? = null,
    ): DeckImportResult

    /** Serialize [deck] to text in the given [format]. Pure and deterministic. */
    suspend fun export(
        deck: Deck,
        format: DeckFileFormat,
    ): String

    /**
     * Produce shareable [DeckShareContent] for [deck] in [format]: the serialized text plus a suggested
     * file name (deck name sanitized + extension) and mime type. The share *intent* is story 0035.
     */
    suspend fun share(
        deck: Deck,
        format: DeckFileFormat,
    ): DeckShareContent
}
