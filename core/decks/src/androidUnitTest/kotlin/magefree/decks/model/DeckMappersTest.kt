package magefree.decks.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The deck model must round-trip the `DeckCardLists`-equivalent [DeckList] losslessly (name, author,
 * ordered main + sideboard of card name / set code / collector number / amount) — the property the table layer
 * (submit) and import/export rely on. No `mage.*` types are involved.
 */
class DeckMappersTest {
    private val sampleList =
        DeckList(
            name = "Mono-Red Aggro",
            author = "pete",
            cards =
                listOf(
                    DeckListCard("Lightning Bolt", "M11", "149", 4),
                    DeckListCard("Mountain", "M11", "244", 20),
                    DeckListCard("Goblin Guide", "ZEN", "125", 4),
                ),
            sideboard =
                listOf(
                    DeckListCard("Smash to Smithereens", "M11", "153", 2),
                ),
        )

    @Test
    fun `DeckList round-trips through Deck losslessly`() {
        val deck = sampleList.toDeck(id = DeckId("id-1"))
        val back = deck.toDeckList()

        assertEquals(sampleList, back)
    }

    @Test
    fun `Deck interchange fields survive a DeckList round-trip`() {
        val deck =
            Deck(
                id = DeckId("id-2"),
                name = "Test",
                author = "author",
                format = DeckFormat.MODERN,
                main = listOf(DeckEntry("Island", "MH2", "1", 24)),
                sideboard = listOf(DeckEntry("Negate", "MH2", "2", 3)),
                favorite = true,
                createdAt = 100L,
                updatedAt = 200L,
            )

        val rebuilt =
            deck.toDeckList().toDeck(
                id = deck.id,
                format = deck.format,
                favorite = deck.favorite,
                createdAt = deck.createdAt,
                updatedAt = deck.updatedAt,
            )

        assertEquals(deck, rebuilt)
    }

    @Test
    fun `entry quantity maps to DeckCardInfo amount and back`() {
        val entry = DeckEntry("Ragavan, Nimble Pilferer", "MH2", "138", 4)
        val deck = Deck(id = DeckId("id-3"), name = "n", main = listOf(entry))

        val listCard = deck.toDeckList().cards.single()
        assertEquals(4, listCard.amount)
        assertEquals("138", listCard.collectorNumber)

        val roundTripped =
            deck
                .toDeckList()
                .toDeck(id = deck.id)
                .main
                .single()
        assertEquals(entry, roundTripped)
    }
}
