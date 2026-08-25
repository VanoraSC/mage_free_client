package magefree.decks.legality

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import magefree.cards.CardCatalog
import magefree.cards.model.Card
import magefree.cards.model.CardFilter
import magefree.cards.model.CardId
import magefree.cards.model.CardPrinting
import magefree.cards.model.CatalogCounts
import magefree.cards.model.ManaCost
import magefree.cards.model.Rarity
import magefree.cards.model.TypeLine
import magefree.decks.internal.DefaultDeckLegality
import magefree.decks.model.Deck
import magefree.decks.model.DeckEntry
import magefree.decks.model.DeckFormat
import magefree.decks.model.DeckId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Offline legality checks over crafted decks: a legal deck plus each violation kind (banned, illegal
 * set, too-few-cards, >4 copies, and Pauper rarity), across two formats. Uses a hand-built legality
 * bundle + a fake [CardCatalog] so the test is hermetic and needs no network or device.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeckLegalityTest {
    // --- Crafted card catalog (name -> printings, by set + rarity) ---
    private val catalog =
        FakeCardCatalog(
            card("Legal Guy", "MOD" to Rarity.RARE),
            card("Bad Card", "MOD" to Rarity.RARE),
            card("Old Card", "LEG" to Rarity.RARE), // only in a non-Modern set
            card("Island", "MOD" to Rarity.LAND),
            card("Plains", "PAU" to Rarity.LAND),
            card("Common Dude", "PAU" to Rarity.COMMON),
            card("Fancy Rare", "PAU" to Rarity.RARE), // not a Pauper-legal rarity
        )

    private val bundle =
        LegalityBundle(
            schemaVersion = 1,
            maxCopiesOverrides = mapOf("Island" to UNLIMITED_COPIES, "Plains" to UNLIMITED_COPIES),
            formats =
                listOf(
                    FormatRules(
                        key = "modern",
                        displayName = "Modern",
                        deckMinSize = 60,
                        sideboardMax = 15,
                        maxCopiesDefault = 4,
                        allowedRarities = null,
                        legalSetCodes = listOf("MOD"),
                        banned = listOf("Bad Card"),
                        restricted = emptyList(),
                    ),
                    FormatRules(
                        key = "pauper",
                        displayName = "Pauper",
                        deckMinSize = 60,
                        sideboardMax = 15,
                        maxCopiesDefault = 4,
                        allowedRarities = listOf("COMMON", "LAND"),
                        legalSetCodes = listOf("PAU"),
                        banned = emptyList(),
                        restricted = emptyList(),
                    ),
                ),
        )

    private fun legality() =
        DefaultDeckLegality(
            bundleProvider = { bundle },
            catalog = catalog,
            ioDispatcher = UnconfinedTestDispatcher(),
        )

    // --- Modern ---

    @Test
    fun `a legal Modern deck reports no violations`() =
        runTest {
            val deck =
                deck(
                    DeckEntry("Island", "MOD", "1", 56),
                    DeckEntry("Legal Guy", "MOD", "2", 4),
                )
            val result = legality().check(deck, DeckFormat.MODERN)

            assertTrue(result.violations.toString(), result.isLegal)
            assertTrue(result.violations.isEmpty())
        }

    @Test
    fun `a banned card is reported`() =
        runTest {
            val deck =
                deck(
                    DeckEntry("Island", "MOD", "1", 56),
                    DeckEntry("Bad Card", "MOD", "3", 4),
                )
            val result = legality().check(deck, DeckFormat.MODERN)

            assertFalse(result.isLegal)
            assertTrue(result.violations.any { it is LegalityViolation.BannedCard && it.cardName == "Bad Card" })
        }

    @Test
    fun `a card from an illegal set is reported`() =
        runTest {
            val deck =
                deck(
                    DeckEntry("Island", "MOD", "1", 56),
                    DeckEntry("Old Card", "LEG", "4", 4),
                )
            val result = legality().check(deck, DeckFormat.MODERN)

            assertFalse(result.isLegal)
            assertTrue(result.violations.any { it is LegalityViolation.IllegalSet && it.cardName == "Old Card" })
        }

    @Test
    fun `a deck below the minimum size is reported`() =
        runTest {
            val deck = deck(DeckEntry("Legal Guy", "MOD", "2", 4))
            val result = legality().check(deck, DeckFormat.MODERN)

            assertFalse(result.isLegal)
            assertTrue(
                result.violations.any {
                    it is LegalityViolation.DeckTooSmall && it.actual == 4 && it.required == 60
                },
            )
        }

    @Test
    fun `more than four copies of a non-basic is reported`() =
        runTest {
            val deck =
                deck(
                    DeckEntry("Island", "MOD", "1", 55),
                    DeckEntry("Legal Guy", "MOD", "2", 5),
                )
            val result = legality().check(deck, DeckFormat.MODERN)

            assertFalse(result.isLegal)
            assertTrue(
                result.violations.any {
                    it is LegalityViolation.TooManyCopies && it.cardName == "Legal Guy" && it.count == 5 && it.max == 4
                },
            )
            // Basic-land override: 55 Islands is fine.
            assertTrue(result.violations.none { it is LegalityViolation.TooManyCopies && it.cardName == "Island" })
        }

    // --- Pauper (rarity restriction) ---

    @Test
    fun `a legal Pauper deck of commons and basics is legal`() =
        runTest {
            val deck =
                deck(
                    DeckEntry("Plains", "PAU", "1", 56),
                    DeckEntry("Common Dude", "PAU", "2", 4),
                )
            val result = legality().check(deck, DeckFormat.PAUPER)

            assertTrue(result.violations.toString(), result.isLegal)
        }

    @Test
    fun `a non-common card is illegal in Pauper`() =
        runTest {
            val deck =
                deck(
                    DeckEntry("Plains", "PAU", "1", 56),
                    DeckEntry("Fancy Rare", "PAU", "3", 4),
                )
            val result = legality().check(deck, DeckFormat.PAUPER)

            assertFalse(result.isLegal)
            assertTrue(result.violations.any { it is LegalityViolation.InvalidRarity && it.cardName == "Fancy Rare" })
        }

    @Test
    fun `available formats come from the bundle`() =
        runTest {
            assertEquals(
                listOf("modern", "pauper"),
                legality().availableFormats().map { it.key },
            )
        }

    // --- Unknown / unbundled format (0042 defect C) ---

    @Test
    fun `a format the bundle does not cover yields a structured result rather than throwing`() =
        runTest {
            // The crafted bundle covers modern + pauper only; LEGACY is a DeckFormat the bundle lacks.
            val deck = deck(DeckEntry("Legal Guy", "MOD", "2", 4))
            val result = legality().check(deck, DeckFormat.LEGACY)

            assertEquals(DeckFormat.LEGACY, result.format)
            assertFalse("an unverifiable deck must not read as legal", result.isLegal)
            assertTrue(result.isFormatUnknown)
            assertEquals(
                listOf(LegalityViolation.UnknownFormat(DeckFormat.LEGACY.key)),
                result.violations,
            )
        }

    @Test
    fun `an unknown format short-circuits before any catalog read`() =
        runTest {
            legality().check(deck(DeckEntry("Legal Guy", "MOD", "2", 4)), DeckFormat.LEGACY)

            assertTrue(catalog.nameLookups.isEmpty())
            assertTrue(catalog.searchQueries.isEmpty())
        }

    // --- Exact-name resolution (0042 defect D) ---

    @Test
    fun `card resolution uses the batched exact-name lookup, never the substring search`() =
        runTest {
            val deck =
                deck(
                    DeckEntry("Island", "MOD", "1", 56),
                    DeckEntry("Legal Guy", "MOD", "2", 4),
                    DeckEntry("Old Card", "LEG", "4", 1),
                )
            legality().check(deck, DeckFormat.MODERN)

            assertTrue("substring search must not be used for exact name resolution", catalog.searchQueries.isEmpty())
            // One batch for the whole deck, carrying every distinct name.
            assertEquals(1, catalog.nameLookups.size)
            assertEquals(listOf("Island", "Legal Guy", "Old Card"), catalog.nameLookups.single().sorted())
        }

    @Test
    fun `exact-name resolution is case-insensitive`() =
        runTest {
            val deck =
                deck(
                    DeckEntry("Island", "MOD", "1", 56),
                    DeckEntry("old card", "LEG", "4", 4), // lower-cased in the deck, "Old Card" in the catalog
                )
            val result = legality().check(deck, DeckFormat.MODERN)

            // Resolved despite the casing, so its (illegal) set is still caught.
            assertTrue(result.violations.any { it is LegalityViolation.IllegalSet && it.cardName == "old card" })
        }

    // --- helpers ---

    private fun deck(vararg main: DeckEntry): Deck = Deck(id = DeckId("d"), name = "test", main = main.toList())

    private fun card(
        name: String,
        vararg printings: Pair<String, Rarity>,
    ): Card =
        Card(
            id = CardId(name.hashCode().toLong()),
            name = name,
            manaCost = ManaCost(""),
            manaValue = 0,
            colors = emptySet(),
            typeLine = TypeLine(emptyList(), emptyList(), emptyList()),
            rules = emptyList(),
            printings = printings.mapIndexed { i, (set, rarity) -> CardPrinting(set, (i + 1).toString(), rarity) },
        )

    private class FakeCardCatalog(
        vararg cards: Card,
    ) : CardCatalog {
        private val byName = cards.associateBy { it.name.lowercase() }

        /** Substring searches seen — the legality check must never issue one. */
        val searchQueries: MutableList<String> = mutableListOf()

        /** The name batches the exact-name path asked for. */
        val nameLookups: MutableList<List<String>> = mutableListOf()

        override suspend fun card(id: CardId): Card? = null

        override suspend fun search(
            query: String,
            limit: Int,
        ): List<Card> {
            searchQueries += query
            return listOfNotNull(byName[query.lowercase()])
        }

        override suspend fun cardsByName(names: Collection<String>): Map<String, Card> {
            nameLookups += names.toList()
            return names.mapNotNull { n -> byName[n.lowercase()]?.let { n.lowercase() to it } }.toMap()
        }

        override suspend fun filter(
            criteria: CardFilter,
            limit: Int,
        ): List<Card> = emptyList()

        override suspend fun counts(): CatalogCounts = CatalogCounts(cardCount = byName.size, printingCount = 0)
    }
}
