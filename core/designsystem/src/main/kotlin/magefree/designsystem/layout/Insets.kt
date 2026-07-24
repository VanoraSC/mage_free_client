package magefree.designsystem.layout

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/*
 * The inset-ownership convention — one rule for who pads for system bars, cutouts, and the app's own
 * navigation chrome, so insets are applied exactly once.
 *
 * ## The convention
 *
 * - **The shell owns *chrome* insets.** The app shell (bottom-bar `Scaffold` / navigation-rail layout)
 *   applies the system-bar / display-cutout insets its chrome occupies AND marks them **consumed**
 *   (`Modifier.consumeWindowInsets`, or `windowInsetsPadding`, both of which are consumption-aware).
 *   The connection strip and the nav host sit inside that padded, consumed region.
 * - **Screens own only *content* insets.** A screen applies [Modifier.contentInsets] for the
 *   safe-drawing insets the shell did **not** consume (typically a horizontal display cutout on an
 *   edge with no chrome). Because it is consumption-aware, it never re-pads what the shell already
 *   handled — so it is always safe to add and cannot double-count.
 *
 * A screen must **not** reach for `Modifier.safeDrawingPadding()` / `systemBarsPadding()` directly:
 * those apply the full system-bar inset again on top of the shell's, which double-counts (the exact
 * `HomeScreen` bug this convention fixes — its bottom padding stacked on top of the bottom bar).
 */

/**
 * The content insets a screen owns under the shell's inset-ownership convention.
 *
 * Applies the *unconsumed* safe-drawing insets for the given [sides] via a consumption-aware
 * [windowInsetsPadding], so it composes correctly beneath a shell that has already consumed its chrome
 * insets. Defaults to the horizontal sides (side display cutouts) — the shell owns the vertical
 * system-bar insets, so a screen adding vertical here would be redundant (though still safe).
 *
 * @param sides the safe-drawing sides the screen is responsible for; defaults to
 *   [WindowInsetsSides.Horizontal].
 */
@Composable
fun Modifier.contentInsets(sides: WindowInsetsSides = WindowInsetsSides.Horizontal): Modifier =
    windowInsetsPadding(WindowInsets.safeDrawing.only(sides))
