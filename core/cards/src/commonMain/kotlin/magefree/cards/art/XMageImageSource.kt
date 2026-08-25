package magefree.cards.art

/**
 * Resolves a card's identity ([CardArtRequest]) to the image URL(s) to try, in order.
 *
 * The URL construction is **ported from XMage's own desktop image-download source**
 * (`org.mage.plugins.card.dl.sources.ScryfallImageSource` in `Mage.Client`, pinned upstream ref
 * `e0fe4b6f6a`). We reuse its *source/URL logic* only — not its bulk-download UX — per story 0031.
 */
interface XMageImageSource {
    /**
     * Candidate URLs for [request], most-specific first. The Coil fetcher ([CardArtFetcher]) tries
     * them in order and falls back to the next on a miss (a non-2xx). Never empty for a well-formed
     * request.
     */
    fun resolve(request: CardArtRequest): List<String>

    /**
     * The primary (first) URL for [request] — a convenience over [resolve], and the **cache key** for
     * the request: the entry is keyed on the request's identity, not on whichever candidate answered.
     */
    fun primaryUrl(request: CardArtRequest): String = resolve(request).first()
}

/**
 * The Scryfall image source, XMage's default/primary source.
 *
 * ### What is ported (from `ScryfallImageSource.innerGenerateURL`, upstream ref `e0fe4b6f6a`)
 * - **Front / basic card by API image redirect** — upstream builds
 *   `https://api.scryfall.com/cards/{set}/{cn}/{lang}?format=image` as the base URL and
 *   `https://api.scryfall.com/cards/{set}/{cn}?format=image&include_variations=true` as the
 *   alternative (the no-language form with `include_variations=true`, upstream's documented
 *   workaround for promos/variations that 404 on the localized path — see the upstream comments
 *   citing issues #6829 and the `4ed/134†` case). We reproduce both, in that order.
 * - **Set code** — `formatSetName` → `ScryfallImageSupportCards.findScryfallSetCode(set)`, which is
 *   `set.toLowerCase(Locale.ENGLISH)`. Ported in [scryfallSetCode] as the locale-invariant no-arg
 *   `lowercase()`, which is `Locale.ROOT` on the JVM — same result for ASCII set codes.
 * - **Collector-number transform** — `ScryfallApiCard.transformCardNumberFromXmageToScryfall`:
 *   trailing `*`→`★`, `+`→`†`, `Ph`→`Φ` (XMage's ASCII spellings of Scryfall's unicode variant
 *   suffixes). Ported verbatim in [transformCollectorNumber].
 * - **Small quality** — `ScryfallImageSourceSmall.innerModifyUrlString` rewrites `format=image` →
 *   `format=image&version=small`. Ported in [applySize]. (Its companion `/large/`→`/small/` rewrite
 *   only applies to already-resolved scryfall.io CDN links, which the API-path construction here
 *   does not emit, so only the `version=small` param is relevant.)
 *
 * ### What is grounded/adapted (and why)
 * - **Back face of a DFC/MDFC** — upstream `getFaceImageUrl` performs a *live* Scryfall API JSON
 *   lookup and reads `card_faces[1].image_uris.large`. That is a network round-trip, unsuitable for
 *   a hermetic, URL-only resolver. Scryfall's image endpoint natively supports `&face=back`, and
 *   **XMage's own direct-link table uses exactly that** — e.g.
 *   `https://api.scryfall.com/cards/sld/1543/en?format=image&face=back`
 *   (`ScryfallImageSupportCards.directDownloadLinks`, upstream ref `e0fe4b6f6a`). We therefore append
 *   `&face=back` to the same base/alt URLs for [CardArtFace.BACK], which yields the same back-face
 *   image XMage's JSON path resolves, without the round-trip.
 * - **Split cards** are a single physical card with one image; they are never a "second side" in
 *   XMage. A split card (or an individual split half from the catalog) therefore resolves as a normal
 *   [CardArtFace.FRONT] request — one image for the whole card.
 * - **Various art** is a per-printing concept: each printing has its own collector number, so a
 *   [CardArtRequest] built from a specific [magefree.cards.model.CardPrinting] already selects the
 *   right art. No separate handling is needed.
 *
 * Not ported: the 300 MB Scryfall bulk-data database optimization (`prepareBulkData`/`analyseBulkData`)
 * — an offline-catalog performance trick for desktop mass-downloads, orthogonal to on-demand loading.
 */
class ScryfallImageSource(
    private val language: String = DEFAULT_LANGUAGE,
) : XMageImageSource {
    override fun resolve(request: CardArtRequest): List<String> {
        val set = scryfallSetCode(request.setCode)
        val cn = transformCollectorNumber(request.collectorNumber)
        val faceSuffix = if (request.face == CardArtFace.BACK) "&face=back" else ""

        // Ported from ScryfallImageSource.innerGenerateURL: localized base, then the no-language
        // include_variations alternative used as a fallback for promos/variations.
        val base = "$API_BASE/$set/$cn/$language?format=image$faceSuffix"
        val alternative = "$API_BASE/$set/$cn?format=image&include_variations=true$faceSuffix"

        return listOf(base, alternative)
            .map { applySize(it, request.size) }
            .distinct()
    }

    private fun applySize(
        url: String,
        size: CardArtSize,
    ): String =
        when (size) {
            CardArtSize.LARGE -> url
            // ScryfallImageSourceSmall.innerModifyUrlString: format=image -> format=image&version=small
            CardArtSize.SMALL -> url.replaceFirst("format=image", "format=image&version=small")
        }

    private companion object {
        const val API_BASE = "https://api.scryfall.com/cards"
        const val DEFAULT_LANGUAGE = "en"

        /** `ScryfallImageSupportCards.findScryfallSetCode` — the xmage set code, lower-cased. */
        fun scryfallSetCode(xmageCode: String): String = xmageCode.lowercase()

        /** `ScryfallApiCard.transformCardNumberFromXmageToScryfall` — ASCII variant suffix → unicode. */
        fun transformCollectorNumber(cardNumber: String): String =
            when {
                cardNumber.endsWith("*") -> cardNumber.dropLast(1) + "★"
                cardNumber.endsWith("+") -> cardNumber.dropLast(1) + "†"
                cardNumber.endsWith("Ph") -> cardNumber.dropLast(2) + "Φ"
                else -> cardNumber
            }
    }
}
