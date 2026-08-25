package magefree.decks.internal.db

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor

/**
 * The on-device deck library database. Fully local — decks are the user's data, persisted on-device;
 * no operation here touches the network. `exportSchema = false`: the catalog is a simple app-owned
 * store with no committed schema history to validate against yet.
 *
 * [DeckDatabaseConstructor] is how Room instantiates this class without reflection, which is what
 * makes the class usable from a common source set (story 0083).
 */
@Database(
    entities = [DeckEntity::class, DeckCardEntity::class],
    version = 1,
    exportSchema = false,
)
@ConstructedBy(DeckDatabaseConstructor::class)
internal abstract class DeckDatabase : RoomDatabase() {
    abstract fun deckDao(): DeckDao

    companion object {
        const val NAME = "decks.db"
    }
}

/**
 * Room's generated instantiator for [DeckDatabase].
 *
 * The Android-only build reached the generated `DeckDatabase_Impl` by reflection from
 * `DeckDatabase::class.java`. Common code has no `Class`, so Room 2.7 replaces that lookup with an
 * `expect object` whose `actual` it generates per target — `@ConstructedBy` on the database points at
 * this one.
 *
 * `NO_ACTUAL_FOR_EXPECT` is suppressed because the `actual`s are produced by KSP during compilation,
 * after the compiler has already checked that every `expect` has one.
 */
@Suppress("NO_ACTUAL_FOR_EXPECT", "KotlinNoActualForExpect")
internal expect object DeckDatabaseConstructor : RoomDatabaseConstructor<DeckDatabase> {
    override fun initialize(): DeckDatabase
}
