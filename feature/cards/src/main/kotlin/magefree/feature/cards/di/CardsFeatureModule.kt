package magefree.feature.cards.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import magefree.feature.cards.ArtCacheController
import magefree.feature.cards.DefaultArtCacheController
import javax.inject.Singleton

/**
 * Hilt wiring for `:feature:cards`. Binds the feature-local [ArtCacheController] to its production
 * implementation, which delegates to story 0031's already-provided policy repository + download
 * manager. Everything the search/inspection ViewModels need ([magefree.cards.CardCatalog]) is already
 * provided by `:core:cards`, so no further bindings are required here.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class CardsFeatureModule {
    @Binds
    @Singleton
    abstract fun bindArtCacheController(impl: DefaultArtCacheController): ArtCacheController
}
