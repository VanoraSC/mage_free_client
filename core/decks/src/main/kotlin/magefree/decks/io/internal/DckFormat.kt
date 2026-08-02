package magefree.decks.io.internal

import magefree.decks.io.DeckImportIssue
import magefree.decks.io.DeckImportIssueKind
import magefree.decks.model.DeckList
import magefree.decks.model.DeckListCard
import magefree.decks.model.DeckZone

/**
 * XMage native `.dck` grammar, ported from `mage.cards.decks.importer.DckDeckImporter` and
 * `mage.cards.decks.exporter.XmageDeckExporter` (ref e0fe4b6f6a).
 *
 * Grammar (per line, after trimming):
 * - blank or `#…` comment → skipped.
 * - `NAME:<deck name>` / `AUTHOR:<author>` headers.
 * - `[SB:] <count> [<set>:<number>] <card name>` — the card line, matched by [CARD_PATTERN], which is
 *   XMage's `(SB:)?\s*(\d*)\s*\[([^]:]+):([^]:]+)\]\s*(.*)\s*$`.
 * - `LAYOUT …` — deck-editor grid positioning; **intentionally not ported** (it is UI-only metadata,
 *   out of scope for import/export) and skipped without error.
 *
 * A `.dck` line is self-describing (it carries set + collector number), so parsing does not touch the
 * catalog; [magefree.decks.io.DeckIO] resolves the name and keeps the file's printing.
 */
internal object DckFormat {
    // XMage: Pattern.compile("(SB:)?\\s*(\\d*)\\s*\\[([^]:]+):([^]:]+)\\]\\s*(.*)\\s*$")
    private val CARD_PATTERN = Regex("^(SB:)?\\s*(\\d*)\\s*\\[([^\\]:]+):([^\\]:]+)\\]\\s*(.*?)\\s*$")

    fun parse(text: String): ParsedDeck {
        var name: String? = null
        var author: String? = null
        val entries = ArrayList<RawEntry>()
        val malformed = ArrayList<DeckImportIssue>()

        text.lineSequence().forEachIndexed { index, rawLine ->
            val lineNumber = index + 1
            val line = CardNames.normalize(rawLine.trim())
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed

            val match = CARD_PATTERN.matchEntire(line)
            when {
                match != null -> {
                    val (sb, countText, setCode, cardNum, cardName) = match.destructured
                    val count = countText.trim().toIntOrNull()
                    val cleanName = cardName.trim()
                    if (count == null || count <= 0) {
                        malformed +=
                            DeckImportIssue(
                                DeckImportIssueKind.MALFORMED,
                                lineNumber,
                                rawLine,
                                "Missing or invalid card count",
                            )
                    } else if (cleanName.isEmpty()) {
                        malformed +=
                            DeckImportIssue(
                                DeckImportIssueKind.MALFORMED,
                                lineNumber,
                                rawLine,
                                "Missing card name",
                            )
                    } else {
                        entries +=
                            RawEntry(
                                name = cleanName,
                                setCode = setCode.trim(),
                                collectorNumber = cardNum.trim(),
                                amount = count,
                                zone = if (sb == "SB:") DeckZone.SIDEBOARD else DeckZone.MAIN,
                                lineNumber = lineNumber,
                            )
                    }
                }
                line.startsWith("NAME:") -> name = line.substring(5)
                line.startsWith("AUTHOR:") -> author = line.substring(7)
                line.startsWith("LAYOUT") -> Unit // UI-only grid metadata; not ported.
                else ->
                    malformed +=
                        DeckImportIssue(
                            DeckImportIssueKind.MALFORMED,
                            lineNumber,
                            rawLine,
                            "Unrecognized .dck line",
                        )
            }
        }
        return ParsedDeck(name = name, author = author, entries = entries, malformed = malformed)
    }

    /**
     * Serialize to `.dck`, matching `XmageDeckExporter`: optional `NAME:`/`AUTHOR:` headers, then main
     * lines `N [SET:NUM] Name` and sideboard lines `SB: N [SET:NUM] Name`. Entries sharing a
     * set+collector-number key are merged (summing amounts) preserving first-seen order, as upstream does.
     */
    fun serialize(deck: DeckList): String =
        buildString {
            if (!deck.name.isNullOrEmpty()) appendLine("NAME:${deck.name}")
            if (!deck.author.isNullOrEmpty()) appendLine("AUTHOR:${deck.author}")
            aggregate(deck.cards).forEach { c ->
                appendLine("${c.amount} [${c.setCode}:${c.collectorNumber}] ${c.cardName}")
            }
            aggregate(deck.sideboard).forEach { c ->
                appendLine("SB: ${c.amount} [${c.setCode}:${c.collectorNumber}] ${c.cardName}")
            }
        }

    /** Merge by set+collector-number (XMage's `getCardKey`), summing amounts, keeping first order. */
    private fun aggregate(cards: List<DeckListCard>): List<DeckListCard> {
        val byKey = LinkedHashMap<String, DeckListCard>()
        for (c in cards) {
            val key = c.setCode + c.collectorNumber
            val existing = byKey[key]
            byKey[key] = existing?.copy(amount = existing.amount + c.amount) ?: c
        }
        return byKey.values.toList()
    }
}
