package magefree.cards

import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import magefree.cards.internal.CardCatalogDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The exact-name catalog lookup (story 0042, defect D) over the **real bundled** database, opened the
 * way the device opens it.
 *
 * This is where the "indexable" claim is actually checked: `EXPLAIN QUERY PLAN` must show SQLite
 * resolving the predicate through the case-insensitive name index rather than scanning the ~32k-row
 * `card` table. The generator only ships a BINARY `idx_card_name`, which a NOCASE comparison cannot
 * use, so [CardCatalogDatabase] adds the NOCASE index to its private copy at copy time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class CardCatalogExactNameTest {
    private lateinit var db: SQLiteDatabase
    private lateinit var catalog: CardCatalog

    @Before
    fun setUp() {
        db = CardCatalogDatabase.open(RuntimeEnvironment.getApplication())
        catalog = CatalogTestSupport.catalog(db, UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `prepared copy has the nocase name index`() {
        val indexes = mutableListOf<String>()
        db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'card'", null).use { c ->
            while (c.moveToNext()) indexes += c.getString(0)
        }
        assertTrue(indexes.toString(), indexes.contains(CardCatalogDatabase.NAME_NOCASE_INDEX))
        // The bundle's own indexes are untouched.
        assertTrue(indexes.toString(), indexes.contains("idx_card_name"))
    }

    @Test
    fun `exact-name plan uses the index`() {
        val plan = queryPlan("SELECT id FROM card WHERE name COLLATE NOCASE IN (?, ?)", "Lightning Bolt", "Island")
        // SEARCH (an index seek per value) rather than SCAN (every row) is the whole point of defect D.
        assertTrue(plan, plan.contains("SEARCH"))
        assertTrue(plan, plan.contains(CardCatalogDatabase.NAME_NOCASE_INDEX))
        assertFalse("the exact-name lookup must not scan the card table: $plan", plan.contains("SCAN"))
    }

    @Test
    fun `substring search still scans`() {
        // Documents *why* exact-name needed its own method: a leading-wildcard LIKE cannot be answered
        // by an index seek, so running it per card name is what made every builder edit expensive.
        val plan = queryPlan("SELECT id, name FROM card WHERE name LIKE ? ESCAPE '\\'", "%bolt%")
        assertTrue(plan, plan.contains("SCAN"))
        assertFalse(plan, plan.contains("SEARCH"))
    }

    @Test
    fun `batch resolves case-insensitively`() =
        runTest {
            val resolved = catalog.cardsByName(listOf("Lightning Bolt", "iSlAnD", "Fire // Ice"))

            assertEquals(3, resolved.size)
            assertEquals("Lightning Bolt", resolved.getValue("lightning bolt").name)
            assertEquals("Island", resolved.getValue("island").name)
            assertEquals("Fire // Ice", resolved.getValue("fire // ice").name)
            // Printings are attached, exactly as the substring search attaches them.
            assertTrue(
                resolved
                    .getValue("lightning bolt")
                    .printings.size > 20,
            )
        }

    @Test
    fun `batch does not match substrings`() =
        runTest {
            // "Bolt" is a substring of many cards; search finds them, the exact lookup must not.
            assertTrue(catalog.search("Bolt").size > 1)
            assertTrue(catalog.cardsByName(listOf("Bolt")).isEmpty())
        }

    @Test
    fun `batch ignores blanks and dupes`() =
        runTest {
            val resolved = catalog.cardsByName(listOf("Island", "island", "  ", "", "ISLAND"))
            assertEquals(1, resolved.size)
            assertNotNull(resolved["island"])
            assertTrue(catalog.cardsByName(emptyList()).isEmpty())
        }

    @Test
    fun `single-name convenience works`() =
        runTest {
            assertEquals("Lightning Bolt", catalog.cardByName("lightning bolt")?.name)
            assertNull(catalog.cardByName("Not A Real Card Name At All"))
        }

    @Test
    fun `a deck of names resolves in one call`() =
        runTest {
            // A realistic builder rebuild: ~20 distinct names. Before the fix this was one full
            // leading-wildcard scan per name (x3 queries each); it is now one IN(...) statement plus
            // the card + printing loads.
            val names =
                listOf(
                    "Lightning Bolt", "Island", "Mountain", "Forest", "Plains", "Swamp",
                    "Counterspell", "Brainstorm", "Dark Ritual", "Giant Growth",
                )
            val resolved = catalog.cardsByName(names)
            assertEquals(names.size, resolved.size)
        }

    /** The `EXPLAIN QUERY PLAN` rows for [sql], flattened to one string. */
    private fun queryPlan(
        sql: String,
        vararg args: String,
    ): String =
        buildString {
            db.rawQuery("EXPLAIN QUERY PLAN $sql", arrayOf(*args)).use { c ->
                while (c.moveToNext()) {
                    append(c.getString(c.getColumnIndexOrThrow("detail"))).append('\n')
                }
            }
        }
}
