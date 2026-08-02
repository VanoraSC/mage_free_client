package magefree.decks.io.internal

import magefree.decks.io.DeckImportIssue
import magefree.decks.model.DeckZone

/**
 * The format-agnostic result of parsing deck text, before the catalog resolves names → printings. A
 * format parser (`.dck`, plain text, `.dec`, MTGA) produces this; [magefree.decks.io.DeckIO] turns
 * [RawEntry] rows into resolved cards and folds [malformed] into the final issue list.
 *
 * This mirrors XMage's split of responsibilities: the importer classes parse text into
 * `DeckCardLists` rows and collect a message buffer for problem lines — here [entries] are the rows
 * and [malformed] the message buffer, kept structured rather than a `StringBuilder`.
 */
internal data class ParsedDeck(
    val name: String? = null,
    val author: String? = null,
    val entries: List<RawEntry> = emptyList(),
    val malformed: List<DeckImportIssue> = emptyList(),
)

/**
 * A single parsed card line before catalog resolution. [setCode]/[collectorNumber] are non-null only
 * for self-describing formats (`.dck`, and MTGA lines that carry a `(SET) NUM`); plain-text/`.dec`
 * lines carry only a [name] and leave the printing to the catalog.
 */
internal data class RawEntry(
    val name: String,
    val setCode: String?,
    val collectorNumber: String?,
    val amount: Int,
    val zone: DeckZone,
    val lineNumber: Int,
)
