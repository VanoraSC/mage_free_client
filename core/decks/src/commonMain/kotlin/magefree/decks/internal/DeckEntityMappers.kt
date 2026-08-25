package magefree.decks.internal

import magefree.decks.internal.db.DeckCardEntity
import magefree.decks.internal.db.DeckEntity
import magefree.decks.internal.db.DeckSummaryRow
import magefree.decks.internal.db.DeckWithCards
import magefree.decks.model.Deck
import magefree.decks.model.DeckEntry
import magefree.decks.model.DeckFormat
import magefree.decks.model.DeckId
import magefree.decks.model.DeckSummary
import magefree.decks.model.DeckZone

/*
 * Mapping between the Room entities and the app-schema deck model. Kept internal to `:core:decks`;
 * the persistence shape never leaks out of the repository.
 */

internal fun DeckWithCards.toDeck(): Deck {
    val main = cards.filter { it.zone == DeckZone.MAIN.name }.sortedBy { it.position }.map { it.toEntry() }
    val side = cards.filter { it.zone == DeckZone.SIDEBOARD.name }.sortedBy { it.position }.map { it.toEntry() }
    return Deck(
        id = DeckId(deck.id),
        name = deck.name,
        author = deck.author,
        format = DeckFormat.fromKey(deck.format),
        main = main,
        sideboard = side,
        favorite = deck.favorite,
        createdAt = deck.createdAt,
        updatedAt = deck.updatedAt,
    )
}

internal fun Deck.toEntity(): DeckEntity =
    DeckEntity(
        id = id.value,
        name = name,
        author = author,
        format = format?.key,
        favorite = favorite,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

internal fun Deck.toCardEntities(): List<DeckCardEntity> {
    val rows = ArrayList<DeckCardEntity>(main.size + sideboard.size)
    main.forEachIndexed { i, e -> rows += e.toCardEntity(id.value, DeckZone.MAIN, i) }
    sideboard.forEachIndexed { i, e -> rows += e.toCardEntity(id.value, DeckZone.SIDEBOARD, i) }
    return rows
}

internal fun DeckSummaryRow.toSummary(): DeckSummary =
    DeckSummary(
        id = DeckId(id),
        name = name,
        author = author,
        format = DeckFormat.fromKey(format),
        favorite = favorite,
        mainCount = mainCount,
        sideboardCount = sideboardCount,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

private fun DeckCardEntity.toEntry(): DeckEntry =
    DeckEntry(
        cardName = cardName,
        setCode = setCode,
        collectorNumber = collectorNumber,
        quantity = quantity,
    )

private fun DeckEntry.toCardEntity(
    deckId: String,
    zone: DeckZone,
    position: Int,
): DeckCardEntity =
    DeckCardEntity(
        deckId = deckId,
        zone = zone.name,
        position = position,
        cardName = cardName,
        setCode = setCode,
        collectorNumber = collectorNumber,
        quantity = quantity,
    )
