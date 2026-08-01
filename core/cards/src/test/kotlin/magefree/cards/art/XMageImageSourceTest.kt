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
}
