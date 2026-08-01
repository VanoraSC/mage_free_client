package magefree.decks.internal

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import magefree.cards.CardCatalog
import magefree.cards.model.Card
import magefree.decks.legality.DeckLegality
import magefree.decks.legality.DeckLegalityResult
import magefree.decks.legality.FormatInfo
import magefree.decks.legality.FormatRules
import magefree.decks.legality.LegalityBundle
import magefree.decks.legality.LegalityViolation
import magefree.decks.legality.UNLIMITED_COPIES
import magefree.decks.model.Deck
import magefree.decks.model.DeckFormat

/**
 * [DeckLegality] over the bundled format data + the offline [CardCatalog]. The bundle is loaded once
 * (lazily) via [bundleProvider]; every check is a local computation on the injected [ioDispatcher].
 *
 * Set- and rarity-legality are evaluated by card **name across all of that card's printings** — this
 * mirrors XMage's `Constructed.legalSets`/`legalRarity`, which pass a card if *any* of its printings
 * qualifies (via `CardRepository.findCards(name)`). The catalog supplies those printings offline.
 */
internal class DefaultDeckLegality(
    private val bundleProvider: suspend () -> LegalityBundle,
    private val catalog: CardCatalog,
    private val ioDispatcher: CoroutineDispatcher,
) : DeckLegality {
    private val loadMutex = Mutex()

    @Volatile
    private var bundle: LegalityBundle? = null

    private suspend fun bundle(): LegalityBundle {
        bundle?.let { return it }
        return loadMutex.withLock {
            bundle ?: bundleProvider().also { bundle = it }
        }
    }

    override suspend fun availableFormats(): List<FormatInfo> =
        withContext(ioDispatcher) {
            bundle().formats.map { FormatInfo(it.key, it.displayName) }
        }

    override suspend fun check(
        deck: Deck,
        format: DeckFormat,
    ): DeckLegalityResult =
        withContext(ioDispatcher) {
            val data = bundle()
            val rules =
                requireNotNull(data.formats.firstOrNull { it.key == format.key }) {
                    "No bundled legality data for format '${format.key}'"
                }
            val violations = ArrayList<LegalityViolation>()

            // Structural: deck size (main) + sideboard size.
            val mainTotal = deck.mainCount
            if (mainTotal < rules.deckMinSize) {
                violations += LegalityViolation.DeckTooSmall(mainTotal, rules.deckMinSize)
            }
            val sideTotal = deck.sideboardCount
            if (sideTotal > rules.sideboardMax) {
                violations += LegalityViolation.SideboardTooLarge(sideTotal, rules.sideboardMax)
            }

            // Copy counts are pooled across main + sideboard, as XMage counts them.
            val counts = LinkedHashMap<String, Int>()
            (deck.main + deck.sideboard).forEach { e ->
                counts[e.cardName] = (counts[e.cardName] ?: 0) + e.quantity
            }
            val names = counts.keys.sorted()

            val banned = rules.banned.toHashSet()
            val restricted = rules.restricted.toHashSet()
            for (name in names) {
                val count = counts.getValue(name)
                if (name in banned) {
                    violations += LegalityViolation.BannedCard(name)
                }
                if (name in restricted && count > 1) {
                    violations += LegalityViolation.RestrictedOverLimit(name, count)
                }
                val max = maxCopiesFor(name, data, rules)
                if (count > max) {
                    violations += LegalityViolation.TooManyCopies(name, count, max)
                }
            }

            // Set + rarity legality, by card name across all printings, using the offline catalog.
            val legalSets = rules.legalSetCodes.toHashSet()
            val allowedRarities = rules.allowedRarities?.toHashSet()
            for (name in names) {
                val card = exactByName(name) ?: continue
                if (legalSets.isNotEmpty() && card.setCodes.none { it in legalSets }) {
                    violations += LegalityViolation.IllegalSet(name)
                }
                if (allowedRarities != null && card.rarities.none { it.name in allowedRarities }) {
                    violations += LegalityViolation.InvalidRarity(name)
                }
            }

            DeckLegalityResult(format, violations.isEmpty(), violations)
        }

    private fun maxCopiesFor(
        name: String,
        data: LegalityBundle,
        rules: FormatRules,
    ): Int {
        val override = data.maxCopiesOverrides[name] ?: return rules.maxCopiesDefault
        return if (override == UNLIMITED_COPIES) Int.MAX_VALUE else override
    }

    /** Exact (case-insensitive) catalog lookup by card name; `null` if the catalog doesn't know it. */
    private suspend fun exactByName(name: String): Card? = catalog.search(name).firstOrNull { it.name.equals(name, ignoreCase = true) }
}
