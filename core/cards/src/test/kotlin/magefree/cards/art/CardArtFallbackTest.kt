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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.util.Base64
import java.util.Collections

/**
 * The documented `resolve()` fallback, exercised end-to-end through the real Coil pipeline with a fake
 * OkHttp engine — no network (story 0043, defect B).
 *
 * [XMageImageSource.resolve] returns the localized path first and the no-language
 * `include_variations=true` alternative second — upstream's documented workaround for promos and
 * variations that 404 on the localized path. These tests pin (a) that a 404 on the primary actually
 * falls through to the alternative and its bytes reach the cache, and (b) that the cache key is the
 * **request's** primary URL whichever candidate served the bytes, so a card never re-downloads
 * depending on which URL happened to win.
 *
 * The assertions are on the *fetch* (what was requested, and what landed in the disk cache), not on
 * the decoded bitmap: Robolectric's `ImageDecoder` cannot decode the canned PNG, so every load ends as
 * an `ErrorResult` after the bytes are already cached. That is the same reason `CardImageLoaderTest`
 * asserts on disk entries rather than on the [ArtWarmer.warm] return value.
 */
@RunWith(RobolectricTestRunner::class)
class CardArtFallbackTest {
    private lateinit var context: Context
    private lateinit var scope: CoroutineScope
    private lateinit var diskDir: File
    private val source = ScryfallImageSource()

    /** Set codes whose localized (primary) URL answers 404, forcing the fallback candidate. */
    private val notFoundOnPrimary = setOf("pmoa")

    private val requestedUrls: MutableList<String> = Collections.synchronizedList(ArrayList())

    private val onePixelPng: ByteArray =
        Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAAC0lEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==",
        )

    /**
     * Answers 404 for the localized path of [notFoundOnPrimary] sets and a 1x1 PNG for everything
     * else — i.e. exactly the promo/variation shape the fallback exists for.
     */
    private val fakeHttp: OkHttpClient =
        OkHttpClient
            .Builder()
            .addInterceptor { chain ->
                val url = chain.request().url.toString()
                requestedUrls += url
                val isPrimaryPath = !url.contains("include_variations")
                val isKnownMissing = notFoundOnPrimary.any { url.contains("/cards/$it/") }
                val builder =
                    Response
                        .Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                if (isPrimaryPath && isKnownMissing) {
                    builder
                        .code(404)
                        .message("Not Found")
                        .body("".toResponseBody("text/plain".toMediaType()))
                        .build()
                } else {
                    builder
                        .code(200)
                        .message("OK")
                        .header("Content-Type", "image/png")
                        .header("Cache-Control", "max-age=3600")
                        .body(onePixelPng.toResponseBody("image/png".toMediaType()))
                        .build()
                }
            }.build()

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        diskDir = File(context.cacheDir, "art-fallback-test-" + System.nanoTime())
    }

    @After
    fun tearDown() {
        diskDir.deleteRecursively()
    }

    private fun newLoader(): CardImageLoader {
        val dataStore =
            PreferenceDataStoreFactory.create(scope = scope) {
                File(context.cacheDir, "art-fallback-prefs-" + System.nanoTime() + ".preferences_pb")
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

    private fun CardImageLoader.hasDiskEntry(key: String): Boolean {
        val disk = imageLoader.value.diskCache ?: return false
        val snapshot = disk.openSnapshot(key) ?: return false
        snapshot.close()
        return true
    }

    private fun CardImageLoader.load(request: CardArtRequest) {
        runBlocking { withTimeout(30_000) { warm(request) } }
    }

    @Test
    fun `a 404 on the localized url falls back to the include_variations candidate`() {
        val loader = newLoader()
        // A promo printing: the localized path 404s, the include_variations alternative serves it.
        val promo = CardArtRequest("PMOA", "12", CardArtFace.FRONT, CardArtSize.LARGE)
        val candidates = source.resolve(promo)

        loader.load(promo)

        assertTrue("the primary candidate must be tried first: $requestedUrls", requestedUrls.contains(candidates[0]))
        assertTrue("the fallback candidate must actually be requested: $requestedUrls", requestedUrls.contains(candidates[1]))
        assertTrue(
            "candidates must be tried in resolve() order",
            requestedUrls.indexOf(candidates[0]) < requestedUrls.indexOf(candidates[1]),
        )
        assertTrue(
            "the fallback's bytes must reach the cache — otherwise a promo renders a placeholder forever",
            loader.hasDiskEntry(source.primaryUrl(promo)),
        )
        assertTrue("a fallback-served card must read back as cached", runBlocking { loader.isCached(promo) })
    }

    @Test
    fun `the disk cache key is the request's primary url whichever candidate served the bytes`() {
        val loader = newLoader()
        val servedByPrimary = CardArtRequest("XLN", "121", CardArtFace.FRONT, CardArtSize.LARGE)
        val servedByFallback = CardArtRequest("PMOA", "12", CardArtFace.FRONT, CardArtSize.LARGE)

        loader.load(servedByPrimary)
        loader.load(servedByFallback)

        // Both are keyed on the request's primary URL — the identity of the request, not of the
        // candidate that happened to answer.
        assertTrue(loader.hasDiskEntry(source.primaryUrl(servedByPrimary)))
        assertTrue(loader.hasDiskEntry(source.primaryUrl(servedByFallback)))
        assertFalse(
            "a fallback-served card must not be cached under the fallback URL, or it re-downloads",
            loader.hasDiskEntry(source.resolve(servedByFallback)[1]),
        )

        // And a second load of the fallback-served card is served from that same entry.
        val before = requestedUrls.size
        loader.load(servedByFallback)
        assertEquals("the cached entry must be reused, not re-fetched: $requestedUrls", before, requestedUrls.size)
    }
}
