package magefree.feature.cards.di

import magefree.feature.cards.ArtCacheController
import magefree.feature.cards.CardArtSettingsViewModel
import magefree.feature.cards.CardInspectionViewModel
import magefree.feature.cards.CardSearchViewModel
import magefree.feature.cards.DefaultArtCacheController
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * Koin wiring for `:feature:cards` (was Hilt's `CardsFeatureModule`). Binds the feature-local
 * [ArtCacheController] to its production implementation, which delegates to the
 * already-provided policy repository + download manager, and declares the feature's ViewModels.
 *
 * [magefree.cards.CardCatalog] comes from `:core:cards`, so nothing more is needed here.
 */
val cardsFeatureModule =
    module {
        single<ArtCacheController> { DefaultArtCacheController(policyRepository = get(), downloadManager = get()) }

        viewModel { CardSearchViewModel(catalog = get()) }
        viewModel { CardInspectionViewModel(catalog = get()) }
        viewModel { CardArtSettingsViewModel(controller = get()) }
    }
