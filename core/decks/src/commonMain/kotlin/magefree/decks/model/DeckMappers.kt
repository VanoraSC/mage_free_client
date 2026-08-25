package magefree.decks.model

/*
 * Lossless mappers between the app-schema [Deck] and the `DeckCardLists`-equivalent [DeckList]. The
 * mapped fields — name, author, and the ordered main/sideboard entries (card name + set code +
 * collector number + amount/quantity) — are exactly XMage's `DeckCardLists`/`DeckCardInfo` fields, so
 * a round-trip in either direction preserves them. App-only metadata (id, format, favorite,
 * timestamps) is not part of the interchange contract and is supplied when rebuilding a [Deck].
 */

/** Project a [Deck] onto the `DeckCardLists`-equivalent interchange type (drops app-only metadata). */
fun Deck.toDeckList(): DeckList =
    DeckList(
        name = name,
        author = author,
        cards = main.map { it.toListCard() },
        sideboard = sideboard.map { it.toListCard() },
    )

/**
 * Rebuild a [Deck] from a [DeckList], supplying the app-only library metadata the interchange type
 * does not carry. [name] falls back to the deck's own name (or a blank string) when the list has none.
 */
fun DeckList.toDeck(
    id: DeckId,
    format: DeckFormat? = null,
    favorite: Boolean = false,
    createdAt: Long = 0L,
    updatedAt: Long = 0L,
): Deck =
    Deck(
        id = id,
        name = name.orEmpty(),
        author = author,
        format = format,
        main = cards.map { it.toEntry() },
        sideboard = sideboard.map { it.toEntry() },
        favorite = favorite,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

private fun DeckEntry.toListCard(): DeckListCard =
    DeckListCard(
        cardName = cardName,
        setCode = setCode,
        collectorNumber = collectorNumber,
        amount = quantity,
    )

private fun DeckListCard.toEntry(): DeckEntry =
    DeckEntry(
        cardName = cardName,
        setCode = setCode,
        collectorNumber = collectorNumber,
        quantity = amount,
    )
