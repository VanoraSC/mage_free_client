package magefree.decks.io.internal

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import magefree.cards.CardCatalog
import magefree.cards.model.Card
import magefree.decks.io.DeckFileFormat
import magefree.decks.io.DeckIO
import magefree.decks.io.DeckImportIssue
import magefree.decks.io.DeckImportIssueKind
import magefree.decks.io.DeckImportResult
import magefree.decks.io.DeckShareContent
import magefree.decks.model.Deck
import magefree.decks.model.DeckId
import magefree.decks.model.DeckList
import magefree.decks.model.DeckListCard
import magefree.decks.model.DeckZone
import magefree.decks.model.toDeck
import magefree.decks.model.toDeckList

/**
 * [DeckIO] over the offline [CardCatalog]. Each format's pure parser produces a [ParsedDeck]; this
 * class resolves every [RawEntry]'s name → a catalog printing and assembles the 0033 [Deck], reporting
 * unresolved / ambiguous / malformed lines. Serialization delegates back to the format objects.
 *
 * **Name resolution.** A name is matched case-insensitively against the catalog (via
 * [CardCatalog.search], which also matches split-card halves), taking the exact-name hit. When the
 * source line already carries a set + collector number (`.dck`, MTGA-with-set) that printing is kept
 * verbatim — the file is authoritative and the printing may not even be in our bundle. When it carries
 * only a name (plain text, `.dec`), the catalog's first printing (deterministic: ordered by set code
 * then collector number) is chosen; if the card has more than one printing that choice is reported as
 * [DeckImportIssueKind.AMBIGUOUS]. An unknown name is reported as [DeckImportIssueKind.UNRESOLVED] and
 * contributes no card. Resolved entries are merged within each zone (by name + set + number) so a
 * round-trip is stable.
 *
 * The imported [Deck] is built with a blank [DeckId] and no library metadata (format/timestamps/
 * favorite) — those are assigned when the deck is persisted (0033 repository / 0035 UI).
 */
