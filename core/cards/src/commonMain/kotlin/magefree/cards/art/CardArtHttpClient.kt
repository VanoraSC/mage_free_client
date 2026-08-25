package magefree.cards.art

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.UserAgent
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders

/**
 * The `User-Agent` every card-art request carries (story 0056).
 *
 * Scryfall's API documentation asks clients to identify themselves, and **enforces it**: a request
 * carrying a generic client default is rejected outright rather than throttled.
 *
 * ```
 * User-Agent: okhttp/4.12.0                                          -> HTTP 400 (generic_user_agent)
 * User-Agent: mage-free-client/0.1.0 (+https://github.com/...)       -> HTTP 302 (redirect to image)
 * ```
 *
 * [CardImageLoader] previously built its fetcher from a bare `OkHttpNetworkFetcherFactory()`, whose
 * client sends OkHttp's own `okhttp/<version>` — so **every** card image request in the app was
 * refused, and every art surface degraded silently to its placeholder.
 *
 * **2026-08-16 addendum, found live (Pete):** setting `User-Agent` alone stopped being sufficient.
 * Wire-level capture (`HttpLoggingInterceptor`, `Level.BODY`) showed every request carrying the
 * correct `User-Agent` and still failing HTTP 400, with a response body reading: *"HTTP requests to
 * api.scryfall.com must contain a User-Agent **and Accept** header."* — Scryfall's API now also
 * requires an `Accept` header, which this client never sent. [defaultArtHttpClient] sets both.
 */
internal object CardArtUserAgent {
    /** The application token — the "descriptive" part Scryfall looks for. */
    const val PRODUCT = "mage-free-client"

    /** Where to reach the authors, per Scryfall's guidance. */
    const val PROJECT_URL = "https://github.com/VanoraSC/mage_free_client"

    /**
     * Used when the platform cannot tell us the version. Deliberately still descriptive: an unknown
     * version is a cosmetic loss, whereas falling back to a generic agent would restore the defect.
     */
    const val UNKNOWN_VERSION = "unknown"

    /**
     * e.g. `mage-free-client/0.1.0 (+https://github.com/VanoraSC/mage_free_client)`.
     *
     * Story 0082 made this take the version rather than read it: deriving it from `PackageManager`
     * pinned the whole art pipeline to Android for one string. A blank or missing version degrades
     * to [UNKNOWN_VERSION] rather than producing `mage-free-client/ (+…)`, because the descriptive
     * token is the part Scryfall checks and an empty version must not be able to break it.
     */
    fun value(appVersion: String?): String = "$PRODUCT/${appVersion?.takeIf { it.isNotBlank() } ?: UNKNOWN_VERSION} (+$PROJECT_URL)"
}

/**
 * The [HttpClient] [CardImageLoader] uses when no test double is injected — i.e. the one that ships.
 *
 * **Story 0082 replaced an OkHttp `Call.Factory` + network interceptor with this.** The engine is
 * still OkHttp on Android; what changed is that the *fetcher* is no longer OkHttp-shaped, so the
 * engine becomes a per-platform choice rather than a dependency baked into the art pipeline.
 *
 * Both headers Scryfall requires are set here, and they remain load-bearing rather than courtesy:
 * a generic agent is answered with HTTP 400 and every art surface in the app silently falls back to
 * its placeholder (story 0056).
 *
 * `UserAgent` is installed as a plugin rather than appended via [defaultRequest] deliberately —
 * `defaultRequest` appends only headers that are not already present, so an engine-supplied
 * `okhttp/<version>` would win and restore exactly the defect 0056 fixed. The plugin sets the header
 * outright. `CardArtUserAgentTest` reads the real wire request, so this claim is checked rather
 * than assumed.
 */
internal fun defaultArtHttpClient(userAgent: String): HttpClient =
    HttpClient(CIO) {
        install(UserAgent) { agent = userAgent }
        defaultRequest {
            // Scryfall's own error text: "HTTP requests to api.scryfall.com must contain a
            // User-Agent and Accept header." `*/*` is the same permissive value curl sends by
            // default, which Scryfall's docs confirm is accepted.
            header(HttpHeaders.Accept, "*/*")
        }
    }
