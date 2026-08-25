package magefree.decks.legality

import androidx.test.core.app.ApplicationProvider
import magefree.cards.bundle.AndroidBundledFiles
import magefree.decks.internal.FormatBundleLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * `formats.json` is really bundled, and really readable through the boundary `:core:cards` reads
 * `cards.sqlite` through (story 0083).
 *
 * This is verification standard 2 for the bundled-resource half of the port: on Android the bytes
 * come from `AndroidBundledFiles`, over the APK assets merged out of this module's `androidMain`,
 * and that is exercised here rather than only declared in a Koin definition. An asset that failed to
 * ship, or a boundary that looked in the wrong place, would otherwise first show up as a deck with
 * no legality verdict on a device in airplane mode.
 */
@RunWith(RobolectricTestRunner::class)
class FormatBundleLoaderTest {
    @Test
    fun `the bundled legality asset loads through BundledFiles`() {
        val files = AndroidBundledFiles(ApplicationProvider.getApplicationContext())

        val bundle = FormatBundleLoader.load(files)

        assertEquals(1, bundle.schemaVersion)
        assertEquals("1.4.60", bundle.xmageVersion)
        assertEquals(15, bundle.sideboardMax)

        val modern = bundle.formats.single { it.key == "modern" }
        assertTrue("modern should list legal sets", modern.legalSetCodes.isNotEmpty())
        assertTrue("modern should list bans", modern.banned.isNotEmpty())

        // Basic lands are the "unlimited copies" case the bundle encodes as -1.
        assertEquals(UNLIMITED_COPIES, bundle.maxCopiesOverrides["Mountain"])
    }
}
