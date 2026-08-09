package magefree.decks.io

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import magefree.cards.CardCatalog
import magefree.cards.model.Card
import magefree.cards.model.CardFaces
import magefree.cards.model.CardFilter
import magefree.cards.model.CardId
import magefree.cards.model.CardPrinting
import magefree.cards.model.CatalogCounts
import magefree.cards.model.ManaCost
import magefree.cards.model.Rarity
import magefree.cards.model.TypeLine
import magefree.decks.io.internal.DefaultDeckIO
import magefree.decks.model.Deck
import magefree.decks.model.DeckEntry
import magefree.decks.model.DeckId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Offline import/export over a crafted catalog. Covers per-format round-trips, sideboard handling,
 * unresolved/ambiguous reporting, name→printing resolution (including a split card and a DFC), and
 * malformed-input → structured error. Uses a fake [CardCatalog]; no network, no device.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeckIOTest {
    private val catalog =
        FakeCardCatalog(
            // Multi-printing card → ambiguous when a plain-text line names it without a set.
            card("Lightning Bolt", "LEA" to "161", "M10" to "146", "M11" to "149"),
            card("Island", "MOD" to "60"),
            card("Counterspell", "MMQ" to "68"),
            card("Brainstorm", "ICE" to "78"),
            // Split card — the catalog stores the joined "A // B" name.
            card("Fire // Ice", "APC" to "128", faces = CardFaces(splitCard = true)),
            // Double-faced card — deck references the front-face name only.
            card("Delver of Secrets", "ISD" to "51", faces = CardFaces(doubleFaced = true, secondSideName = "Insectile Aberration")),
        )

    private fun io() = DefaultDeckIO(catalog = catalog, ioDispatcher = UnconfinedTestDispatcher())

    // --- .dck ---

    @Test
    fun `dck import parses name author main and sideboard`() =
        runTest {
            val text =
                """
                NAME:Test Deck
                AUTHOR:Pete
                4 [M10:146] Lightning Bolt
                20 [MOD:60] Island
                SB: 2 [MMQ:68] Counterspell
                """.trimIndent()
            val result = io().import(text, DeckFileFormat.DCK)

            assertFalse(result.hasErrors)
            assertEquals("Test Deck", result.deck.name)
            assertEquals("Pete", result.deck.author)
            assertEquals(2, result.deck.main.size)
            assertEquals(DeckEntry("Lightning Bolt", "M10", "146", 4), result.deck.main[0])
            assertEquals(DeckEntry("Island", "MOD", "60", 20), result.deck.main[1])
            assertEquals(listOf(DeckEntry("Counterspell", "MMQ", "68", 2)), result.deck.sideboard)
        }

    @Test
    fun `dck round-trips`() = assertRoundTrips(DeckFileFormat.DCK)

    // --- plain text ---

    @Test
    fun `text import handles counts and empty-line sideboard`() =
        runTest {
            val text =
                """
                4 Lightning Bolt
                20 Island

                2 Counterspell
                """.trimIndent()
            val result = io().import(text, DeckFileFormat.TEXT)

            assertEquals(2, result.deck.main.size)
            assertEquals(1, result.deck.sideboard.size)
            assertEquals("Counterspell", result.deck.sideboard[0].cardName)
            // Island has a single printing → resolved, not ambiguous; Bolt has 3 → ambiguous.
            assertTrue(result.ambiguous.any { it.text == "Lightning Bolt" })
        }

    @Test
    fun `text import handles SB prefix sideboard`() =
        runTest {
            val text =
                """
                4 Lightning Bolt
                SB: 3 Counterspell
                """.trimIndent()
            val result = io().import(text, DeckFileFormat.TEXT)
            assertEquals(1, result.deck.main.size)
            assertEquals(listOf(DeckEntry("Counterspell", "MMQ", "68", 3)), result.deck.sideboard)
        }

    @Test
    fun `text round-trips`() = assertRoundTrips(DeckFileFormat.TEXT)

    // --- .dec ---

    @Test
    fun `dec import parses SB prefix`() =
        runTest {
            val text =
                """
                // my deck
                4 Lightning Bolt
                20 Island
                SB: 2 Counterspell
                """.trimIndent()
            val result = io().import(text, DeckFileFormat.DEC)
            assertEquals(2, result.deck.main.size)
            assertEquals(1, result.deck.sideboard.size)
        }

    @Test
    fun `dec round-trips`() = assertRoundTrips(DeckFileFormat.DEC)

    // --- MTGA ---

    @Test
    fun `mtga import parses set and number and header sideboard`() =
        runTest {
            val text =
                """
                Deck
                4 Lightning Bolt (M10) 146
                20 Island (MOD) 60

                Sideboard
                2 Counterspell (MMQ) 68
                """.trimIndent()
            val result = io().import(text, DeckFileFormat.MTGA)
            assertFalse(result.hasErrors)
            assertEquals(DeckEntry("Lightning Bolt", "M10", "146", 4), result.deck.main[0])
            assertEquals(listOf(DeckEntry("Counterspell", "MMQ", "68", 2)), result.deck.sideboard)
        }

    @Test
    fun `mtga round-trips`() = assertRoundTrips(DeckFileFormat.MTGA)

    // --- resolution: split & DFC ---

    @Test
    fun `split card resolves by joined name`() =
        runTest {
            val result = io().import("3 Fire // Ice", DeckFileFormat.TEXT)
            assertFalse(result.hasErrors)
            assertEquals(DeckEntry("Fire // Ice", "APC", "128", 3), result.deck.main.single())
        }

    @Test
    fun `single-slash split name is normalized to double-slash`() =
        runTest {
            val result = io().import("3 Fire / Ice", DeckFileFormat.TEXT)
            val resolved = result.deck.main.single()
            assertEquals("Fire // Ice", resolved.cardName)
        }

    @Test
    fun `double-faced card resolves by front-face name`() =
        runTest {
            val result = io().import("4 Delver of Secrets", DeckFileFormat.TEXT)
            assertFalse(result.hasErrors)
            assertEquals(DeckEntry("Delver of Secrets", "ISD", "51", 4), result.deck.main.single())
        }

    // --- reporting ---

    @Test
    fun `unresolved name is reported and not dropped silently`() =
        runTest {
            val result = io().import("4 Nonexistent Card", DeckFileFormat.TEXT)
            assertTrue(result.deck.main.isEmpty())
            assertEquals(1, result.unresolved.size)
            assertEquals("Nonexistent Card", result.unresolved.single().text)
            assertTrue(result.hasErrors)
        }

    @Test
    fun `ambiguous multi-printing is reported but still included`() =
        runTest {
            val result = io().import("4 Lightning Bolt", DeckFileFormat.TEXT)
            assertEquals(1, result.deck.main.size)
            assertEquals(1, result.ambiguous.size)
            // First printing by catalog order (set code then number): LEA:161.
            assertEquals(DeckEntry("Lightning Bolt", "LEA", "161", 4), result.deck.main.single())
        }

    @Test
    fun `malformed dck line yields a structured error not a crash`() =
        runTest {
            val text =
                """
                4 [M10:146] Lightning Bolt
                this is not a valid line
                x [BAD] Broken
                """.trimIndent()
            val result = io().import(text, DeckFileFormat.DCK)
            assertEquals(1, result.deck.main.size) // the one good line
            assertTrue(result.malformed.isNotEmpty())
            assertTrue(result.malformed.any { it.lineNumber == 2 })
        }

    @Test
    fun `malformed dec count yields a structured error`() =
        runTest {
            val result = io().import("notanumber Lightning Bolt", DeckFileFormat.DEC)
            assertEquals(1, result.malformed.size)
            assertEquals(DeckImportIssueKind.MALFORMED, result.malformed.single().kind)
        }

    // --- auto-detect ---

    @Test
    fun `auto-detect recognizes dck by bracket lines`() =
        runTest {
            val result = io().import("4 [M10:146] Lightning Bolt")
            assertEquals(DeckEntry("Lightning Bolt", "M10", "146", 4), result.deck.main.single())
        }

    @Test
    fun `auto-detect recognizes mtga by header`() =
        runTest {
            val result = io().import("Deck\n4 Lightning Bolt (M10) 146")
            assertEquals(DeckEntry("Lightning Bolt", "M10", "146", 4), result.deck.main.single())
        }

    @Test
    fun `auto-detect falls back to plain text`() =
        runTest {
            val result = io().import("4 Lightning Bolt\n20 Island")
            assertEquals(2, result.deck.main.size)
        }

    // --- share content ---

    @Test
    fun `share content carries filename mime and text`() =
        runTest {
            val deck =
                Deck(
                    id = DeckId("d"),
                    name = "My: Cool Deck!",
                    main = listOf(DeckEntry("Island", "MOD", "60", 20)),
                )
            val share = io().share(deck, DeckFileFormat.DCK)
            assertEquals("My_ Cool Deck.dck", share.fileName)
            assertEquals("text/plain", share.mimeType)
            assertTrue(share.content.contains("[MOD:60] Island"))
        }

    // --- exact-name resolution (0042 defect D) ---

    @Test
    fun `import resolves names through one batched exact-name lookup, not the substring search`() =
        runTest {
            val text =
                """
                4 [M10:146] Lightning Bolt
                20 [MOD:60] Island
                2 [APC:128] Fire // Ice
                SB: 3 [MMQ:68] Counterspell
                """.trimIndent()
            val result = io().import(text, DeckFileFormat.DCK)

            assertFalse(result.hasErrors)
            assertTrue("import must not fall back to the substring search", catalog.searchQueries.isEmpty())
            assertEquals("one batched lookup for the whole file", 1, catalog.nameLookups.size)
            assertEquals(
                listOf("Counterspell", "Fire // Ice", "Island", "Lightning Bolt"),
                catalog.nameLookups.single().sorted(),
            )
        }

    @Test
    fun `import resolves a name whose casing differs from the catalog`() =
        runTest {
            val result = io().import("4 [M10:146] lightning BOLT", DeckFileFormat.DCK)

            assertFalse(result.issues.toString(), result.hasErrors)
            // The catalog's canonical name wins, so the stored deck stays consistent.
            assertEquals(listOf(DeckEntry("Lightning Bolt", "M10", "146", 4)), result.deck.main)
        }

    // --- helpers ---

    /** Build a representative deck, export it, re-import, export again — the two imports must be equal. */
    private fun assertRoundTrips(format: DeckFileFormat) =
        runTest {
            val original =
                Deck(
                    id = DeckId(""),
                    name = "Round Trip",
                    author = "Pete",
                    main =
                        listOf(
                            DeckEntry("Lightning Bolt", "M10", "146", 4),
                            DeckEntry("Island", "MOD", "60", 20),
                            DeckEntry("Fire // Ice", "APC", "128", 2),
                        ),
                    sideboard = listOf(DeckEntry("Counterspell", "MMQ", "68", 3)),
                )
            val io = io()
            val text1 = io.export(original, format)
            val imported1 = io.import(text1, format)
            assertFalse("import of exported text had errors: ${imported1.issues}", imported1.hasErrors)

            val text2 = io.export(imported1.deck, format)
            val imported2 = io.import(text2, format)

            assertEquals(imported1.deck.main, imported2.deck.main)
            assertEquals(imported1.deck.sideboard, imported2.deck.sideboard)
            assertEquals(text1, text2)
        }

    private fun card(
        name: String,
        vararg printings: Pair<String, String>,
        faces: CardFaces = CardFaces(),
    ): Card =
        Card(
            id = CardId(name.hashCode().toLong()),
            name = name,
            manaCost = ManaCost(""),
            manaValue = 0,
            colors = emptySet(),
            typeLine = TypeLine(emptyList(), emptyList(), emptyList()),
            rules = emptyList(),
            faces = faces,
            printings = printings.map { (set, num) -> CardPrinting(set, num, Rarity.COMMON) },
        )

    /**
     * Fake catalog. [search] is the broad substring match (the user-facing box); [cardsByName] is the
     * exact case-insensitive batch the import path must use. [searchQueries] records any substring
     * search so a test can assert import never falls back to it.
     */
    private class FakeCardCatalog(
        vararg cards: Card,
    ) : CardCatalog {
        private val all = cards.toList()
        val searchQueries: MutableList<String> = mutableListOf()
        val nameLookups: MutableList<List<String>> = mutableListOf()

        override suspend fun card(id: CardId): Card? = all.firstOrNull { it.id == id }

        override suspend fun search(
            query: String,
            limit: Int,
        ): List<Card> {
            searchQueries += query
            val q = query.trim().lowercase()
            if (q.isEmpty()) return emptyList()
            return all.filter { it.name.lowercase().contains(q) }
        }

        override suspend fun cardsByName(names: Collection<String>): Map<String, Card> {
            nameLookups += names.toList()
            return all
                .filter { card -> names.any { it.equals(card.name, ignoreCase = true) } }
                .associateBy { it.name.lowercase() }
        }

        override suspend fun filter(
            criteria: CardFilter,
            limit: Int,
        ): List<Card> = emptyList()

        override suspend fun counts(): CatalogCounts = CatalogCounts(cardCount = all.size, printingCount = all.sumOf { it.printings.size })
    }
}
