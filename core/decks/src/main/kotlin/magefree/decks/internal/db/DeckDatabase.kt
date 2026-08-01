package magefree.decks.internal.db

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * The on-device deck library database. Fully local — decks are the user's data, persisted on-device;
 * no operation here touches the network. `exportSchema = false`: the catalog is a simple app-owned
 * store with no committed schema history to validate against yet.
 */
@Database(
    entities = [DeckEntity::class, DeckCardEntity::class],
    version = 1,
    exportSchema = false,
)
internal abstract class DeckDatabase : RoomDatabase() {
    abstract fun deckDao(): DeckDao

    companion object {
        const val NAME = "decks.db"
    }
}
