package magefree.cards.art

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import coil3.PlatformContext
import coil3.memory.MemoryCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import okio.Path.Companion.toOkioPath
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * The art client must identify itself.
 *
 * Scryfall rejects generic client defaults: `User-Agent: okhttp/4.12.0` answers **HTTP 400**
 * (`generic_user_agent`), a descriptive one answers 302 → the image. [CardImageLoader] sent none, so
 * every card image request in the app failed silently and the UI degraded to placeholders.
 *
 * ### Why this test is built the way it is
 * [CardImageLoader] has a `httpClient` constructor seam that the cache-policy tests use to inject a
 * fake engine. **This test deliberately does not use it.** An injected `HttpClient` carries whatever
 * headers the test gave it, so asserting on one would pass whether or not the shipping code sets a
 * `User-Agent` — it bypasses the exact default path that was broken. So the loader here is built with
 * `httpClient` **omitted**, and the only thing substituted is the *URL source*: a fake
 * [XMageImageSource] pointing at a local [MockWebServer]. That leaves the production HTTP client
 * intact and lets the server record the bytes that actually went out on the wire.
 *
 * That is also why the assertions read the **recorded request**, not the loader's configuration:
 * "an interceptor is installed" is a claim about wiring; "the request that left the client carried
 * this header" is the thing Scryfall actually judges.
 */
class CardArtUserAgentTest {
    private val context: PlatformContext = PlatformContext.INSTANCE

    /** The JVM stand-in for the Android `Context.cacheDir`. */
    private val tempRoot: File = File(System.getProperty("java.io.tmpdir"), "magefree-art-test").apply { mkdirs() }
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
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        diskDir = File(tempRoot, "ua-test-" + System.nanoTime())
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        diskDir.deleteRecursively()
    }

    /**
     * A loader in its **shipping** configuration: no `httpClient` argument, so it builds and uses
     * whatever HTTP client production uses.
     */
    private fun defaultLoader(): CardImageLoader {
        val dataStore =
            PreferenceDataStoreFactory.create(scope = scope) {
                File(tempRoot, "ua-prefs-" + System.nanoTime() + ".preferences_pb")
            }
        return CardImageLoader(
            context = context,
            source = LocalImageSource(),
            policyRepository = CardArtCachePolicyRepository(dataStore),
            appScope = scope,
            ioDispatcher = Dispatchers.Unconfined,
            // no test here asserts on the failure log, but the parameter is required so a
            // loader can never be built that silently drops it.
            logWarning = { },
            diskCacheDirectory = diskDir.toOkioPath(),
            // Sized as the platform edge does, so the loader under test behaves as it ships.
            memoryCache = { MemoryCache.Builder().maxSizePercent(context, CardImageLoader.MEMORY_CACHE_PERCENT).build() },
            // httpClient deliberately omitted — this test exists to exercise the default.
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
    fun `the default loader sends an Accept header on the wire`() {
        // Scryfall's API rejects a request missing this header too —
        // "HTTP requests to api.scryfall.com must contain a User-Agent and Accept header" — with a
        // *different* HTTP 400 body than the generic-User-Agent case, so a User-Agent-only fix looks
        // complete (compiles, passes the User-Agent tests above) while still failing every request.
        val accept = warmAndRecord().getHeader("Accept")

        assertNotNull(
            "card art requests must carry an Accept header; Scryfall answers HTTP 400 without one",
            accept,
        )
        assertFalse("Accept must not be blank", accept!!.isBlank())
    }

    @Test
    fun `the User-Agent is not a generic client default`() {
        val userAgent = warmAndRecord().getHeader("User-Agent")!!

        // Asserted positively rather than by absence. Checking only
        // `!startsWith("okhttp/")` — the one generic default that existed when the fetcher was
        // OkHttp-based. Moving to Ktor's CIO engine changed what "generic" looks like, and a
        // `ktor-client/...` default sailed straight through the old check while being exactly as
        // rejectable by Scryfall. Naming engines is a losing game: the requirement is that the agent
        // *is ours*, which no future engine default can satisfy by accident.
        assertTrue(
            "User-Agent '$userAgent' is not this application's — Scryfall rejects generic client " +
                "defaults with HTTP 400 (generic_user_agent), whichever engine produced them",
            userAgent.startsWith("mage-free-client/"),
        )

        // Kept as documentation of the two defaults actually seen in this project.
        assertFalse("User-Agent '$userAgent' is OkHttp's generic default", userAgent.startsWith("okhttp/"))
        assertFalse("User-Agent '$userAgent' is Ktor's generic default", userAgent.startsWith("ktor-client"))
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
    fun `the User-Agent has the documented product-version-contact shape`() {
        val userAgent = warmAndRecord().getHeader("User-Agent")!!

        // Pins the whole value, not just its parts: `<product>/<version> (+<project url>)`. The
        // version is whatever the platform reports for the installed build — `unknown` under
        // Robolectric (the manifest under test declares no versionName), the app's versionName on a
        // device — so its presence is pinned but its content is not.
        val expected =
            Regex("""^mage-free-client/\S+ \(\+https://github\.com/VanoraSC/mage_free_client\)$""")
        assertTrue(
            "User-Agent '$userAgent' does not match ${expected.pattern}",
            expected.matches(userAgent),
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
