package magefree.feature.lobby.di

import magefree.feature.lobby.LobbyViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * Koin wiring for `:feature:lobby`. Like `:feature:connect`, this feature had no Hilt
 * module — its ViewModel was found by `@HiltViewModel` — so the file is new rather than converted.
 *
 * [magefree.network.LobbyRepository] comes from `:core:network`'s `networkModule`.
 */
val lobbyFeatureModule =
    module {
        viewModel { LobbyViewModel(lobbyRepository = get()) }
    }
