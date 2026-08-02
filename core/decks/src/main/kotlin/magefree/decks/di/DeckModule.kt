package magefree.decks.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import magefree.cards.CardCatalog
import magefree.decks.DeckRepository
import magefree.decks.internal.DefaultDeckLegality
import magefree.decks.internal.FormatBundleLoader
import magefree.decks.internal.RoomDeckRepository
import magefree.decks.internal.db.DeckDatabase
import magefree.decks.io.DeckIO
import magefree.decks.io.internal.DefaultDeckIO
import magefree.decks.legality.DeckLegality
import javax.inject.Singleton

/**
 * Hilt provisioning for `:core:decks`. Exposes only the public [DeckRepository] (fully-offline deck
 * library, Room-backed) and [DeckLegality] (offline checker over the bundled `formats.json` + the
 * `:core:cards` [CardCatalog]); the Room database/DAO stay internal to the module and never enter the
 * Dagger graph. `:app` gets both working just by depending on this module — no network involved.
 */
@Module
@InstallIn(SingletonComponent::class)
object DeckModule {
    @Provides
    @Singleton
    fun provideDeckRepository(
        @ApplicationContext context: Context,
    ): DeckRepository {
        val database =
            Room
                .databaseBuilder(context, DeckDatabase::class.java, DeckDatabase.NAME)
                .build()
        return RoomDeckRepository(dao = database.deckDao(), ioDispatcher = Dispatchers.IO)
    }

    @Provides
    @Singleton
    fun provideDeckLegality(
        @ApplicationContext context: Context,
        catalog: CardCatalog,
    ): DeckLegality =
        DefaultDeckLegality(
            bundleProvider = { FormatBundleLoader.load(context) },
            catalog = catalog,
            ioDispatcher = Dispatchers.IO,
        )

    @Provides
    @Singleton
    fun provideDeckIO(catalog: CardCatalog): DeckIO = DefaultDeckIO(catalog = catalog, ioDispatcher = Dispatchers.IO)
}
