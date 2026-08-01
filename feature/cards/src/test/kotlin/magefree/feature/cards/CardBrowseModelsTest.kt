package magefree.feature.cards

import magefree.cards.art.CardArtFace
import magefree.cards.model.CardColor
import magefree.cards.model.Rarity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure/mapping coverage of [CardRow]/[CardDisplay] mapping and [CardBrowseFilters] -> [CardFilter]. */
class CardBrowseModelsTest {
    @Test
    fun toCardRowMapsDisplayFieldsAndFrontArt() {
        val card =
            testCard(
                1,
                "Lightning Bolt",
                manaCost = "{R}",
                cardTypes = listOf("Instant"),
                rules = listOf("Deal 3 damage to any target."),
            )
        val row = card.toCardRow()

        assertEquals("Lightning Bolt", row.display.name)
        assertEquals("{R}", row.display.manaCost)
        assertEquals("Instant", row.display.typeLine)
        assertEquals("Deal 3 damage to any target.", row.display.oracleText)
        assertEquals("TST", row.setLabel)
        assertEquals(CardArtFace.FRONT, row.artRequest?.face)
    }

    @Test
    fun emptyManaAndRulesMapToNull() {
        val card = testCard(2, "Forest", manaCost = "", cardTypes = listOf("Land"), rules = emptyList())
        val display = card.toCardDisplay()

        assertNull(display.manaCost)
        assertNull(display.oracleText)
        assertEquals("Land", display.typeLine)
    }

    @Test
    fun filtersFoldIntoConjunctiveCardFilter() {
        val filters =
            CardBrowseFilters(
                colors = setOf(CardColor.RED, CardColor.WHITE),
                cardType = "Creature",
                manaValue = 3,
                rarity = Rarity.MYTHIC,
                setCode = "DOM",
            )
        val filter = filters.toCardFilter("bolt")

        assertTrue(filters.isActive)
        assertEquals("bolt", filter.nameQuery)
        assertEquals(setOf(CardColor.RED, CardColor.WHITE), filter.colors)
        assertEquals("Creature", filter.cardType)
        assertEquals(3..3, filter.manaValue)
        assertEquals(Rarity.MYTHIC, filter.rarity)
        assertEquals("DOM", filter.setCode)
    }

    @Test
    fun topManaBucketIsOpenEnded() {
        val filter = CardBrowseFilters(manaValue = MANA_VALUE_MAX_BUCKET).toCardFilter("")
        assertEquals(MANA_VALUE_MAX_BUCKET..Int.MAX_VALUE, filter.manaValue)
        assertNull(filter.nameQuery)
    }

    @Test
    fun emptyFiltersAreInactive() {
        assertFalse(CardBrowseFilters().isActive)
    }
}
