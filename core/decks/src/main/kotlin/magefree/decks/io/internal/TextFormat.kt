package magefree.decks.io.internal

import magefree.decks.io.DeckImportIssue
import magefree.decks.io.DeckImportIssueKind
import magefree.decks.model.DeckList
import magefree.decks.model.DeckListCard
import magefree.decks.model.DeckZone

/**
 * MTGO/deckstats plain-text grammar, ported from `mage.cards.decks.importer.TxtDeckImporter` and
 * `mage.cards.decks.exporter.MtgOnlineDeckExporter` (ref e0fe4b6f6a).
 *
 * Grammar (per line, trimmed):
 * - `//…` comment → skipped; `//sideboard` switches subsequent cards to the sideboard.
 * - inner `#…` comment stripped from the line (deckstats style).
 * - a blank line (once any card has been seen) switches to the sideboard (`switchSideboardByEmptyLine`).
 * - `SB: N Name` marks a single sideboard card.
 * - `N Name` (count optional → defaults to 1). Non-digits in the count are stripped (`4x` → `4`); a
 *   count `<= 0` or `>= 100` is reported as malformed. Section headers in [IGNORE_NAMES] are skipped.
 * - names have no set/number, so the catalog picks the printing.
 */
internal object TextFormat {
    private val IGNORE_NAMES =
        setOf(
            "lands",
            "creatures",
            "planeswalkers",
            "other spells",
            "sideboard cards",
            "Instant",
            "Land",
            "Enchantment",
            "Artifact",
            "Sorcery",
            "Planeswalker",
            "Creature",
        )

    private val nonDigit = Regex("\\D+")

    fun parse(text: String): ParsedDeck {
        val entries = ArrayList<RawEntry>()
        val malformed = ArrayList<DeckImportIssue>()
        var sideboard = false
        var wasCardLines = false

        text.lineSequence().forEachIndexed { index, rawLine ->
            val lineNumber = index + 1
            var line = rawLine.trim()
            val lower = line.lowercase()

            if (lower.startsWith("//")) {
                if (lower.startsWith("//sideboard")) sideboard = true
                return@forEachIndexed
            }
            val hashIndex = line.indexOf('#')
            if (hashIndex >= 0) line = line.substring(0, hashIndex).trim()

            if (line.isEmpty() && !wasCardLines) return@forEachIndexed
            if (line.isEmpty()) { // blank line after cards → switch to sideboard (mtgo style)
                sideboard = true
                return@forEachIndexed
            }

            var singleLineSideboard = false
            if (line.startsWith("SB:")) {
                line = line.removePrefix("SB:").trim()
                singleLineSideboard = true
            }

            line = line.replace('\t', ' ')
            val delim = line.indexOf(' ')
            var lineNum = ""
            if (delim > 0) {
                lineNum = line.substring(0, delim).trim()
                if (lineNum in IGNORE_NAMES) return@forEachIndexed
            }

            var amount = 0
            var haveAmount = false
            if (lineNum.isNotEmpty()) {
                val digits = lineNum.replace(nonDigit, "")
                val parsed = digits.toIntOrNull()
                if (parsed != null) {
                    if (parsed <= 0 || parsed >= 100) {
                        malformed +=
                            DeckImportIssue(
                                DeckImportIssueKind.MALFORMED,
                                lineNumber,
                                rawLine,
                                "Invalid card count '$lineNum' (must be 1..99)",
                            )
                        return@forEachIndexed
                    }
                    amount = parsed
                    haveAmount = true
                }
            }

            var lineName =
                if (haveAmount) line.substring(delim).trim() else line.trim()
            if (!haveAmount) amount = 1

            lineName = CardNames.fixSplitSeparator(CardNames.normalize(lineName))
            if (lineName in IGNORE_NAMES || lineName.isEmpty()) return@forEachIndexed

            wasCardLines = true
            entries +=
                RawEntry(
                    name = lineName,
                    setCode = null,
                    collectorNumber = null,
                    amount = amount,
                    zone = if (sideboard || singleLineSideboard) DeckZone.SIDEBOARD else DeckZone.MAIN,
                    lineNumber = lineNumber,
                )
        }
        return ParsedDeck(entries = entries, malformed = malformed)
    }

    /**
     * Serialize like `MtgOnlineDeckExporter`: `N Name` lines for the main deck, then a blank line, then
     * `N Name` lines for the sideboard. The blank separator is what [parse] uses to switch zones, so a
     * serialized deck round-trips. Entries are merged by card name (upstream `getCardName` key).
     */
    fun serialize(deck: DeckList): String =
        buildString {
            aggregate(deck.cards).forEach { appendLine("${it.amount} ${it.cardName}") }
            val side = aggregate(deck.sideboard)
            if (side.isNotEmpty()) {
                appendLine()
                side.forEach { appendLine("${it.amount} ${it.cardName}") }
            }
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
