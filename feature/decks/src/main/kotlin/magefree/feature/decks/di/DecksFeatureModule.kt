package magefree.feature.decks.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import magefree.cards.CardCatalog
import magefree.cards.art.CardImageLoader
import magefree.feature.decks.art.DeckArtDownloader
import magefree.feature.decks.art.DefaultDeckArtDownloader
import javax.inject.Singleton

/**
 * Hilt wiring for `:feature:decks`. Provides only the deck-scoped [DeckArtDownloader]; everything else
 * the ViewModels need — `DeckRepository`/`DeckLegality`/`DeckIO` (`:core:decks`), `CardCatalog` +
 * `CardImageLoader` (`:core:cards`), and `ArtCacheController` (`:feature:cards`) — is already provided
 * elsewhere in the singleton graph. The downloader reuses story 0031's `ArtDownloadManager` + the
 * app-wide [CardImageLoader] (as the `ArtWarmer`); only its *target set* is deck-scoped.
 */
@Module
@InstallIn(SingletonComponent::class)
object DecksFeatureModule {
    /** Long-lived scope for the deck-scoped pre-download (mirrors `:core:cards`' art scope). */
    private val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Provides
    @Singleton
    fun provideDeckArtDownloader(
        catalog: CardCatalog,
        loader: CardImageLoader,
    ): DeckArtDownloader =
        DefaultDeckArtDownloader(
            catalog = catalog,
            warmer = loader,
            appScope = appScope,
        )
}
