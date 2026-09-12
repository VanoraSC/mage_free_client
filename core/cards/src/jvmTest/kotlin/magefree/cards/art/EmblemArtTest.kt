package magefree.cards.art

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Emblem art, from upstream's own table.
 *
 * The table is ported rather than derived, so what is worth pinning is the lookup — keyed exactly the
 * way upstream's `findTokenLink` keys it, so this finds an image where upstream's client does and nowhere
 * else — and that every entry is a printing the ordinary card path already knows how to fetch.
 */
class EmblemArtTest {
    @Test
    fun `an emblem resolves to the printing upstream's table names for it`() {
        // Liliana, the Last Hope's emblem, as the server sends it: upstream links `EMN/Emblem Liliana` to
        // Scryfall's cards/temn/9.
        assertEquals(
            CardArtRequest(setCode = "temn", collectorNumber = "9", size = CardArtSize.ART_CROP),
            emblemArtRequest(setCode = "EMN", name = "Emblem Liliana", size = CardArtSize.ART_CROP),
        )
    }

    @Test
    fun `the same emblem in another set is that set's printing`() {
        val m21 = emblemArtRequest(setCode = "M21", name = "Emblem Liliana")

        assertEquals("tm21" to "18", m21?.let { it.setCode to it.collectorNumber })
    }

    @Test
    fun `two emblems of one name in one set are told apart by image number, and neither answers to the bare name`() {
        assertEquals("78", emblemArtRequest(setCode = "CMM", name = "Emblem Chandra", imageNumber = 1)?.collectorNumber)
        assertEquals("79", emblemArtRequest(setCode = "CMM", name = "Emblem Chandra", imageNumber = 2)?.collectorNumber)
        // `findTokenLink` appends the number whenever it is not 0, so there is no bare `CMM/Emblem Chandra`
        // key to fall back to — guessing one of the two would be choosing a picture the server did not.
        assertNull(emblemArtRequest(setCode = "CMM", name = "Emblem Chandra"))
    }

    @Test
    fun `an emblem upstream has no image for resolves to nothing`() {
        // Dominaria United's Karn: upstream's table carries a TODO saying there is no official emblem card.
        assertNull(emblemArtRequest(setCode = "DMU", name = "Emblem Karn"))
    }

    @Test
    fun `a key is matched exactly, as upstream matches it`() {
        // Scryfall's own name for the card, and a set in the wrong case, are both not upstream's key.
        assertNull(emblemArtRequest(setCode = "EMN", name = "Liliana, the Last Hope Emblem"))
        assertNull(emblemArtRequest(setCode = "emn", name = "Emblem Liliana"))
    }

    @Test
    fun `an emblem's printing resolves through the ordinary card path`() {
        val request = emblemArtRequest(setCode = "EMN", name = "Emblem Liliana", size = CardArtSize.ART_CROP)!!

        assertEquals(
            "https://api.scryfall.com/cards/temn/9/en?format=image&version=art_crop",
            ScryfallImageSource().resolve(request).first(),
        )
    }

    @Test
    fun `every entry is an emblem key and a printing`() {
        // A guard on the port itself: 130 `put`s whose name begins `Emblem` at the pinned ref.
        assertEquals(130, EMBLEM_PRINTINGS.size)
        EMBLEM_PRINTINGS.forEach { (key, printing) ->
            val (set, number) = printing
            assertTrue(key, Regex("""[A-Z0-9]{3,4}/Emblem [^/]+(/\d+)?""").matches(key))
            assertTrue(key, set.isNotBlank() && set == set.lowercase())
            assertTrue(key, number.isNotBlank())
        }
    }
}