internal class DefaultDeckIO(
    private val catalog: CardCatalog,
    private val ioDispatcher: CoroutineDispatcher,
) : DeckIO {
    override suspend fun import(
        text: String,
        format: DeckFileFormat?,
    ): DeckImportResult =
        withContext(ioDispatcher) {
            val resolvedFormat = format ?: detectFormat(text)
            val parsed = parse(text, resolvedFormat)

            val issues = ArrayList<DeckImportIssue>(parsed.malformed)
            val mainCards = ArrayList<DeckListCard>()
            val sideboardCards = ArrayList<DeckListCard>()

            for (entry in parsed.entries) {
                val card = exactByName(entry.name)
                if (card == null) {
                    issues +=
                        DeckImportIssue(
                            DeckImportIssueKind.UNRESOLVED,
                            entry.lineNumber,
                            entry.name,
                            "No card named '${entry.name}' in the catalog",
                        )
                    continue
                }
                val resolved = resolvePrinting(card, entry, issues)
                if (entry.zone == DeckZone.MAIN) mainCards += resolved else sideboardCards += resolved
            }

            val deckList =
                DeckList(
                    name = parsed.name,
                    author = parsed.author,
                    cards = merge(mainCards),
                    sideboard = merge(sideboardCards),
                )
            DeckImportResult(deck = deckList.toDeck(id = DeckId("")), issues = issues)
        }

    override suspend fun export(
        deck: Deck,
        format: DeckFileFormat,
    ): String =
        withContext(ioDispatcher) {
            serialize(deck.toDeckList(), format)
        }

    override suspend fun share(
        deck: Deck,
        format: DeckFileFormat,
    ): DeckShareContent =
        withContext(ioDispatcher) {
            DeckShareContent(
                fileName = suggestFileName(deck.name, format),
                mimeType = format.mimeType,
                content = serialize(deck.toDeckList(), format),
            )
        }

    private fun parse(
        text: String,
        format: DeckFileFormat,
    ): ParsedDeck =
        when (format) {
            DeckFileFormat.DCK -> DckFormat.parse(text)
            DeckFileFormat.TEXT -> TextFormat.parse(text)
            DeckFileFormat.DEC -> DecFormat.parse(text)
            DeckFileFormat.MTGA -> MtgaFormat.parse(text)
        }

    private fun serialize(
        deckList: DeckList,
        format: DeckFileFormat,
    ): String =
        when (format) {
            DeckFileFormat.DCK -> DckFormat.serialize(deckList)
            DeckFileFormat.TEXT -> TextFormat.serialize(deckList)
            DeckFileFormat.DEC -> DecFormat.serialize(deckList)
            DeckFileFormat.MTGA -> MtgaFormat.serialize(deckList)
        }

    /** Choose a printing for a resolved [card], preserving a file-supplied set or picking the catalog default. */
    private fun resolvePrinting(
        card: Card,
        entry: RawEntry,
        issues: MutableList<DeckImportIssue>,
    ): DeckListCard {
        // Self-describing line: keep the file's set + collector number (authoritative for interchange).
        if (entry.setCode != null) {
            return DeckListCard(
                cardName = card.name,
                setCode = entry.setCode,
                collectorNumber = entry.collectorNumber.orEmpty(),
                amount = entry.amount,
            )
        }
        val printings = card.printings
        if (printings.isEmpty()) {
            return DeckListCard(card.name, "", "", entry.amount)
        }
        val chosen = printings.first()
        if (printings.size > 1) {
            issues +=
                DeckImportIssue(
                    DeckImportIssueKind.AMBIGUOUS,
                    entry.lineNumber,
                    entry.name,
                    "'${card.name}' has ${printings.size} printings; chose ${chosen.setCode}:${chosen.collectorNumber}",
                )
        }
        return DeckListCard(card.name, chosen.setCode, chosen.collectorNumber, entry.amount)
    }

    /** Exact (case-insensitive) catalog lookup by card name; `null` if the catalog doesn't know it. */
    private suspend fun exactByName(name: String): Card? = catalog.search(name).firstOrNull { it.name.equals(name, ignoreCase = true) }

    /** Auto-detect the file format from content. */
    private fun detectFormat(text: String): DeckFileFormat =
        when {
            looksLikeDck(text) -> DeckFileFormat.DCK
            MtgaFormat.looksLikeMtga(text) -> DeckFileFormat.MTGA
            else -> DeckFileFormat.TEXT
        }

    private fun looksLikeDck(text: String): Boolean =
        text.lineSequence().any { raw ->
            val line = raw.trim()
            line.startsWith("NAME:") ||
                line.startsWith("AUTHOR:") ||
                line.startsWith("LAYOUT") ||
                DCK_CARD_LINE.containsMatchIn(line)
        }

    private companion object {
        // A .dck card line always carries a bracketed [SET:NUM] token — the strongest content signal.
        val DCK_CARD_LINE = Regex("\\[[^\\]:]+:[^\\]:]+\\]")

        val UNSAFE_FILENAME = Regex("[^A-Za-z0-9 ._-]")

        /** Merge cards sharing name + set + number within a zone, summing amounts, keeping first order. */
        fun merge(cards: List<DeckListCard>): List<DeckListCard> {
            val byKey = LinkedHashMap<String, DeckListCard>()
            for (c in cards) {
                val key = c.cardName + "|" + c.setCode + "|" + c.collectorNumber
                val existing = byKey[key]
                byKey[key] = existing?.copy(amount = existing.amount + c.amount) ?: c
            }
            return byKey.values.toList()
        }

        fun suggestFileName(
            deckName: String,
            format: DeckFileFormat,
        ): String {
            val base =
                deckName
                    .trim()
                    .replace(UNSAFE_FILENAME, "_")
                    .trim('_', ' ')
                    .ifEmpty { "deck" }
            return "$base.${format.extension}"
        }
    }
}
