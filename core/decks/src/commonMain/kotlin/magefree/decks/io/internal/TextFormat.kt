package magefree.decks.io.internal

import magefree.decks.io.DeckImportIssue
import magefree.decks.io.DeckImportIssueKind
import magefree.decks.model.DeckList
import magefree.decks.model.DeckListCard
import magefree.decks.model.DeckZone

/**
 * MTGO/deckstats plain-text grammar, ported from `mage.cards.decks.importer.TxtDeckImporter` and
 * `mage.cards.decks.exporter.MtgOnlineDeckExporter` (ref e0fe4b6f6a), plus explicit section-header
 * recognition for exports (MTGGoldfish and others) that label their sections instead of relying on a
 * blank line — see the "found live" note below.
 *
 * Grammar (per line, trimmed):
 * - `//…` comment → skipped; `//sideboard` switches subsequent cards to the sideboard.
 * - inner `#…` comment stripped from the line (deckstats style).
 * - `Deck` / `Mainboard` → subsequent cards are main; `Sideboard` / `Commander` / `Maybeboard` →
 *   subsequent cards are sideboard. Matched as a whole trimmed line, case-insensitive — narrower than
 *   [MtgaFormat]'s prefix match, deliberately: [IGNORE_NAMES] already has a "sideboard cards" *label*
 *   (used inline within one list, not as a zone divider) that a prefix match would misfire on. `About`
 *   and a leading `Name …` line, while no card has been seen yet, are skipped as file metadata rather
 *   than parsed as 1-count cards named "About"/"Name …".
 * - otherwise, a blank line (once any card has been seen) switches to the sideboard
 *   (`switchSideboardByEmptyLine`) — the fallback for files with no headers at all.
 * - `SB: N Name` marks a single sideboard card.
 * - `N Name` (count optional → defaults to 1). Non-digits in the count are stripped (`4x` → `4`); a
 *   count `<= 0` or `>= 100` is reported as malformed. Section headers in [IGNORE_NAMES] are skipped.
 * - names have no set/number, so the catalog picks the printing.
 *
 * **Found live (Pete, 2026-08-20): an MTGGoldfish export put all 75 cards in the sideboard.**
 * Goldfish's plain-text export looks like `About` / `Name <deck>` / blank / `Deck` / … / blank /
 * `Sideboard` / … — upstream's own blank-line rule has no way back to the main deck once it fires
 * (confirmed by reading `TxtDeckImporter.readLine` directly: `sideboard` is only ever set, never
 * cleared, unlike [MtgaFormat.parse]'s explicit `Deck`/`Mainboard` reset), so the blank line between
 * the `About`/`Name` preamble and `Deck` latched `sideboard = true` for the rest of the file — the
 * real `Deck` section included. Recognizing `Deck`/`Mainboard` as an explicit reset (as [MtgaFormat]
 * already does) fixes it structurally, and skipping `About`/`Name …` before any card is seen means
 * the blank line right after them is treated as ordinary pre-card whitespace (already skipped by the
 * `!wasCardLines` rule) rather than the trigger.
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
        var name: String? = null

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

            if (lower == "deck" || lower == "mainboard") {
                sideboard = false
                return@forEachIndexed
            }
            if (lower == "sideboard" || lower == "commander" || lower == "maybeboard") {
                sideboard = true
                return@forEachIndexed
            }
            if (!wasCardLines && lower == "about") return@forEachIndexed
            if (!wasCardLines && lower.startsWith("name ")) {
                name = line.substring(5).trim().ifEmpty { null }
                return@forEachIndexed
            }

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
        return ParsedDeck(name = name, entries = entries, malformed = malformed)
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
