package magefree.feature.game.di

import android.util.Log
import magefree.feature.game.board.GameBoardViewModel
import magefree.feature.game.board.ManualPassPolicy
import magefree.feature.game.board.PassPolicy
import magefree.feature.game.board.StopStore
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * The logcat tag the board narrates under: `adb logcat -s MageBoardDiag`.
 *
 * Only builds with `BuildConfig.BOARD_DIAG` on write anything here — debug builds by default, release
 * builds never unless `-PboardDiag=true` is passed. See `feature/game/build.gradle.kts`.
 */
internal const val BOARD_DIAG_TAG: String = "MageBoardDiag"

/**
 * Koin provisioning for `:feature:game` (was Hilt's `BoardModule`).
 *
 * **The pass policy stays manual, and stops did not change that.** The seam was written expecting
 * auto-pass to arrive as a policy here; it arrived somewhere else entirely. `HumanPlayer.priority()`
 * reads the player's own `UserSkipPrioritySteps` on the *server* and passes without ever sending the
 * client a prompt, so a client-side policy can only decline questions it was asked — and the questions
 * a stop is about are the ones it never receives. The stops are sent upstream instead
 * (`GameClient.setPriorityStops`), and every prompt that does arrive is one the player asked for.
 *
 * The store is a `single`: stops belong to the player, and a match plays several games through several
 * boards. Set once, played with.
 */
val boardModule =
    module {
        /** What the player has asked to be stopped at. Outlives any one game. */
        single { StopStore() }

        /** Everything explicit and manual: the skipping the player wants is the server's to do. */
        factory<PassPolicy> { ManualPassPolicy }

        viewModel {
            GameBoardViewModel(
                gameClient = get(),
                passPolicy = get(),
                cardCatalog = get(),
                stops = get(),
                // Wired unconditionally: the switch lives at the call sites, where it can keep a message
                // from being built at all, rather than here, where it could only discard one.
                log = { message -> Log.d(BOARD_DIAG_TAG, message) },
            )
        }
    }
