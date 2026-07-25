package magefree.designsystem.card

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for the pure logic of the card display model: the accessibility-summary builder and the
 * [CardDisplay.accessibleSummary] override behavior. The composables themselves are verified via previews.
 */
class CardDisplayTest {
    @Test
    fun `summary is just the name when no other fields are present`() {
        assertEquals(
            "Lightning Bolt",
            buildCardContentDescription(CardDisplay(name = "Lightning Bolt")),
        )
    }

    @Test
    fun `summary joins name mana cost and type line`() {
        assertEquals(
            "Grizzly Bears, mana cost 1G, Creature — Bear",
            buildCardContentDescription(
                CardDisplay(name = "Grizzly Bears", manaCost = "1G", typeLine = "Creature — Bear"),
            ),
        )
    }

    @Test
    fun `summary omits blank mana cost and type line`() {
        assertEquals(
            "Nameless Token",
            buildCardContentDescription(
                CardDisplay(name = "Nameless Token", manaCost = "   ", typeLine = ""),
            ),
        )
    }

    @Test
    fun `summary excludes oracle text by default`() {
        assertEquals(
            "Lightning Bolt, Instant",
            buildCardContentDescription(
                CardDisplay(
                    name = "Lightning Bolt",
                    typeLine = "Instant",
                    oracleText = "Deal 3 damage to any target.",
                ),
            ),
        )
    }

    @Test
    fun `summary includes oracle text when requested`() {
        assertEquals(
            "Lightning Bolt, Instant. Deal 3 damage to any target.",
            buildCardContentDescription(
                CardDisplay(
                    name = "Lightning Bolt",
                    typeLine = "Instant",
                    oracleText = "Deal 3 damage to any target.",
                ),
                includeOracleText = true,
            ),
        )
    }

    @Test
    fun `accessibleSummary uses the explicit content description override`() {
        val card =
            CardDisplay(
                name = "Grizzly Bears",
                manaCost = "1G",
                contentDescription = "A 2/2 bear for two mana",
            )
        assertEquals("A 2/2 bear for two mana", card.accessibleSummary)
    }

    @Test
    fun `accessibleSummary falls back to the built summary`() {
        val card = CardDisplay(name = "Grizzly Bears", manaCost = "1G")
        assertEquals("Grizzly Bears, mana cost 1G", card.accessibleSummary)
    }
}
