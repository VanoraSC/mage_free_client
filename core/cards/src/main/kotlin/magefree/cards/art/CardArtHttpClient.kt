package magefree.cards.art

import android.content.Context
import android.content.pm.PackageManager
import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

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
 * Replaces the request's `User-Agent` (rather than appending to it) so exactly one value goes out —
 * an added header would leave OkHttp's generic default on the wire alongside ours.
 *
 * Registered as an *application* interceptor, which runs before OkHttp's `BridgeInterceptor` fills in
 * its default, so the header set here is the one that survives.
 */
internal class UserAgentInterceptor(
    private val userAgent: String,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response =
        chain.proceed(
            chain
                .request()
                .newBuilder()
                .header("User-Agent", userAgent)
                .build(),
        )
}

/**
 * The `Call.Factory` [CardImageLoader] uses when no test double is injected — i.e. the one that ships.
 * It is a stock [OkHttpClient] plus [UserAgentInterceptor]; no other behaviour is altered.
 */
internal fun defaultArtCallFactory(context: Context): Call.Factory =
    OkHttpClient
        .Builder()
        .addInterceptor(UserAgentInterceptor(CardArtUserAgent.value(context)))
        .build()
