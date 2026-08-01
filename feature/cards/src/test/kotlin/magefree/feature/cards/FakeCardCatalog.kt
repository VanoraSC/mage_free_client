package magefree.feature.cards

import magefree.cards.CardCatalog
import magefree.cards.model.Card
import magefree.cards.model.CardColor
import magefree.cards.model.CardFaces
import magefree.cards.model.CardFilter
import magefree.cards.model.CardId
import magefree.cards.model.CardPrinting
import magefree.cards.model.CatalogCounts
import magefree.cards.model.ManaCost
import magefree.cards.model.Rarity
import magefree.cards.model.TypeLine

/**
 * A scriptable in-memory [CardCatalog] for hermetic ViewModel tests — no SQLite, no device. It records
 * every [search] query and [filter] criteria so tests can assert the debounce/filter behavior, and
 * returns configurable result lists.
 */
class FakeCardCatalog(
    private val byId: Map<CardId, Card> = emptyMap(),
    private val searchResult: (String) -> List<Card> = { emptyList() },
    private val filterResult: (CardFilter) -> List<Card> = { emptyList() },
) : CardCatalog {
    val searchQueries: MutableList<String> = mutableListOf()
    val filterCriteria: MutableList<CardFilter> = mutableListOf()

    override suspend fun card(id: CardId): Card? = byId[id]

    override suspend fun search(
        query: String,
        limit: Int,
    ): List<Card> {
        searchQueries += query
        return searchResult(query)
    }

    override suspend fun filter(
        criteria: CardFilter,
        limit: Int,
    ): List<Card> {
        filterCriteria += criteria
        return filterResult(criteria)
    }

    override suspend fun counts(): CatalogCounts = CatalogCounts(cardCount = byId.size, printingCount = byId.size)
}

/** Build a minimal oracle [Card] for tests. */
fun testCard(
    id: Long,
    name: String,
    manaCost: String = "",
    manaValue: Int = 0,
    colors: Set<CardColor> = emptySet(),
    cardTypes: List<String> = listOf("Creature"),
    subTypes: List<String> = emptyList(),
    rules: List<String> = emptyList(),
    power: String? = null,
    toughness: String? = null,
    loyalty: String? = null,
    faces: CardFaces = CardFaces(),
    printings: List<CardPrinting> = listOf(CardPrinting(setCode = "TST", collectorNumber = id.toString(), rarity = Rarity.COMMON)),
): Card =
    Card(
        id = CardId(id),
        name = name,
        manaCost = ManaCost(manaCost),
        manaValue = manaValue,
        colors = colors,
        typeLine = TypeLine(superTypes = emptyList(), cardTypes = cardTypes, subTypes = subTypes),
        rules = rules,
        power = power,
        toughness = toughness,
        loyalty = loyalty,
        faces = faces,
        printings = printings,
    )
