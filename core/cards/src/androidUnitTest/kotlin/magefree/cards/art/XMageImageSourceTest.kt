package magefree.cards.art

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * URL-resolution tests for [ScryfallImageSource], asserting the exact strings ported from XMage's
 * `ScryfallImageSource` (upstream ref `e0fe4b6f6a`) against known cards. No network is touched.
 */
class XMageImageSourceTest {
    private val source = ScryfallImageSource()

    @Test
    fun `basic front card matches XMage's api image url`() {
        // XMage's own worked example: https://api.scryfall.com/cards/xln/121/en?format=image
        val request = CardArtRequest("XLN", "121", CardArtFace.FRONT, CardArtSize.LARGE)

        val urls = source.resolve(request)

        assertEquals(
            listOf(
                "https://api.scryfall.com/cards/xln/121/en?format=image",
                "https://api.scryfall.com/cards/xln/121?format=image&include_variations=true",
            ),
            urls,
        )
        assertEquals("https://api.scryfall.com/cards/xln/121/en?format=image", source.primaryUrl(request))
    }

    @Test
    fun `set code is lower-cased`() {
        val urls = source.resolve(CardArtRequest("DOM", "1", size = CardArtSize.LARGE))
        assertTrue(urls.all { it.contains("/cards/dom/") })
    }

    @Test
    fun `small size appends version small to every url`() {
        val urls = source.resolve(CardArtRequest("XLN", "121", size = CardArtSize.SMALL))

        assertEquals(
            listOf(
                "https://api.scryfall.com/cards/xln/121/en?format=image&version=small",
                "https://api.scryfall.com/cards/xln/121?format=image&version=small&include_variations=true",
            ),
            urls,
        )
    }

    @Test
    fun `art crop asks Scryfall for the illustration alone`() {
        // Not an upstream size — upstream's client draws whole cards, so it never asks for one. It is
        // the image the board's own tier wants, and Scryfall serves it from the same endpoint. A
        // *different picture*, not a crop of the small one, so it is its own cache entry.
        val urls = source.resolve(CardArtRequest("XLN", "121", size = CardArtSize.ART_CROP))

        assertEquals(
            listOf(
                "https://api.scryfall.com/cards/xln/121/en?format=image&version=art_crop",
                "https://api.scryfall.com/cards/xln/121?format=image&version=art_crop&include_variations=true",
            ),
            urls,
        )
    }

    @Test
    fun `dfc back face appends face back - grounded in XMage direct-link table`() {
        // XMage's directDownloadLinks uses exactly this shape, e.g. sld/1543/en?format=image&face=back.
        // Delver of Secrets // Insectile Aberration (ISD #51).
        val back = CardArtRequest("ISD", "51", CardArtFace.BACK, CardArtSize.LARGE)

        assertEquals(
            listOf(
                "https://api.scryfall.com/cards/isd/51/en?format=image&face=back",
                "https://api.scryfall.com/cards/isd/51?format=image&include_variations=true&face=back",
            ),
            source.resolve(back),
        )
    }

    @Test
    fun `front and back of same printing differ only by face param`() {
        val front = source.primaryUrl(CardArtRequest("ELD", "90", CardArtFace.FRONT, CardArtSize.LARGE))
        val back = source.primaryUrl(CardArtRequest("ELD", "90", CardArtFace.BACK, CardArtSize.LARGE))

        assertEquals("https://api.scryfall.com/cards/eld/90/en?format=image", front)
        assertEquals("https://api.scryfall.com/cards/eld/90/en?format=image&face=back", back)
    }

    @Test
    fun `split card resolves as a single front image`() {
        // Fire // Ice (APC #128) is one physical card — one image, front face.
        val urls = source.resolve(CardArtRequest("APC", "128", CardArtFace.FRONT, CardArtSize.LARGE))
        assertEquals("https://api.scryfall.com/cards/apc/128/en?format=image", urls.first())
        assertTrue(urls.none { it.contains("face=back") })
    }

