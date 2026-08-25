package magefree.cards

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import magefree.cards.bundle.JvmBundledFiles
import magefree.cards.internal.CardCatalogDatabase
import magefree.cards.model.Card
import magefree.cards.model.CardColor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Coverage / sanity check over the **real bundled** `assets/cards.sqlite`. Exercises the actual
 * on-device path — [CardCatalogDatabase.open] copies the asset out of the (Robolectric-packaged) APK
 * assets and opens it read-only — then asserts the bundle is present at the right order of magnitude
 * and that well-known cards resolve with the expected fields. This is what proves the whole ~91.5k
 * printing bundle (not testable row-by-row) is real and correctly shaped.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CardCatalogBundleTest {
    private lateinit var db: SQLiteConnection
    private lateinit var catalog: CardCatalog

    @Before
    fun setUp() {
        db = CardCatalogDatabase.open(JvmBundledFiles(), BundledSQLiteDriver())
        catalog = CatalogTestSupport.catalog(db, UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `bundle has the expected order of magnitude of cards and printings`() =
        runTest {
            val counts = catalog.counts()
            // XMage 1.4.60: ~32k oracle cards / ~91.5k printings. Assert order-of-magnitude, not exact,
            // so a routine regeneration doesn't break the gate.
            assertTrue("cards=${counts.cardCount}", counts.cardCount in 25_000..45_000)
            assertTrue("printings=${counts.printingCount}", counts.printingCount in 80_000..110_000)
            assertTrue(counts.printingCount > counts.cardCount)
        }

    @Test
    fun `Lightning Bolt resolves with expected fields and many printings`() =
        runTest {
            val bolt = catalog.search("Lightning Bolt").first { it.name == "Lightning Bolt" }
            assertEquals("{R}", bolt.manaCost.symbols)
            assertEquals(1, bolt.manaValue)
            assertEquals(setOf(CardColor.RED), bolt.colors)
            assertTrue(bolt.typeLine.hasCardType("Instant"))
            assertTrue(bolt.rules.joinToString(" ").contains("3 damage"))
            // Reprinted heavily — a solid signal the printing table is fully populated.
            assertTrue("printings=${bolt.printings.size}", bolt.printings.size > 20)
        }

    @Test
    fun `a known double-faced card resolves`() =
        runTest {
            val delver = catalog.search("Delver of Secrets").first { it.name == "Delver of Secrets" }
            assertTrue(delver.faces.doubleFaced)
            assertEquals("Insectile Aberration", delver.faces.secondSideName)
        }

    @Test
    fun `a known split card resolves`() =
        runTest {
            val split = catalog.search("Fire // Ice").first { it.name == "Fire // Ice" }
            assertTrue(split.faces.splitCard)
        }

    @Test
    fun `every printing points at a real card (no orphans)`() {
        // The whole table, not a sample: an orphaned printing (a `card_id` with no `card` row) is
        // invisible through the catalog API — a card simply never shows the printing — so it can only
        // be caught by asking the database directly. Story 0044: the test previously named for this
        // check asserted only that Island had printings and was a Land, which cannot detect an orphan.
        val (printings, orphans) =
            db
                .prepare(
                    "SELECT COUNT(*), COUNT(CASE WHEN c.id IS NULL THEN 1 END) " +
                        "FROM printing p LEFT JOIN card c ON p.card_id = c.id",
                ).use { statement ->
                    assertTrue(statement.step())
                    statement.getLong(0) to statement.getLong(1)
                }

        // Counted in the same pass, so a "0 orphans" that only means "0 rows" cannot pass unnoticed.
        assertTrue("printings=$printings", printings > 80_000L)
        assertEquals("orphaned printings", 0L, orphans)
    }

    @Test
    fun `a broadly reprinted card resolves with its printings`() =
        runTest {
            val island: Card = catalog.search("Island").first { it.name == "Island" }
            assertTrue(island.printings.isNotEmpty())
            assertTrue(island.typeLine.hasCardType("Land"))
        }
}
