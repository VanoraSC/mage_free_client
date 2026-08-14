package magefree.cards.art

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Story 0056 — the art client must identify itself.
 *
 * Scryfall rejects generic client defaults: `User-Agent: okhttp/4.12.0` answers **HTTP 400**
 * (`generic_user_agent`), a descriptive one answers 302 → the image. [CardImageLoader] sent none, so
 * every card image request in the app failed silently and the UI degraded to placeholders.
 *
 * ### Why this test is built the way it is
 * [CardImageLoader] has a `callFactory` constructor seam that the cache-policy tests use to inject a
 * fake engine. **This test deliberately does not use it.** An injected `Call.Factory` carries whatever
 * headers the test gave it, so asserting on one would pass whether or not the shipping code sets a
 * `User-Agent` — it bypasses the exact default path that was broken. So the loader here is built with
 * `callFactory` **omitted**, and the only thing substituted is the *URL source*: a fake
 * [XMageImageSource] pointing at a local [MockWebServer]. That leaves the production HTTP client
 * intact and lets the server record the bytes that actually went out on the wire.
 *
 * That is also why the assertions read the **recorded request**, not the loader's configuration:
 * "an interceptor is installed" is a claim about wiring; "the request that left the client carried
 * this header" is the thing Scryfall actually judges.
 */
@RunWith(RobolectricTestRunner::class)
class CardArtUserAgentTest {
    private lateinit var context: Context
    private lateinit var scope: CoroutineScope
    private lateinit var diskDir: File
    private lateinit var server: MockWebServer

    /** A 1x1 PNG, so the fetch is a normal successful image response rather than an error path. */
    private val onePixelPng: ByteArray =
        Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAAC0lEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==",
        )

    /** Resolves every request to the mock server — the *only* production collaborator replaced. */
    private inner class LocalImageSource : XMageImageSource {
        override fun resolve(request: CardArtRequest): List<String> =
            listOf(
                server
                    .url("/cards/${request.setCode}/${request.collectorNumber}/en?format=image")
                    .toString(),
            )
    }

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        diskDir = File(context.cacheDir, "ua-test-" + System.nanoTime())
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        diskDir.deleteRecursively()
    }

    /**
     * A loader in its **shipping** configuration: no `callFactory` argument, so it builds and uses
     * whatever HTTP client production uses.
     */
    private fun defaultLoader(): CardImageLoader {
        val dataStore =
            PreferenceDataStoreFactory.create(scope = scope) {
                File(context.cacheDir, "ua-prefs-" + System.nanoTime() + ".preferences_pb")
            }
        return CardImageLoader(
            context = context,
            source = LocalImageSource(),
            policyRepository = CardArtCachePolicyRepository(dataStore),
            appScope = scope,
            ioDispatcher = Dispatchers.Unconfined,
            diskCacheDirectory = diskDir,
            // callFactory deliberately omitted — this test exists to exercise the default.
        )
    }

    private fun warmAndRecord(): RecordedRequest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "image/png")
                .setBody(Buffer().write(onePixelPng)),
        )
        val loader = defaultLoader()
        runBlocking {
            withTimeout(30_000) {
                loader.warm(CardArtRequest("M21", "272", CardArtFace.FRONT, CardArtSize.LARGE))
            }
        }
        val recorded = server.takeRequest(30, TimeUnit.SECONDS)
        assertNotNull("the loader never issued a request to the mock server", recorded)
        return recorded!!
    }

    @Test
    fun `the default loader sends a User-Agent on the wire`() {
        val userAgent = warmAndRecord().getHeader("User-Agent")

        assertNotNull(
            "card art requests must carry a User-Agent; Scryfall answers HTTP 400 without one",
            userAgent,
        )
        assertFalse("User-Agent must not be blank", userAgent!!.isBlank())
    }

    @Test
    fun `the User-Agent is not a generic client default`() {
        val userAgent = warmAndRecord().getHeader("User-Agent")!!

        // This is the exact value Scryfall rejects with `generic_user_agent`.
        assertFalse(
            "User-Agent '$userAgent' is OkHttp's generic default, which Scryfall rejects with HTTP 400",
            userAgent.startsWith("okhttp/"),
        )
    }

    @Test
    fun `the User-Agent identifies this application and its project`() {
        val userAgent = warmAndRecord().getHeader("User-Agent")!!

        // Scryfall's API docs ask for a descriptive agent naming the application, plus a way to reach
        // its authors. Both tokens are spelled out literally rather than read from the production
        // constants — a test that asserts a value against itself asserts nothing.
        assertTrue(
            "User-Agent '$userAgent' should name the application",
            userAgent.contains("mage-free-client"),
        )
        assertTrue(
            "User-Agent '$userAgent' should carry the project URL",
            userAgent.contains("github.com/VanoraSC/mage_free_client"),
        )
    }

    @Test
    fun `the User-Agent is sent exactly once`() {
        // A header set on top of OkHttp's own default rather than replacing it would ship two values,
        // and Scryfall would see the generic one too.
        val headers = warmAndRecord().headers.values("User-Agent")

        assertEquals("expected exactly one User-Agent header, got $headers", 1, headers.size)
    }
}
