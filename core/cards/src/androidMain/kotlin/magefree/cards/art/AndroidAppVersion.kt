package magefree.cards.art

import android.content.Context
import android.content.pm.PackageManager

/**
 * The installed package's `versionName` — i.e. the version declared by the app's build
 * (`app/build.gradle.kts`, `versionName = "0.1.0"`), read back at runtime.
 *
 * This module has no `BuildConfig` version of its own and cannot see the app module's, so the
 * package manager is the one source that tracks the real build rather than duplicating a literal
 * that would drift the first time the app version changes. In a JVM/Robolectric unit test the
 * manifest under test declares no `versionName`, so this yields `null` there — which is why the
 * tests assert on the descriptive tokens, not on a version string.
 *
 * Its own file because it is the only Android-specific line in the art pipeline:
 * the `User-Agent` string-building into [CardArtUserAgent.value], leaving the KMP conversion a file
 * to relocate rather than a pipeline to unpick.
 */
internal fun androidAppVersion(context: Context): String? =
    try {
        context.packageManager
            .getPackageInfo(context.packageName, 0)
            ?.versionName
            ?.takeIf { it.isNotBlank() }
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }
