package magefree.feature.decks.di

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import magefree.cards.art.CardImageLoader
import magefree.feature.decks.art.DeckArtDownloader
import magefree.feature.decks.art.DefaultDeckArtDownloader
import magefree.feature.decks.builder.BuilderViewModel
import magefree.feature.decks.library.LibraryViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/** Long-lived scope for the deck-scoped pre-download (mirrors `:core:cards`' art scope). */
private val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * Koin wiring for `:feature:decks` (was Hilt's `DecksFeatureModule`). Provides the deck-scoped
 * [DeckArtDownloader] and the feature's ViewModels; everything else they need —
 * `DeckRepository`/`DeckLegality`/`DeckIO` (`:core:decks`), `CardCatalog` + [CardImageLoader]
 * (`:core:cards`), and `ArtCacheController` (`:feature:cards`) — is provided elsewhere in the graph.
 *
 * The downloader reuses the `ArtDownloadManager` and the app-wide [CardImageLoader] (as the
 * `ArtWarmer`); only its *target set* is deck-scoped.
 */
val decksFeatureModule =
    module {
        single<DeckArtDownloader> {
            DefaultDeckArtDownloader(
                catalog = get(),
                warmer = get<CardImageLoader>(),
                appScope = appScope,
            )
        }

        viewModel { LibraryViewModel(repository = get(), deckIO = get()) }

        viewModel {
            BuilderViewModel(
                repository = get(),
                catalog = get(),
                legality = get(),
                deckIO = get(),
                artDownloader = get(),
                artCache = get(),
            )
        }
    }
