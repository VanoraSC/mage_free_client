package magefree.cards.di

import kotlinx.coroutines.Dispatchers
import magefree.cards.CardCatalog
import magefree.cards.internal.CardCatalogDatabase
import magefree.cards.internal.SqliteCardCatalog
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

/**
 * Koin provisioning for the bundled card catalog (was Hilt's `CardCatalogModule`). Provides a single
 * app-wide [CardCatalog] backed by the on-device SQLite asset, opened lazily on the IO dispatcher.
 * The catalog is fully offline — it needs no bridge, network, or connection — so `:app` gets a
 * working catalog just by depending on this module.
 */
val cardCatalogModule =
    module {
        single<CardCatalog> {
            val context = androidContext()
            SqliteCardCatalog(
                databaseProvider = { CardCatalogDatabase.open(context) },
                ioDispatcher = Dispatchers.IO,
            )
        }
    }
