package magefree.cards.bundle

import android.content.Context
import okio.Path
import okio.Path.Companion.toOkioPath
import okio.Source
import okio.source

/**
 * The Android [BundledFiles]: APK assets for bundled bytes, the app's private `filesDir` for
 * writable storage.
 *
 * This is the file the KMP conversion relocates to `androidMain` — the whole of `:core:cards`'
 * bundled-asset dependency on Android is now these few lines, rather than a `Context` threaded
 * through the catalog opener.
 *
 * `AssetManager.open` streams and transparently inflates, so the APK is free to keep `cards.sqlite`
 * compressed (~5.5 MB rather than ~14 MB). The >1 MB compressed-asset caveat applies only to
 * `openFd`/mmap access, which this path never uses.
 */
class AndroidBundledFiles(
    private val context: Context,
) : BundledFiles {
    override fun openBundled(name: String): Source = context.assets.open(name).source()

    override val writableDirectory: Path get() = context.filesDir.toOkioPath()
}