    @Test
    fun `collector number variant suffixes are transformed to scryfall unicode`() {
        // transformCardNumberFromXmageToScryfall: * -> star, + -> dagger, Ph -> phi.
        assertEquals(
            "https://api.scryfall.com/cards/4ed/134†/en?format=image",
            source.primaryUrl(CardArtRequest("4ED", "134+", size = CardArtSize.LARGE)),
        )
        assertEquals(
            "https://api.scryfall.com/cards/war/180★/en?format=image",
            source.primaryUrl(CardArtRequest("WAR", "180*", size = CardArtSize.LARGE)),
        )
        assertEquals(
            "https://api.scryfall.com/cards/nph/1Φ/en?format=image",
            source.primaryUrl(CardArtRequest("NPH", "1Ph", size = CardArtSize.LARGE)),
        )
    }

    @Test
    fun `non-english language is used in the localized base url`() {
        val russian = ScryfallImageSource(language = "ru")
        assertEquals(
            "https://api.scryfall.com/cards/xln/121/ru?format=image",
            russian.primaryUrl(CardArtRequest("XLN", "121", size = CardArtSize.LARGE)),
        )
    }

    // ---- tokens, which have no collector number to be found by --------------------------------------

    @Test
    fun `a token is looked up by name, in the derived token set first`() {
        // Upstream leaves a token's `cardNumber` empty, so the set-plus-number identity is not there.
        // Its table's own worked example is `RNA/Zombie` -> https://api.scryfall.com/cards/trna/3, and
        // the derived request resolves to the same card by name.
        val urls = source.resolve(tokenArtRequest("RNA", "Zombie Token", CardArtSize.LARGE)!!)

        assertEquals(
            listOf(
                "https://api.scryfall.com/cards/named?exact=Zombie&set=trna&format=image",
                "https://api.scryfall.com/cards/named?exact=Zombie&set=rna&format=image",
            ),
            urls,
        )
    }

    @Test
    fun `the bare set is the fallback, because some sets are already token sets`() {
        // Measured across upstream's table: almost every entry that is not `t` + the set code points at
        // the set code itself — the promo and supplemental sets, where the token lives in the same set.
        // Both are offered, in that order, through the same fallback list an ordinary card already uses.
        val urls = source.resolve(tokenArtRequest("P04", "Spirit Token")!!)

        assertTrue("the derived token set is tried first", urls.first().contains("set=tp04"))
        assertTrue("the bare set is the fallback", urls.last().contains("set=p04"))
    }

    @Test
    fun `XMage's own Token suffix is not part of the name anything else knows`() {
        // `CardImageUtils` strips it upstream for exactly this reason.
        assertEquals("Zombie", tokenArtRequest("RNA", "Zombie Token")!!.tokenName)
        assertEquals("Germ", tokenArtRequest("CMA", "Germ")!!.tokenName)
    }

    @Test
    fun `a token name is percent-encoded, so a two-word token is still one query parameter`() {
        val urls = source.resolve(tokenArtRequest("GRN", "Bird Illusion Token")!!)

        assertTrue(urls.first(), urls.first().startsWith("https://api.scryfall.com/cards/named?exact=Bird%20Illusion&set=tgrn"))
    }

    @Test
    fun `a token asks for the same sizes an ordinary card does`() {
        assertTrue(source.resolve(tokenArtRequest("RNA", "Zombie Token")!!).first().endsWith("&version=small"))
        assertTrue(
            source.resolve(tokenArtRequest("RNA", "Zombie Token", CardArtSize.ART_CROP)!!).first().endsWith("&version=art_crop"),
        )
    }

    @Test
    fun `a token with no set has no request at all`() {
        // The board falls back to the placeholder rather than asking Scryfall for a set it does not have.
        assertEquals(null, tokenArtRequest("", "Zombie Token"))
        assertEquals(null, tokenArtRequest("RNA", " Token"))
    }
}
