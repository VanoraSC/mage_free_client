package magefree.feature.game.di

import magefree.feature.game.board.GameBoardViewModel
import magefree.feature.game.board.PassPolicy
import magefree.feature.game.board.StopPassPolicy
import magefree.feature.game.board.StopStore
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * Koin provisioning for `:feature:game` (was Hilt's `BoardModule`).
 *
 * It binds two things beyond the ViewModel: the [PassPolicy] the board answers priority prompts with,
 * and the [StopStore] that policy reads.
 *
 * **This binding is what the seam was for.** `PassPolicy`'s own KDoc said it: "when stops and
 * configurable auto-pass arrive, this binding is what changes, and nothing in the ViewModel or on the
 * screen has to." 0115 is that, and it was.
 *
 * The policy is a `factory` — one board's lifetime, which is what Hilt's `ViewModelComponent` scope
 * gave it. The store is a `single`, deliberately: stops belong to the *player*, not to a game, and a
 * match plays several games through several boards. Set once, played with.
 */
val boardModule =
    module {
        /** What the player has asked to be stopped at. Outlives any one game. */
        single { StopStore() }

        /** Auto-pass, with the player's own stops and the two that are rules. */
        factory<PassPolicy> { StopPassPolicy(stops = get()) }

        viewModel {
            GameBoardViewModel(gameClient = get(), passPolicy = get(), cardCatalog = get(), stops = get())
        }
    }
