package magefree.cards.art

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor

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
 * requires an `Accept` header, which this client never sent. [ScryfallHeadersInterceptor] sets both.
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

    /** e.g. `mage-free-client/0.1.0 (+https://github.com/VanoraSC/mage_free_client)`. */
    fun value(context: Context): String = "$PRODUCT/${appVersion(context)} (+$PROJECT_URL)"

    /**
     * The installed package's `versionName` — i.e. the version declared by the app's build
     * (`app/build.gradle.kts`, `versionName = "0.1.0"`), read back at runtime.
     *
     * This module has no `BuildConfig` version of its own and cannot see the app module's, so the
     * package manager is the one source that tracks the real build rather than duplicating a literal
     * that would drift the first time the app version changes. In a JVM/Robolectric unit test the
     * manifest under test declares no `versionName`, so this yields [UNKNOWN_VERSION] there — which
     * is why the tests assert on the descriptive tokens, not on a version string.
     */
    private fun appVersion(context: Context): String =
        try {
            context.packageManager
                .getPackageInfo(context.packageName, 0)
                ?.versionName
                ?.takeIf { it.isNotBlank() }
                ?: UNKNOWN_VERSION
        } catch (_: PackageManager.NameNotFoundException) {
            UNKNOWN_VERSION
        }
}

/**
 * Sets the two headers Scryfall's API requires (`User-Agent` and `Accept`), replacing rather than
 * appending so exactly one value of each goes out — an added header would leave OkHttp's generic
 * defaults on the wire alongside ours.
 *
 * Registered as a **network** interceptor ([defaultArtCallFactory]'s `addNetworkInterceptor`) per
 * Coil's own docs (coil-kt.github.io/coil/network/), which call this out specifically as what
 * "ensures headers apply to every image request handled by your ImageLoader".
 */
internal class ScryfallHeadersInterceptor(
    private val userAgent: String,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response =
        chain.proceed(
            chain
                .request()
                .newBuilder()
                .header("User-Agent", userAgent)
                // Scryfall's own error text: "HTTP requests to api.scryfall.com must contain a
                // User-Agent and Accept header." `*/*` is the same permissive value curl sends by
                // default, which Scryfall's docs confirm is accepted.
                .header("Accept", "*/*")
                .build(),
        )
}

/**
 * The `Call.Factory` [CardImageLoader] uses when no test double is injected — i.e. the one that ships.
 * It is a stock [OkHttpClient] plus [ScryfallHeadersInterceptor]; no other behaviour is altered.
 */
internal fun defaultArtCallFactory(context: Context): Call.Factory =
    OkHttpClient
        .Builder()
        .addNetworkInterceptor(ScryfallHeadersInterceptor(CardArtUserAgent.value(context)))
        // Temporary diagnostic (2026-08-16): OkHttp's own documented wire-logging tool
        // (square/okhttp's HttpLoggingInterceptor), registered as a network interceptor so its
        // output is the literal bytes on the wire — headers *and* status line — for every art
        // fetch. Logcat tag "OkHttp". Remove once the Accept-header fix is confirmed live.
        .addNetworkInterceptor(
            HttpLoggingInterceptor { message -> Log.d("OkHttp", message) }
                .apply { level = HttpLoggingInterceptor.Level.BODY },
        ).build()
