package magefree.app.game

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Scopes **immersive system UI** to the composition it is placed in.
 *
 * On enter it hides the status and navigation bars — via [WindowInsetsControllerCompat] with
 * [BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE][WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE]
 * so a swipe reveals them transiently — and on **dispose** it restores them (and the previous
 * bar behaviour). Because the work runs inside a [DisposableEffect] keyed to the current view, the
 * immersive state is strictly tied to this composable's lifetime: leaving the game route, pressing
 * back, or the Activity going to the background all remove it from composition and restore the bars.
 *
 * Placed at the top of [ImmersiveGameScreen]; no-ops safely when the host [Context] is not an
 * [Activity] (e.g. certain preview/inspection environments).
 */
@Composable
fun ImmersiveSystemUi() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = view.context.findActivity()?.window
        if (window == null) {
            onDispose {}
        } else {
            val controller = WindowInsetsControllerCompat(window, view)
            val previousBehavior = controller.systemBarsBehavior
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
            onDispose {
                // Restore on exit / back / backgrounding so the immersive state never leaks past
                // the game route.
                controller.show(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior = previousBehavior
            }
        }
    }
}

/** Walks the [ContextWrapper] chain to find the hosting [Activity], if any. */
private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
