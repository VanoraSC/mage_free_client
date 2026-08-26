package magefree.decks.model

/**
 * A lightweight library-list view of a deck: header metadata plus main/sideboard card totals, without
 * loading the deck's card lines. This is what [magefree.decks.DeckRepository.observeLibrary] emits for
 * the deck-list screen.
 */
data class DeckSummary(
    val id: DeckId,
    val name: String,
    val author: String?,
    val format: DeckFormat?,
    val favorite: Boolean,
    val mainCount: Int,
    val sideboardCount: Int,
    val createdAt: Long,
    val updatedAt: Long,
)
