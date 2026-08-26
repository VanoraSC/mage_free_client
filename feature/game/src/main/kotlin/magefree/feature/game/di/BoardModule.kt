package magefree.feature.game.di

import magefree.feature.game.board.GameBoardViewModel
import magefree.feature.game.board.ManualPassPolicy
import magefree.feature.game.board.PassPolicy
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * Koin provisioning for `:feature:game` (was Hilt's `BoardModule`).
 *
 * It binds one thing beyond the ViewModel: the [PassPolicy] the board answers priority prompts with
 *. That is the whole point of the seam — **when stops and configurable
 * auto-pass arrive, this binding is what changes**, and nothing in the ViewModel or on the screen
 * has to.
 *
 * Hilt scoped the policy to `ViewModelComponent`, because it belongs to one board's lifetime. Koin's
 * equivalent is a `factory`: a fresh instance per resolution, which is what the ViewModel scope gave.
 * A future policy that reads persisted stop settings would take them as parameters here.
 */
val boardModule =
    module {
        /** Everything explicit and manual, as this release ships. */
        factory<PassPolicy> { ManualPassPolicy }

        viewModel { GameBoardViewModel(gameClient = get(), passPolicy = get(), cardCatalog = get()) }
    }
