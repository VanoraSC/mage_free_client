package magefree.decks.internal.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * Data-access for the local deck library. All reads are local SQLite queries; the library list is a
 * reactive [Flow] Room re-emits on any change. Multi-table writes (header + card lines) are wrapped in
 * [Transaction] so a deck is never half-written.
 */
@Dao
internal interface DeckDao {
    /**
     * The library, ordered favorites-first then most-recently-updated, with main/sideboard card counts
     * aggregated in SQL so the list needs no card rows. Re-emits whenever any deck or card row changes.
     */
    @Query(
        """
        SELECT d.id AS id, d.name AS name, d.author AS author, d.format AS format,
               d.favorite AS favorite, d.createdAt AS createdAt, d.updatedAt AS updatedAt,
               COALESCE((SELECT SUM(c.quantity) FROM deck_cards c
                         WHERE c.deckId = d.id AND c.zone = 'MAIN'), 0) AS mainCount,
               COALESCE((SELECT SUM(c.quantity) FROM deck_cards c
                         WHERE c.deckId = d.id AND c.zone = 'SIDEBOARD'), 0) AS sideboardCount
        FROM decks d
        ORDER BY d.favorite DESC, d.updatedAt DESC, d.name COLLATE NOCASE ASC
        """,
    )
    fun observeSummaries(): Flow<List<DeckSummaryRow>>

    @Transaction
    @Query("SELECT * FROM decks WHERE id = :id")
    suspend fun findDeck(id: String): DeckWithCards?

    @Insert
    suspend fun insertDeck(deck: DeckEntity)

    @Insert
    suspend fun insertCards(cards: List<DeckCardEntity>)

    @Query("DELETE FROM deck_cards WHERE deckId = :deckId")
    suspend fun deleteCards(deckId: String)

    @Query("DELETE FROM decks WHERE id = :id")
    suspend fun deleteDeck(id: String)

    @Query("UPDATE decks SET name = :name, updatedAt = :updatedAt WHERE id = :id")
    suspend fun rename(
        id: String,
        name: String,
        updatedAt: Long,
    )

    @Query("UPDATE decks SET favorite = :favorite, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setFavorite(
        id: String,
        favorite: Boolean,
        updatedAt: Long,
    )

    /** Replace a deck's header + all card lines atomically (the save path). */
    @Transaction
    suspend fun upsertDeck(
        deck: DeckEntity,
        cards: List<DeckCardEntity>,
    ) {
        deleteCards(deck.id)
        deleteDeck(deck.id)
        insertDeck(deck)
        insertCards(cards)
    }
}
