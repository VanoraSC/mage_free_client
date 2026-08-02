package magefree.decks.io.internal

import magefree.decks.io.DeckImportIssue
import magefree.decks.io.DeckImportIssueKind
import magefree.decks.model.DeckList
import magefree.decks.model.DeckListCard
import magefree.decks.model.DeckZone

/**
 * Decked Builder / Apprentice / old-MTGO `.dec` grammar, ported from
 * `mage.cards.decks.importer.DecDeckImporter` (ref e0fe4b6f6a).
 *
 * Grammar (per line, trimmed):
 * - blank or `//…` → skipped.
 * - `SB: ` prefix marks a sideboard card.
 * - `<count> <card name>` — the count must parse as an integer, else the line is malformed; a line
 *   with no space is malformed. Names carry no set/number, so the catalog picks the printing.
 */
internal object DecFormat {
    fun parse(text: String): ParsedDeck {
        val entries = ArrayList<RawEntry>()
        val malformed = ArrayList<DeckImportIssue>()

        text.lineSequence().forEachIndexed { index, rawLine ->
            val lineNumber = index + 1
            var line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("//")) return@forEachIndexed

            var sideboard = false
            if (line.startsWith("SB:")) {
                line = line.substring(3).trim()
                sideboard = true
            }

            val delim = line.indexOf(' ')
            if (delim <= 0) {
                malformed +=
                    DeckImportIssue(
                        DeckImportIssueKind.MALFORMED,
                        lineNumber,
                        rawLine,
                        "Expected '<count> <card name>'",
                    )
                return@forEachIndexed
            }
            val lineNum = line.substring(0, delim).trim()
            val lineName = line.substring(delim).trim()
            val count = lineNum.toIntOrNull()
            if (count == null || count <= 0) {
                malformed +=
                    DeckImportIssue(
                        DeckImportIssueKind.MALFORMED,
                        lineNumber,
                        rawLine,
                        "Invalid number '$lineNum'",
                    )
                return@forEachIndexed
            }
            entries +=
                RawEntry(
                    name = CardNames.fixSplitSeparator(CardNames.normalize(lineName)),
                    setCode = null,
                    collectorNumber = null,
                    amount = count,
                    zone = if (sideboard) DeckZone.SIDEBOARD else DeckZone.MAIN,
                    lineNumber = lineNumber,
                )
        }
        return ParsedDeck(entries = entries, malformed = malformed)
    }

    /** Serialize as `N Name` for the main deck and `SB: N Name` for the sideboard (round-trips via [parse]). */
    fun serialize(deck: DeckList): String =
        buildString {
            aggregate(deck.cards).forEach { appendLine("${it.amount} ${it.cardName}") }
            aggregate(deck.sideboard).forEach { appendLine("SB: ${it.amount} ${it.cardName}") }
        }

    private fun aggregate(cards: List<DeckListCard>): List<DeckListCard> {
        val byName = LinkedHashMap<String, DeckListCard>()
        for (c in cards) {
            val existing = byName[c.cardName]
            byName[c.cardName] = existing?.copy(amount = existing.amount + c.amount) ?: c
        }
        return byName.values.toList()
    }
}
