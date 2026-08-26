package magefree.decks.model

/**
 * The `DeckCardLists`-equivalent interchange type: a plain, app-schema mirror of XMage's
 * `mage.cards.decks.DeckCardLists` (`name`, `author`, `cards`, `sideboard`) and `DeckCardInfo`
 * (`cardName`, `setCode`, `cardNumber`, `amount`). This is the shared representation that import/export
 * (import/export) and the table layer (submit-to-table) consume, so all three agree on deck identity by
 * card name + set code + collector number.
 *
 * It carries only the fields XMage's contract carries — no app-only library metadata (id, favorite,
 * timestamps, format) — so mapping is lossless in both directions for those fields (see
 * [magefree.decks.model.toDeck] / [magefree.decks.model.toDeckList]). No `mage.*` type is involved.
 */
data class DeckList(
    val name: String?,
    val author: String?,
    val cards: List<DeckListCard> = emptyList(),
    val sideboard: List<DeckListCard> = emptyList(),
)

/**
 * One card record in a [DeckList], mirroring XMage's `DeckCardInfo`. [collectorNumber] corresponds to
 * `DeckCardInfo.cardNumber`; [amount] to `DeckCardInfo.amount`.
 */
data class DeckListCard(
    val cardName: String,
    val setCode: String,
    val collectorNumber: String,
    val amount: Int,
)
