package magefree.decks.internal

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import magefree.decks.DeckRepository
import magefree.decks.internal.db.DeckDao
import magefree.decks.model.Deck
import magefree.decks.model.DeckFormat
import magefree.decks.model.DeckId
import magefree.decks.model.DeckSummary
import java.util.UUID

/**
 * [DeckRepository] over the local Room database. All work runs on the injected [ioDispatcher]; the
 * library [Flow] is produced by Room and mapped off that dispatcher. Nothing here touches the network.
 *
 * [now] and [newId] are injected so persistence tests get deterministic timestamps and ids.
 */
internal class RoomDeckRepository(
    private val dao: DeckDao,
    private val ioDispatcher: CoroutineDispatcher,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : DeckRepository {
    override fun observeLibrary(): Flow<List<DeckSummary>> =
        dao
            .observeSummaries()
            .map { rows -> rows.map { it.toSummary() } }
            .flowOn(ioDispatcher)

    override suspend fun load(id: DeckId): Deck? =
        withContext(ioDispatcher) {
            dao.findDeck(id.value)?.toDeck()
        }

    override suspend fun create(
        name: String,
        format: DeckFormat?,
    ): Deck =
        withContext(ioDispatcher) {
            val ts = now()
            val deck =
                Deck(
                    id = DeckId(newId()),
                    name = name,
                    format = format,
                    createdAt = ts,
                    updatedAt = ts,
                )
            dao.upsertDeck(deck.toEntity(), deck.toCardEntities())
            deck
        }

    override suspend fun save(deck: Deck) {
        withContext(ioDispatcher) {
            val updated = deck.copy(updatedAt = now())
            dao.upsertDeck(updated.toEntity(), updated.toCardEntities())
        }
    }

    override suspend fun duplicate(id: DeckId): Deck? =
        withContext(ioDispatcher) {
            val source = dao.findDeck(id.value)?.toDeck() ?: return@withContext null
            val ts = now()
            val copy =
                source.copy(
                    id = DeckId(newId()),
                    name = "${source.name} (copy)",
                    favorite = false,
                    createdAt = ts,
                    updatedAt = ts,
                )
            dao.upsertDeck(copy.toEntity(), copy.toCardEntities())
            copy
        }

    override suspend fun rename(
        id: DeckId,
        name: String,
    ) {
        withContext(ioDispatcher) { dao.rename(id.value, name, now()) }
    }

    override suspend fun delete(id: DeckId) {
        withContext(ioDispatcher) { dao.deleteDeck(id.value) }
    }

    override suspend fun setFavorite(
        id: DeckId,
        favorite: Boolean,
    ) {
        withContext(ioDispatcher) { dao.setFavorite(id.value, favorite, now()) }
    }
}
