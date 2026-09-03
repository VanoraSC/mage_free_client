package magefree.app.catalog

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

/**
 * Scopes **landscape orientation** to the composition it is placed in.
 *
 * §7.19 makes the whole new UI landscape and §11 says how to get there while the old UI is still
 * running: *"orientation is requested at runtime, not locked in the manifest"*. Locking the Activity
 * would rotate every old portrait screen along with it, degrading the UI that works to serve the one
 * being built.
 *
 * So it is a request, tied to a [DisposableEffect]: on enter the Activity is asked for landscape, and
 * on dispose it is returned to whatever it was before — leaving the surface, pressing back, or the
 * process being torn down all restore it. This is the seam the real board will use; it lives with the
 * catalog for now because the catalog's full-window preview is the first surface that needs it.
 *
 * No-ops safely when the host [Context] is not an [Activity].
 */
@Composable
fun LandscapeOnly() {
    val context = LocalContext.current
    DisposableEffect(context) {
        val activity = context.findActivity()
        if (activity == null) {
            onDispose {}
        } else {
            val previous = activity.requestedOrientation
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            onDispose { activity.requestedOrientation = previous }
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
