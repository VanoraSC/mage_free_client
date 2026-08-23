package magefree.decks.di

import androidx.room.Room
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
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

/**
 * Koin provisioning for `:core:decks` (was Hilt's `DeckModule`). Exposes only the public
 * [DeckRepository] (fully-offline deck library, Room-backed) and [DeckLegality] (offline checker
 * over the bundled `formats.json` + the `:core:cards` [CardCatalog]); the Room database/DAO stay
 * internal to the module and never enter the graph. `:app` gets both working just by depending on
 * this module — no network involved.
 */
val deckModule =
    module {
        single<DeckRepository> {
            val database =
                Room
                    .databaseBuilder(androidContext(), DeckDatabase::class.java, DeckDatabase.NAME)
                    .build()
            RoomDeckRepository(dao = database.deckDao(), ioDispatcher = Dispatchers.IO)
        }

        single<DeckLegality> {
            val context = androidContext()
            DefaultDeckLegality(
                bundleProvider = { FormatBundleLoader.load(context) },
                catalog = get(),
                ioDispatcher = Dispatchers.IO,
            )
        }

        single<DeckIO> { DefaultDeckIO(catalog = get(), ioDispatcher = Dispatchers.IO) }
    }
