package magefree.feature.game.board

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

/**
 * A scriptable in-memory [CardCatalog] for [GameBoardViewModel]'s flip-detection tests - no
 * SQLite, no device. Only [cardByName] (via the interface's default, over [cardsByName]) is exercised;
 * the rest exist to satisfy the interface with harmless empty answers.
 */
class FakeCardCatalog(
    private val byName: Map<String, Card> = emptyMap(),
) : CardCatalog {
    override suspend fun card(id: CardId): Card? = null

    override suspend fun search(
        query: String,
        limit: Int,
    ): List<Card> = emptyList()

    override suspend fun cardsByName(names: Collection<String>): Map<String, Card> =
        byName.filterKeys { key -> names.any { it.equals(key, ignoreCase = true) } }.mapKeys { it.key.lowercase() }

    override suspend fun filter(
        criteria: CardFilter,
        limit: Int,
    ): List<Card> = emptyList()

    override suspend fun counts(): CatalogCounts = CatalogCounts(cardCount = byName.size, printingCount = byName.size)
}

/** Build a minimal oracle [Card] for tests, keyed by [name] as the catalog would store it. */
fun testCard(
    name: String,
    faces: CardFaces = CardFaces(),
): Card =
    Card(
        id = CardId(1),
        name = name,
        manaCost = ManaCost(""),
        manaValue = 0,
        colors = emptySet(),
        typeLine = TypeLine(superTypes = emptyList(), cardTypes = listOf("Creature"), subTypes = emptyList()),
        rules = emptyList(),
        faces = faces,
        printings = listOf(CardPrinting(setCode = "TST", collectorNumber = "1", rarity = Rarity.COMMON)),
    )
