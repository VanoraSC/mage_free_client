package magefree.bridge.mapping

import mage.cards.decks.DeckCardInfo
import mage.cards.decks.DeckCardLists
import magefree.protocol.DeckList
import magefree.protocol.DeckListCard

/**
 * Maps the app-schema [magefree.protocol.DeckList] to/from XMage's `mage.cards.decks.DeckCardLists`
 * (with per-card `DeckCardInfo`). Part of the **single coupling surface**: `mage.cards.decks.*` is
 * constructed/read only here (and its sibling mappers), so a `DeckCardLists` never crosses the wire —
 * `JoinTable`/`SubmitDeck`/`UpdateDeck` carry the pure [DeckList] and the bridge builds the real deck
 * at this boundary. Pure and deterministic — no I/O or hidden state.
 *
 * This is the bridge-side reverse of the device-side `Deck ↔ DeckList` mapper: the two
 * `DeckList` shapes are field-aligned (`name`/`author`/`cards`/`sideboard`; each card `cardName`/
 * `setCode`/`collectorNumber`/`amount`) so table can hand a device deck straight through.
 *
 * Field mapping (see [DeckCardInfo]'s `cardName`/`cardNumber`/`setCode`/`amount`):
 * - [DeckListCard.collectorNumber] ↔ `DeckCardInfo.cardNumber`.
 * - [DeckListCard.amount] ↔ `DeckCardInfo.amount`.
 */
public object DeckListMapper {
    /**
     * Builds an XMage [DeckCardLists] from the app-schema [deck]. The layout fields are intentionally
     * left null — `SessionImpl.submitDeck`/`joinTable` clear them anyway (the "can't join table"
     * workaround), and the app carries no layout.
     */
    public fun toDeckCardLists(deck: DeckList): DeckCardLists =
        DeckCardLists().apply {
            name = deck.name
            author = deck.author
            cards = deck.cards.map { infoOf(it) }.toMutableList()
            sideboard = deck.sideboard.map { infoOf(it) }.toMutableList()
        }

    /** Maps the app-schema card [card] to a `DeckCardInfo` (note the `cardNumber` ← `collectorNumber`). */
    private fun infoOf(card: DeckListCard): DeckCardInfo = DeckCardInfo(card.cardName, card.collectorNumber, card.setCode, card.amount)

    /**
     * Projects an XMage [DeckCardLists] back to the app-schema [DeckList] — the reverse of
     * [toDeckCardLists], used to keep the mapping honestly round-trippable in tests (and available for
     * any future upstream-deck relay).
     */
    public fun toDeckList(lists: DeckCardLists): DeckList =
        DeckList(
            name = lists.name,
            author = lists.author,
            cards = lists.cards.orEmpty().map { cardOf(it) },
            sideboard = lists.sideboard.orEmpty().map { cardOf(it) },
        )

    /** Projects a `DeckCardInfo` back to the app-schema [DeckListCard]. */
    private fun cardOf(info: DeckCardInfo): DeckListCard =
        DeckListCard(
            cardName = info.cardName ?: "",
            setCode = info.setCode ?: "",
            collectorNumber = info.cardNumber ?: "",
            amount = info.amount,
        )
}
