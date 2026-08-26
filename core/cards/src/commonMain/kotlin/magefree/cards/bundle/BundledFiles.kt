package magefree.cards.bundle

import okio.Path
import okio.Source

/**
 * Where a bundled file's bytes come from, and where this process may write.
 *
 * **This is the "bundled assets are their own case", made concrete.** Two modules ship a file
 * inside the application and read it at runtime — `:core:cards` for the 14 MB `cards.sqlite`, and
 * `:core:decks` for `formats.json` — and both did it through `Context.getAssets()`, which is the
 * single reason each of them needed an Android `Context` at all. The mechanism is identical at both
 * sizes, so it is defined once here rather than invented twice.
 *
 * Deliberately expressed in okio types rather than `java.io`: a KMP module's common source set
 * cannot reference `java.*`, and okio is already on the classpath (Coil and DataStore both use it).
 *
 * Implementations live at the platform edge — `AndroidBundledFiles` reads APK assets and the app's
 * private `filesDir`; a JVM host reads classpath resources and a temp or working directory.
 */
interface BundledFiles {
    /**
     * Opens the file bundled under [name] — an APK asset on Android, a classpath resource on the
     * JVM. The caller closes it.
     *
     * Throws if [name] is not bundled: an absent bundle is a build error, not a runtime condition to
     * degrade around, and the app cannot function without its catalog.
     */
    fun openBundled(name: String): Source

    /**
     * A private directory this process may write to and read back across launches.
     *
     * `cards.sqlite` cannot be opened in place — an asset lives inside the APK zip — so it is copied
     * here once and opened from here thereafter.
     */
    val writableDirectory: Path
}
