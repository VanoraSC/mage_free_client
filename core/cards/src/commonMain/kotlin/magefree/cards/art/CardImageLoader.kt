package magefree.cards.art

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.NetworkFetcher
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.Path

/**
 * Warms/queries the art cache for a single [CardArtRequest]. This is the narrow surface the bulk
 * pre-download ([ArtDownloadManager]) depends on, so it can be faked in tests without touching Coil.
 */
interface ArtWarmer {
    /** True if this request's art is already in the memory or disk cache (prefetch resume skips it). */
    suspend fun isCached(request: CardArtRequest): Boolean

    /** Fetch this request's art into the active cache. Returns whether it succeeded. */
    suspend fun warm(request: CardArtRequest): Boolean
}

/**
 * The on-demand card-art loader. Callers pass a [CardArtRequest] (identity), never a URL;
 * [CardArtFetcher] resolves the URL candidates via [XMageImageSource] and fetches the first one that
 * answers, while Coil provides the memory+disk cache, request de-duplication, and cancellation. Every
 * cache key — memory and disk — is the request's *primary* URL, whichever candidate served the bytes.
 *
 * ### Cache policy
 * The loader honors the active [CardArtCachePolicy]:
 * - [CardArtCachePolicy.PERSISTENT] builds an [ImageLoader] with a disk cache (`diskCache != null`).
 * - [CardArtCachePolicy.SESSION_ONLY] builds one with **no disk cache** (`diskCache == null`) — Coil's
 *   disk layer is off entirely, so nothing is written to storage; only the in-memory LRU is used.
 *
 * It observes [CardArtCachePolicyRepository]; on a **downgrade** (PERSISTENT → SESSION_ONLY) it clears
 * the existing disk cache before swapping in the memory-only loader. The current loader is exposed as
 * a [StateFlow] so the `AsyncImage` always binds to the policy-correct instance.
 *
 * ### Identifying the client
 * The default HTTP client sends a descriptive `User-Agent` ([CardArtUserAgent]). Scryfall rejects
 * generic client defaults with HTTP 400, so this is load-bearing, not courtesy.
 *
 * ### Offline / placeholder
 * A cache miss with no network yields an [ErrorResult] (never a crash); card-browse renders the design-system
 * placeholder, and the card's text stays available from the bundled catalog.
 */
