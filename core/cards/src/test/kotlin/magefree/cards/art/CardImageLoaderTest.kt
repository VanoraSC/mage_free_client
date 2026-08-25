package magefree.cards.art

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.util.Base64

/**
 * Loader cache-policy tests using a **fake OkHttp engine** (an interceptor that returns a canned 1x1
 * PNG for any URL) — Coil runs its real fetch + memory/disk cache pipeline, but nothing hits the
 * network. Proves: PERSISTENT writes the disk cache, SESSION_ONLY has no disk cache at all, and a
 * PERSISTENT → SESSION_ONLY downgrade clears the disk.
 */
@RunWith(RobolectricTestRunner::class)
class CardImageLoaderTest {
    private lateinit var context: Context
    private lateinit var scope: CoroutineScope
    private lateinit var diskDir: File
    private val source = ScryfallImageSource()

    // A 1x1 PNG returned for every request, so Coil's fetcher has real bytes to cache — no network.
    private val onePixelPng: ByteArray =
        Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAAC0lEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==",
        )

    private val fakeHttp: OkHttpClient =
        OkHttpClient
            .Builder()
            .addInterceptor { chain ->
                Response
                    .Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Content-Type", "image/png")
                    .header("Cache-Control", "max-age=3600")
                    .body(onePixelPng.toResponseBody("image/png".toMediaType()))
                    .build()
            }.build()

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        diskDir = File(context.cacheDir, "art-test-" + System.nanoTime())
    }

    @After
    fun tearDown() {
        diskDir.deleteRecursively()
    }

    /** True if the active loader's disk cache holds an entry for [key]. */
    private fun CardImageLoader.hasDiskEntry(key: String): Boolean {
        val disk = imageLoader.value.diskCache ?: return false
        val snapshot = disk.openSnapshot(key) ?: return false
        snapshot.close()
        return true
    }

    private fun newLoader(): CardImageLoader {
        val dataStore =
            PreferenceDataStoreFactory.create(scope = scope) {
                File(context.cacheDir, "art-prefs-" + System.nanoTime() + ".preferences_pb")
            }
        return CardImageLoader(
            context = context,
            source = source,
            policyRepository = CardArtCachePolicyRepository(dataStore),
            appScope = scope,
            ioDispatcher = Dispatchers.Unconfined,
            // Story 0082: no test here asserts on the failure log, but the parameter is required so a
            // loader can never be built that silently drops it.
            logWarning = { },
            diskCacheDirectory = diskDir,
            callFactory = fakeHttp,
        )
    }

    @Test
    fun `persistent policy builds a disk cache and session-only removes it`() {
        val loader = newLoader()

        // Default is PERSISTENT.
        assertNotNull(loader.imageLoader.value.diskCache)

        runBlocking { loader.applyPolicy(CardArtCachePolicy.SESSION_ONLY) }
        assertNull(loader.imageLoader.value.diskCache)

        runBlocking { loader.applyPolicy(CardArtCachePolicy.PERSISTENT) }
        assertNotNull(loader.imageLoader.value.diskCache)
    }

    @Test
    fun `warm under persistent writes the disk cache`() {
        val loader = newLoader()
        val request = CardArtRequest("XLN", "121", CardArtFace.FRONT, CardArtSize.LARGE)
        val key = source.primaryUrl(request)

        runBlocking { withTimeout(30_000) { loader.warm(request) } }

        assertTrue("expected a disk cache entry for $key", loader.hasDiskEntry(key))
        assertTrue(runBlocking { loader.isCached(request) })
    }

    @Test
    fun `warm under session-only never touches disk`() {
        val loader = newLoader()
        runBlocking { loader.applyPolicy(CardArtCachePolicy.SESSION_ONLY) }

        val request = CardArtRequest("DOM", "1", CardArtFace.FRONT, CardArtSize.LARGE)
        runBlocking { withTimeout(30_000) { loader.warm(request) } }

        // No disk cache exists at all under SESSION_ONLY.
        assertNull(loader.imageLoader.value.diskCache)
    }

    @Test
    fun `downgrade to session-only clears the disk cache`() {
        val loader = newLoader()
        val request = CardArtRequest("XLN", "121", CardArtFace.FRONT, CardArtSize.LARGE)
        val key = source.primaryUrl(request)

        runBlocking { withTimeout(30_000) { loader.warm(request) } }
        assertTrue(loader.hasDiskEntry(key))

        // Downgrade clears the disk...
        runBlocking { loader.applyPolicy(CardArtCachePolicy.SESSION_ONLY) }
        assertNull(loader.imageLoader.value.diskCache)

        // ...so a fresh PERSISTENT loader on the same directory finds nothing.
        runBlocking { loader.applyPolicy(CardArtCachePolicy.PERSISTENT) }
        assertFalse("disk entry should have been cleared on downgrade", loader.hasDiskEntry(key))
    }
}
