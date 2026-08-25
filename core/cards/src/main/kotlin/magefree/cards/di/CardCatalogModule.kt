package magefree.cards.di

import kotlinx.coroutines.Dispatchers
import magefree.cards.CardCatalog
import magefree.cards.bundle.AndroidBundledFiles
import magefree.cards.bundle.BundledFiles
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
        /**
         * The platform edge for bundled files (story 0082). Declared as a binding rather than
         * constructed inline so `:core:decks` can consume the same one for `formats.json` when story
         * 0083 converts it — §9.2's point that one resource story answers both.
         */
        single<BundledFiles> { AndroidBundledFiles(androidContext()) }

        single<CardCatalog> {
            val files = get<BundledFiles>()
            SqliteCardCatalog(
                databaseProvider = { CardCatalogDatabase.open(files) },
                ioDispatcher = Dispatchers.IO,
            )
        }
    }