class CardImageLoader(
    /**
     * Coil's own context type. On Android it is a `typealias` for `android.content.Context`, so this
     * is the same object as before and nothing about the shipping behaviour changes — but the type
     * is multiplatform, which is what lets this class leave `androidMain`.
     */
    private val context: PlatformContext,
    private val source: XMageImageSource,
    private val policyRepository: CardArtCachePolicyRepository,
    private val appScope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val diskCacheDirectory: Path,
    /**
     * How big the in-memory cache is, decided at the platform edge.
     *
     * Coil's `maxSizePercent` takes an `android.content.Context` and is Android-only, so sizing the
     * cache as a share of the device's memory class cannot live here. Rather than replace it with a
     * fixed byte count — a behaviour change on the platform that ships — the whole decision is
     * supplied, and Android keeps passing `maxSizePercent`.
     */
    private val memoryCache: () -> MemoryCache,
    httpClient: HttpClient? = null,
    /**
     * The `User-Agent` the default client sends. **Load-bearing, not courtesy** — Scryfall answers
     * HTTP 400 to a generic agent and every art surface in the app degrades to its placeholder
     *. Supplied rather than defaulted so there is no way to construct a loader that
     * quietly sends nothing.
     */
    userAgent: String = CardArtUserAgent.value(null),
    /**
     * Where a failed fetch is reported. Injected rather than calling `android.util.Log` directly, so
     * the failure path is not what pins this class to Android — and so a JVM host can route it
     * somewhere real instead of dropping it.
     */
    private val logWarning: (String) -> Unit,
) : ArtWarmer {
    private val networkFetcherFactory: NetworkFetcher.Factory =
        if (httpClient != null) {
            KtorNetworkFetcherFactory(httpClient = { httpClient })
        } else {
            // Not a bare KtorNetworkFetcherFactory(): its client would send the engine's own generic agent,
            // which Scryfall refuses with HTTP 400. The lambda is evaluated lazily by
            // Coil, so the client is still built on first use.
            KtorNetworkFetcherFactory(httpClient = { defaultArtHttpClient(userAgent) })
        }

    private val rebuildMutex = Mutex()

    @Volatile
    private var currentPolicy: CardArtCachePolicy = CardArtCachePolicy.DEFAULT

    private val _imageLoader = MutableStateFlow(build(CardArtCachePolicy.DEFAULT))

    /** The policy-correct Coil loader; re-emits whenever the cache policy changes. */
    val imageLoader: StateFlow<ImageLoader> = _imageLoader.asStateFlow()

    init {
        appScope.launch {
            policyRepository.policy.collect { policy -> applyPolicy(policy) }
        }
    }

    /**
     * Swap the active loader to [policy], clearing the disk cache on a PERSISTENT → SESSION_ONLY
     * downgrade. Idempotent; safe to call directly from tests.
     */
    internal suspend fun applyPolicy(policy: CardArtCachePolicy): Unit =
        rebuildMutex.withLock {
            if (policy == currentPolicy) return@withLock
            val downgradeToMemoryOnly = currentPolicy.usesDiskCache && !policy.usesDiskCache
            withContext(ioDispatcher) {
                val next = build(policy)
                val previous = _imageLoader.value
                if (downgradeToMemoryOnly) previous.diskCache?.clear()
                previous.shutdown()
                _imageLoader.value = next
                currentPolicy = policy
            }
        }

    override suspend fun isCached(request: CardArtRequest): Boolean =
        withContext(ioDispatcher) {
            val loader = _imageLoader.value
            val key = cacheKey(request)
            val inMemory = loader.memoryCache?.keys?.any { it.key == key } == true
            if (inMemory) return@withContext true
            val snapshot = loader.diskCache?.openSnapshot(key) ?: return@withContext false
            snapshot.close()
            true
        }

    override suspend fun warm(request: CardArtRequest): Boolean {
        val result = _imageLoader.value.execute(buildRequest(request))
        return when (result) {
            is SuccessResult -> true
            is ErrorResult -> {
                // ArtDownloadManager only counts failures, it does not see why — without this, a
                // systemic problem (every request 4xx-ing, DNS down, TLS failure) is
                // indistinguishable from ordinary per-card misses (a bad printing, an offline
                // device) in anything short of attaching a debugger. Logcat is the only place this
                // is currently visible; there is no in-app diagnostic surface for it yet.
                logWarning(
                    "Art fetch failed for ${request.setCode} #${request.collectorNumber} " +
                        "(${source.primaryUrl(request)}): ${result.throwable}",
                )
                false
            }
        }
    }

    /** An [ImageRequest] for [request] with stable memory/disk cache keys — for the `AsyncImage`. */
    fun buildRequest(request: CardArtRequest): ImageRequest {
        val key = cacheKey(request)
        return ImageRequest
            .Builder(context)
            .data(request)
            .memoryCacheKey(key)
            .diskCacheKey(key)
            .build()
    }

    /** The cache key for a request: its primary resolved URL (also what the mapper feeds Coil). */
    private fun cacheKey(request: CardArtRequest): String = source.primaryUrl(request)

    private fun build(policy: CardArtCachePolicy): ImageLoader {
        val builder =
            ImageLoader
                .Builder(context)
                .components {
                    // The request is handed to Coil as a CardArtRequest (not a URL) and stays one all
                    // the way to CardArtFetcher, which resolves it and walks the candidate URLs with a
                    // stable cache key. A Mapper here would flatten it to a single URL and there would
                    // be no fallback.
                    add(CardArtKeyer(source))
                    add(CardArtFetcher.Factory(source, networkFetcherFactory))
                    add(networkFetcherFactory)
                }.memoryCache(memoryCache)
        if (policy.usesDiskCache) {
            builder.diskCache {
                DiskCache
                    .Builder()
                    .directory(diskCacheDirectory)
                    .maxSizeBytes(DISK_CACHE_MAX_BYTES)
                    .build()
            }
        } else {
            // Memory-only: no disk cache attached at all, so ImageLoader.diskCache is null and nothing
            // is ever written to storage.
            builder.diskCache(null)
        }
        return builder.build()
    }

    internal companion object {
        /** Logcat tag for [logWarning]'s Android wiring (see `cardArtModule`). */
        const val LOG_TAG = "CardImageLoader"

        /** Cache subdirectory + memory share, applied by the platform edge (see `cardArtModule`). */
        const val DISK_CACHE_DIR = "card_art"
        private const val DISK_CACHE_MAX_BYTES = 512L * 1024 * 1024 // 512 MB ceiling for warmed art
        const val MEMORY_CACHE_PERCENT = 0.20
    }
}
