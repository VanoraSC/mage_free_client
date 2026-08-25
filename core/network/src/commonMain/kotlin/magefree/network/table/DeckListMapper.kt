package magefree.network.table

import magefree.decks.model.Deck
import magefree.decks.model.toDeckList
import magefree.protocol.DeckList
import magefree.protocol.DeckListCard
import magefree.decks.model.DeckList as DeviceDeckList
import magefree.decks.model.DeckListCard as DeviceDeckListCard

/**
 * The deck half of the table client's mapper boundary (story 0037): projects a 0033 device-side [Deck]
 * onto 0036's wire [DeckList] so a caller passes a domain deck to `join`/`submit`/`update` and never a
 * `:protocol` type.
 *
 * It reuses 0033's own [Deck.toDeckList] to get the interchange [DeviceDeckList] (`magefree.decks.model.DeckList`)
 * — the single place that drops app-only deck metadata (id/format/favorite/timestamps) — then copies its
 * field-aligned records onto the protocol [DeckList] (`magefree.protocol.DeckList`). The two `DeckList`
 * types are intentionally the same shape (both mirror XMage's `DeckCardLists`); they are distinct types
 * only because they live in different modules, hence the aliased imports here.
 */
internal fun Deck.toProtocolDeckList(): DeckList = toDeckList().toProtocol()

/** Copy the 0033 interchange [DeviceDeckList] onto the field-aligned wire [DeckList]. */
private fun DeviceDeckList.toProtocol(): DeckList =
    DeckList(
        name = name,
        author = author,
        cards = cards.map { it.toProtocol() },
        sideboard = sideboard.map { it.toProtocol() },
    )

private fun DeviceDeckListCard.toProtocol(): DeckListCard =
    DeckListCard(
        cardName = cardName,
        setCode = setCode,
        collectorNumber = collectorNumber,
        amount = amount,
    )
