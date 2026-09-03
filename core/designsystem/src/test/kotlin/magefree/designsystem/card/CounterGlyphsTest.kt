package magefree.designsystem.card

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which counter kinds the shipped font can name.
 *
 * The table is transcribed by hand from `mana.css` and checked by hand against `CounterType`, so the
 * assertions worth having are about the **shape of the mapping** rather than about the font's
 * contents: that the boost counters are matched by their generated form rather than listed, that an
 * uneven boost is told from an even one, and that an unknown kind resolves to nothing rather than to
 * something that merely sounds similar.
 */
class CounterGlyphsTest {
    @Test
    fun `the counters a game is mostly made of are named`() {
        // These four carry nearly every counter a normal game puts on the board.
        listOf("+1/+1", "-1/-1", "loyalty", "charge").forEach { name ->
            assertNotNull("no glyph for the $name counter", counterGlyph(name))
        }
    }

    @Test
    fun `boost counters are matched by shape, not by a list`() {
        // Upstream generates these names in `CardUtil.getBoostCountAsStr`, so every magnitude is a
        // different string and no table could hold them all.
        listOf("+1/+1", "+2/+2", "+3/+3", "+12/+12").forEach { name ->
            assertEquals("$name should be the even plus counter", counterGlyph("+1/+1"), counterGlyph(name))
        }
        listOf("-1/-1", "-2/-2", "-7/-7").forEach { name ->
            assertEquals("$name should be the even minus counter", counterGlyph("-1/-1"), counterGlyph(name))
        }
    }

    @Test
    fun `an uneven boost is a different symbol from an even one`() {
        // `mana.css` draws them differently, and so does the game: a `+1/+1` is a straight buff, a
        // `+1/+0` changes the creature's shape. Collapsing them would say the wrong thing.
        val even = counterGlyph("+1/+1")
        val uneven = counterGlyph("+1/+0")
        assertNotNull(uneven)
        assertEquals(false, even == uneven)

        assertEquals(uneven, counterGlyph("+2/+0"))
        assertEquals(counterGlyph("-1/-0"), counterGlyph("-0/-1"))
    }

    @Test
    fun `a mixed-sign boost takes its symbol from the first half`() {
        // `-1/+1` shrinks power and grows toughness. It is uneven by definition, and the leading sign
        // is the one the name is read from.
        assertEquals(counterGlyph("-1/-0"), counterGlyph("-1/+1"))
        assertEquals(counterGlyph("+1/-0"), counterGlyph("+1/-1"))
    }

    @Test
    fun `a kind the font has never heard of resolves to nothing`() {
        // The kinds are an open set upstream — hundreds of them, and a new set can add one — so this
        // is the normal case rather than the error case. The chip still shows its colour and count.
        assertNull(counterGlyph("moonsilver"))
        assertNull(counterGlyph(""))
        assertNull(counterGlyph("+1"))
        assertNull(counterGlyph("1/1"))
    }

    @Test
    fun `nothing is mapped that upstream does not actually call a counter`() {
        // The stylesheet has art for several things that are not counter kinds at all. Mapping one of
        // them would put a picture on a counter that never carries it, and nothing would ever notice.
        listOf("goad", "damage", "skull", "paw", "skeleton").forEach { name ->
            assertNull("$name is stylesheet art, not a CounterType", counterGlyph(name))
        }
    }
}
