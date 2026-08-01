package magefree.cards.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import magefree.cards.CardCatalog
import magefree.cards.internal.CardCatalogDatabase
import magefree.cards.internal.SqliteCardCatalog
import javax.inject.Singleton

/**
 * Hilt provisioning for the bundled card catalog. Provides a single app-wide [CardCatalog] backed by
 * the on-device SQLite asset, opened lazily on the IO dispatcher. The catalog is fully offline — it
 * needs no bridge, network, or connection — so `:app` gets a working catalog just by depending on
 * this module.
 */
@Module
@InstallIn(SingletonComponent::class)
object CardCatalogModule {
    @Provides
    @Singleton
    fun provideCardCatalog(
        @ApplicationContext context: Context,
    ): CardCatalog =
        SqliteCardCatalog(
            databaseProvider = { CardCatalogDatabase.open(context) },
            ioDispatcher = Dispatchers.IO,
        )
}
