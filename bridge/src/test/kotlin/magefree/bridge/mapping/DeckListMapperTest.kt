package magefree.bridge.mapping

import magefree.protocol.DeckList
import magefree.protocol.DeckListCard
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Hermetic tests for [DeckListMapper]. `mage.cards.decks.DeckCardLists`/`DeckCardInfo` are plain,
 * field-constructor `mage-common` types (like the `ChatMessage`), so the mapper is exercised with
 * real in-memory fixtures round-tripping protocol [DeckList] → `DeckCardLists` → protocol [DeckList].
 */
class DeckListMapperTest {
    private val sample =
        DeckList(
            name = "Mono-U Tempo",
            author = "alice",
            cards =
                listOf(
                    DeckListCard(cardName = "Island", setCode = "M21", collectorNumber = "123", amount = 20),
                    DeckListCard(cardName = "Brineborn Cutthroat", setCode = "M21", collectorNumber = "50", amount = 4),
                ),
            sideboard =
                listOf(
                    DeckListCard(cardName = "Negate", setCode = "M21", collectorNumber = "58", amount = 3),
                ),
        )

    @Test
    fun `toDeckCardLists carries name, author, and every card field (collectorNumber to cardNumber)`() {
        val lists = DeckListMapper.toDeckCardLists(sample)

        assertEquals("Mono-U Tempo", lists.name)
        assertEquals("alice", lists.author)
        assertEquals(2, lists.cards.size)
        val first = lists.cards[0]
        assertEquals("Island", first.cardName)
        assertEquals("M21", first.setCode)
        // The app-schema collectorNumber maps onto DeckCardInfo.cardNumber.
        assertEquals("123", first.cardNumber)
        assertEquals(20, first.amount)
        assertEquals(1, lists.sideboard.size)
        assertEquals("Negate", lists.sideboard[0].cardName)
        assertEquals(3, lists.sideboard[0].amount)
    }

    @Test
    fun `a deck round-trips through DeckCardLists and back unchanged`() {
        val roundTripped = DeckListMapper.toDeckList(DeckListMapper.toDeckCardLists(sample))
        assertEquals(sample, roundTripped)
    }

    @Test
    fun `an empty deck maps to an empty DeckCardLists and back`() {
        val empty = DeckList()
        val roundTripped = DeckListMapper.toDeckList(DeckListMapper.toDeckCardLists(empty))
        assertEquals(empty, roundTripped)
    }
}
