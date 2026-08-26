package magefree.feature.connect.di

import magefree.feature.connect.ServerListViewModel
import magefree.feature.connect.SignInViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * Koin wiring for `:feature:connect`. The feature had no Hilt module — its two
 * ViewModels were discovered by `@HiltViewModel` alone — so this file is new rather than converted.
 *
 * Koin has no annotation-driven discovery: a ViewModel that is not declared is a ViewModel that
 * cannot be resolved, and the failure surfaces at the moment the screen opens. Since this is the
 * **first** screen the app shows (the entry policy starts on `ConnectRoute`), a miss here
 * is a launch crash rather than a subtle one.
 *
 * Both dependencies come from `:core:network`'s `networkModule`.
 */
val connectFeatureModule =
    module {
        viewModel { ServerListViewModel(serverRepository = get()) }
        viewModel { SignInViewModel(connectionRepository = get()) }
    }
