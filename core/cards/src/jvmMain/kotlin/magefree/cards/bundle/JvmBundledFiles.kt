package magefree.cards.bundle

import okio.Path
import okio.Path.Companion.toOkioPath
import okio.Source
import okio.source
import java.io.File

/**
 * The JVM [BundledFiles]: classpath resources for bundled bytes, a directory under the JVM's temp
 * root for writable storage.
 *
 * This is the half of the boundary that had been named but never written. Its immediate job
 * is the `jvm()` target's test suite — `CardCatalogBundleTest` opens the real `cards.sqlite` through
 * it, which is what makes those tests a portability check rather than a second copy of the Android
 * ones — and it is what a desktop build uses unchanged.
 *
 * **A classpath resource is the JVM's APK asset.** `AssetManager.open(name)` and
 * `ClassLoader.getResourceAsStream(name)` have the same contract: a read-only stream over a file
 * packaged inside the application, addressed by a relative name. So the same `cards.sqlite` /
 * `formats.json` names resolve on both platforms with no call-site change.
 */
class JvmBundledFiles(
    private val classLoader: ClassLoader = JvmBundledFiles::class.java.classLoader!!,
    override val writableDirectory: Path = defaultWritableDirectory(),
) : BundledFiles {
    override fun openBundled(name: String): Source =
        requireNotNull(classLoader.getResourceAsStream(name)) {
            // Deliberately the same failure shape as the Android side: an absent bundle is a build
            // error, not a runtime condition to degrade around.
            "missing bundled resource: $name"
        }.source()

    private companion object {
        /**
         * A per-user directory under the JVM temp root, stable across launches so the catalog's
         * copy-once/prepare-once logic behaves exactly as it does on device rather than re-laying a
         * 14 MB file every run.
         */
        fun defaultWritableDirectory(): Path =
            File(System.getProperty("java.io.tmpdir"), "magefree-cards")
                .apply { mkdirs() }
                .toOkioPath()
    }
}
