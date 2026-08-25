package magefree.cards.art

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import coil3.PlatformContext
import coil3.memory.MemoryCache
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.Path.Companion.toOkioPath
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Base64

/**
 * Loader cache-policy tests using a **fake OkHttp engine** (an interceptor that returns a canned 1x1
 * PNG for any URL) — Coil runs its real fetch + memory/disk cache pipeline, but nothing hits the
 * network. Proves: PERSISTENT writes the disk cache, SESSION_ONLY has no disk cache at all, and a
 * PERSISTENT → SESSION_ONLY downgrade clears the disk.
 */
class CardImageLoaderTest {
    private val context: PlatformContext = PlatformContext.INSTANCE

    /** Story 0085: the JVM stand-in for the Android `Context.cacheDir` these tests used to write to. */
    private val tempRoot: File = File(System.getProperty("java.io.tmpdir"), "magefree-art-test").apply { mkdirs() }
    private lateinit var scope: CoroutineScope
    private lateinit var diskDir: File
    private val source = ScryfallImageSource()

    // A 1x1 PNG returned for every request, so Coil's fetcher has real bytes to cache — no network.
    private val onePixelPng: ByteArray =
        Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAAC0lEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==",
        )

    private val fakeHttp: HttpClient =
        HttpClient(
            MockEngine {
                respond(
                    content = onePixelPng,
                    status = HttpStatusCode.OK,
                    headers =
                        headersOf(
                            HttpHeaders.ContentType to listOf("image/png"),
                            HttpHeaders.CacheControl to listOf("max-age=3600"),
                        ),
                )
            },
        )

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        diskDir = File(tempRoot, "art-test-" + System.nanoTime())
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
                File(tempRoot, "art-prefs-" + System.nanoTime() + ".preferences_pb")
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
            diskCacheDirectory = diskDir.toOkioPath(),
            // Sized as the platform edge does, so the loader under test behaves as it ships.
            memoryCache = { MemoryCache.Builder().maxSizePercent(context, CardImageLoader.MEMORY_CACHE_PERCENT).build() },
            httpClient = fakeHttp,
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
