package magefree.cards

import androidx.sqlite.SQLiteConnection
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import magefree.cards.model.CardColor
import magefree.cards.model.CardFilter
import magefree.cards.model.ColorMatch
import magefree.cards.model.Rarity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Deterministic search/filter/lookup behaviour, tested against a small committed fixture SQLite
 * (`src/test/resources/fixture-cards.sqlite`) carved from the real bundle. Covers name-search
 * ranking, every filter axis (color/type/mana/set/rarity/legality) and DFC/split/flip/planeswalker
 * lookups — the exact query logic that runs on device.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CardCatalogFixtureTest {
    private lateinit var db: SQLiteConnection
    private lateinit var catalog: CardCatalog
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        db = CatalogTestSupport.openResourceDatabase("fixture-cards.sqlite")
        catalog = CatalogTestSupport.catalog(db, dispatcher)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `search ranks prefix matches above word and substring matches`() =
        runTest {
            val names = catalog.search("lightning").map { it.name }
            // Prefix matches ("Lightning ...") come first, shorter name first.
            assertEquals(listOf("Lightning Bolt", "Lightning Helix"), names.take(2))
            // Word-boundary matches ("... Lightning") follow.
            assertTrue(names.indexOf("Ball Lightning") > names.indexOf("Lightning Helix"))
            assertTrue(names.contains("Chain Lightning"))
        }

    @Test
    fun `search ranks an exact match above a prefix match`() =
        runTest {
            val names = catalog.search("Fire").map { it.name }
            // "Fire" (exact) must sort before "Fire // Ice" (prefix).
            assertTrue(names.indexOf("Fire") < names.indexOf("Fire // Ice"))
        }

    @Test
    fun `blank search returns nothing`() =
        runTest {
            assertTrue(catalog.search("   ").isEmpty())
        }

    @Test
    fun `color filter ANY includes red cards and excludes off-color`() =
        runTest {
            val names = catalog.filter(CardFilter(colors = setOf(CardColor.RED))).map { it.name }
            assertTrue(names.contains("Lightning Bolt"))
            assertFalse(names.contains("Counterspell")) // mono-blue
            assertFalse(names.contains("Island")) // colorless land
        }

    @Test
    fun `color filter EXACT distinguishes mono from multicolor`() =
        runTest {
            val exactRed =
                catalog
                    .filter(
                        CardFilter(colors = setOf(CardColor.RED), colorMatch = ColorMatch.EXACT),
                    ).map { it.name }
            assertTrue(exactRed.contains("Lightning Bolt")) // mono-red
            assertFalse(exactRed.contains("Lightning Helix")) // red/white
        }

    @Test
    fun `type filter matches whole card-type tokens`() =
        runTest {
            val instants = catalog.filter(CardFilter(cardType = "Instant")).map { it.name }
            assertTrue(instants.contains("Lightning Bolt"))
            assertTrue(instants.contains("Counterspell"))
            assertFalse(instants.contains("Island"))
        }

    @Test
    fun `mana value filter is inclusive`() =
        runTest {
            val oneDrops = catalog.filter(CardFilter(manaValue = 1..1)).map { it.name }
            assertTrue(oneDrops.contains("Lightning Bolt"))
            assertFalse(oneDrops.contains("Jace, the Mind Sculptor")) // mana value 4
        }

    @Test
    fun `set and legality filters use the printing pool`() =
        runTest {
            val bolt = catalog.search("Lightning Bolt").first()
            val aSet = bolt.printings.first().setCode

            val bySet = catalog.filter(CardFilter(setCode = aSet)).map { it.name }
            assertTrue(bySet.contains("Lightning Bolt"))

            val legal = catalog.filter(CardFilter(legalSets = setOf(aSet))).map { it.name }
            assertTrue(legal.contains("Lightning Bolt"))
        }

    @Test
    fun `rarity filter matches a printed rarity`() =
        runTest {
            val commons = catalog.filter(CardFilter(rarity = Rarity.COMMON)).map { it.name }
            assertTrue(commons.contains("Lightning Bolt")) // printed common many times
            assertFalse(commons.contains("Jace, the Mind Sculptor")) // mythic only
        }

    @Test
    fun `double-faced card exposes its second side`() =
        runTest {
            val delver = catalog.search("Delver of Secrets").first { it.name == "Delver of Secrets" }
            assertTrue(delver.faces.doubleFaced)
            assertEquals("Insectile Aberration", delver.faces.secondSideName)
            assertTrue(delver.typeLine.hasCardType("Creature"))
        }

    @Test
    fun `split card is found by either half name`() =
        runTest {
            val split = catalog.search("Fire // Ice").first { it.name == "Fire // Ice" }
            assertTrue(split.faces.splitCard)
            // Searching a single half still surfaces the full split card.
            assertTrue(catalog.search("ice").any { it.name == "Fire // Ice" })
            assertTrue(catalog.search("fire").any { it.name == "Fire // Ice" })
        }

    @Test
    fun `flip card names its flipped side`() =
        runTest {
            val akki = catalog.search("Akki Lavarunner").first { it.name == "Akki Lavarunner" }
            assertTrue(akki.faces.flipCard)
            assertEquals("Tok-Tok, Volcano Born", akki.faces.flipCardName)
        }

    @Test
    fun `planeswalker carries starting loyalty`() =
        runTest {
            val jace = catalog.search("Jace, the Mind Sculptor").first()
            assertTrue(jace.typeLine.hasCardType("Planeswalker"))
            assertEquals("3", jace.loyalty)
        }

    @Test
    fun `card(id) round-trips a search result`() =
        runTest {
            val bolt = catalog.search("Lightning Bolt").first()
            val byId = catalog.card(bolt.id)
            assertNotNull(byId)
            assertEquals(bolt.name, byId!!.name)
            assertEquals(bolt.printings.size, byId.printings.size)
        }
}
