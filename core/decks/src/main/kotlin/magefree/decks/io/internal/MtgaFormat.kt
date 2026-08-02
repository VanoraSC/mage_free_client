package magefree.decks.io.internal

import magefree.decks.io.DeckImportIssue
import magefree.decks.io.DeckImportIssueKind
import magefree.decks.model.DeckList
import magefree.decks.model.DeckListCard
import magefree.decks.model.DeckZone
import java.util.Locale

/**
 * MTG Arena export grammar, ported from `mage.cards.decks.importer.MtgaImporter` and
 * `mage.cards.decks.exporter.MtgArenaDeckExporter` (ref e0fe4b6f6a).
 *
 * Grammar (per line, trimmed, `*F*` foil marks removed):
 * - `Deck` / `Mainboard` headers → main; `Sideboard` / `Commander` / `Maybeboard` / blank → sideboard.
 * - `<count> <name>` optionally followed by `(<set>) <number>`, matched by [MTGA_PATTERN]
 *   (`(\d+)\s+(<name>)(?:\s+\()?(alnum)?(?:\)\s+)?(graph)?`). When a set is present the line is
 *   self-describing; otherwise the catalog picks the printing. Set `DAR` remaps to `DOM` on import.
 */
internal object MtgaFormat {
    // XMage set remap on import (Arena's Dominaria code DAR → XMage DOM).
    private val SET_REMAPPING = mapOf("DAR" to "DOM")

    // XMage: (\d+)\s+( CARD_NAME )(?:\s+\()?(\p{Alnum}+)?(?:\)\s+)?(\p{Graph}+)?
    private val MTGA_PATTERN =
        Regex(
            "(\\d+)" +
                "\\s+" +
                "(" + CardNames.CARD_NAME_PATTERN + ")" +
                "(?:\\s+\\()?" +
                "(\\p{Alnum}+)?" +
                "(?:\\)\\s+)?" +
                "(\\p{Graph}+)?",
        )

    fun parse(text: String): ParsedDeck {
        val entries = ArrayList<RawEntry>()
        val malformed = ArrayList<DeckImportIssue>()
        var sideboard = false

        text.lineSequence().forEachIndexed { index, rawLine ->
            val lineNumber = index + 1
            val line = rawLine.replace(" *F*", "").trim()
            val lower = line.lowercase(Locale.ENGLISH)

            if (lower.startsWith("deck") || lower.startsWith("mainboard")) {
                sideboard = false
                return@forEachIndexed
            }
            if (lower.startsWith("sideboard") ||
                lower.startsWith("commander") ||
                lower.startsWith("maybeboard") ||
                line.isEmpty()
            ) {
                sideboard = true
                return@forEachIndexed
            }

            val match = MTGA_PATTERN.matchEntire(CardNames.normalize(line))
            if (match == null) {
                malformed +=
                    DeckImportIssue(
                        DeckImportIssueKind.MALFORMED,
                        lineNumber,
                        rawLine,
                        "Unrecognized MTGA line",
                    )
                return@forEachIndexed
            }
            val count = match.groupValues[1].toIntOrNull()
            if (count == null || count <= 0) {
                malformed += DeckImportIssue(DeckImportIssueKind.MALFORMED, lineNumber, rawLine, "Invalid card count")
                return@forEachIndexed
            }
            val name = match.groupValues[2].trim()
            val rawSet = match.groups[3]?.value
            val setCode = rawSet?.let { SET_REMAPPING[it] ?: it }
            val cardNumber = match.groups[4]?.value

            entries +=
                RawEntry(
                    name = name,
                    setCode = setCode,
                    collectorNumber = if (setCode != null) cardNumber else null,
                    amount = count,
                    zone = if (sideboard) DeckZone.SIDEBOARD else DeckZone.MAIN,
                    lineNumber = lineNumber,
                )
        }
        return ParsedDeck(entries = entries, malformed = malformed)
    }

    /**
     * Serialize like `MtgArenaDeckExporter`: `N Name (SET) NUM` main lines, a blank line, then the
     * sideboard. Set codes are upper-cased and `DOM` remaps to `DAR` (Arena's code). Round-trips via
     * [parse], whose blank-line rule moves to the sideboard.
     */
    fun serialize(deck: DeckList): String =
        buildString {
            aggregate(deck.cards).forEach { append(line(it)).append('\n') }
            val side = aggregate(deck.sideboard)
            if (side.isNotEmpty()) {
                append('\n')
                side.forEach { append(line(it)).append('\n') }
            }
        }

    private fun line(c: DeckListCard): String {
        val set = c.setCode.uppercase(Locale.ENGLISH).let { if (it == "DOM") "DAR" else it }
        return "${c.amount} ${c.cardName} ($set) ${c.collectorNumber}"
    }

    /** Merge by the Arena line key (name + set + number), as `MtgArenaDeckExporter` does. */
    private fun aggregate(cards: List<DeckListCard>): List<DeckListCard> {
        val byKey = LinkedHashMap<String, DeckListCard>()
        for (c in cards) {
            val key = c.cardName + "|" + c.setCode + "|" + c.collectorNumber
            val existing = byKey[key]
            byKey[key] = existing?.copy(amount = existing.amount + c.amount) ?: c
        }
        return byKey.values.toList()
    }

    /** Heuristic port of `MtgaImporter.isMTGA`: header marks, or a first card line carrying a `(SET)`. */
    fun looksLikeMtga(text: String): Boolean {
        val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: return false
        val lower = firstLine.lowercase(Locale.ENGLISH)
        if (lower.startsWith("deck") ||
            lower.startsWith("mainboard") ||
            lower.startsWith("sideboard") ||
            lower.startsWith("commander") ||
            lower.startsWith("maybeboard")
        ) {
            return true
        }
        val match = MTGA_PATTERN.matchEntire(CardNames.normalize(firstLine))
        return match != null && match.groups[3] != null
    }
}
