package magefree.cards.art

import coil3.ImageLoader
import coil3.Uri
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.key.Keyer
import coil3.network.HttpException
import coil3.request.Options
import coil3.toUri

/**
 * The Coil [Fetcher] for a [CardArtRequest] — the caller [XMageImageSource.resolve]'s KDoc describes.
 *
 * It walks the resolved candidates **in order** and falls back to the next one when the current one
 * answers a non-2xx (a [HttpException]) or yields nothing. That is what makes the
 * `include_variations=true` alternative real rather than documented-but-dead: promo and variation
 * printings that 404 on the localized path are served by it instead of rendering a placeholder
 * forever (story 0043, defect B).
 *
 * ### Cache-key stability
 * The delegate network fetcher keys the disk cache on `options.diskCacheKey ?: url`. Every candidate
 * is therefore fetched with the **request's own** key (the primary URL) pinned into [Options], never
 * with the URL that happened to answer. One card = one cache entry, whichever candidate served the
 * bytes — otherwise a card would re-download whenever the winning candidate changed, and a prefetch's
 * `isCached` probe (which keys on the primary URL) would never see the warmed entry.
 *
 * Anything that is not a candidate miss — an offline [okio.IOException], a cancellation — propagates
 * immediately rather than burning a second request against a source that is not answering.
 */
internal class CardArtFetcher(
    private val request: CardArtRequest,
    private val options: Options,
    private val imageLoader: ImageLoader,
    private val source: XMageImageSource,
    private val delegate: Fetcher.Factory<Uri>,
) : Fetcher {
    override suspend fun fetch(): FetchResult? {
        val candidates = source.resolve(request)
        val keyed = options.copy(diskCacheKey = options.diskCacheKey ?: candidates.first())
        var lastMiss: HttpException? = null

        for (url in candidates) {
            val fetcher = delegate.create(url.toUri(), keyed, imageLoader) ?: continue
            try {
                return fetcher.fetch() ?: continue
            } catch (miss: HttpException) {
                // This candidate does not exist (typically the 404 on the localized path for a
                // promo/variation printing). Try the next one.
                lastMiss = miss
            }
        }

        // Every candidate missed: surface the last miss so Coil produces its ErrorResult and the UI
        // degrades to the design-system placeholder (0031/0032), exactly as before.
        lastMiss?.let { throw it }
        return null
    }

    /** Builds a [CardArtFetcher] for every [CardArtRequest], delegating the bytes to [delegate]. */
    class Factory(
        private val source: XMageImageSource,
        private val delegate: Fetcher.Factory<Uri>,
    ) : Fetcher.Factory<CardArtRequest> {
        override fun create(
            data: CardArtRequest,
            options: Options,
            imageLoader: ImageLoader,
        ): Fetcher =
            CardArtFetcher(
                request = data,
                options = options,
                imageLoader = imageLoader,
                source = source,
                delegate = delegate,
            )
    }
}

/**
 * The memory-cache key for a [CardArtRequest]: its primary resolved URL. A custom data type needs its
 * own [Keyer] to be memory-cacheable, and keying on the request's primary URL (not on the candidate
 * that answered) keeps the memory and disk keys identical — see [CardArtFetcher].
 */
internal class CardArtKeyer(
    private val source: XMageImageSource,
) : Keyer<CardArtRequest> {
    override fun key(
        data: CardArtRequest,
        options: Options,
    ): String = source.primaryUrl(data)
}
