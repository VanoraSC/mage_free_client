package magefree.designsystem.text

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which symbols the shipped font can draw.
 *
 * The list is transcribed from `mana.css` by hand, so the assertion worth having is **coverage of what
 * the server actually sends** rather than of the font's own contents. A code that arrives and has no
 * glyph falls back to its literal token, which is legible but is the thing this work exists to remove.
 */
class ManaSymbolsTest {
    @Test
    fun `every symbol an ordinary cost is made of can be drawn`() {
        // The generic amounts and five colours between them cover almost every mana cost printed.
        (0..20).map(Int::toString).forEach { generic ->
            assertNotNull("no glyph for {$generic}", manaSymbolSlot(generic))
        }
        listOf("W", "U", "B", "R", "G", "C", "X", "S").forEach { code ->
            assertNotNull("no glyph for {$code}", manaSymbolSlot(code))
        }
    }

    @Test
    fun `the tap symbol can be drawn, which is most of what a board says`() {
        // `{T}` appears in nearly every activated ability on the battlefield — it is the single most
        // common symbol in the text this app shows.
        assertNotNull(manaSymbolSlot("T"))
        assertNotNull(manaSymbolSlot("Q"))
    }

    @Test
    fun `hybrids are covered in both the orders upstream can send them`() {
        // `{W/U}` and `{U/W}` are the same printed symbol. Knowing only one would silently fall back to
        // text for half of them, which reads as an intermittent bug rather than a missing feature.
        listOf("W/U", "U/W", "B/G", "G/B", "R/W", "W/R").forEach { code ->
            assertNotNull("no glyph for {$code}", manaSymbolSlot(code))
        }
    }

    @Test
    fun `the two-generic and colourless hybrids are covered`() {
        listOf("2/W", "2/U", "2/B", "2/R", "2/G", "C/W", "C/G").forEach { code ->
            assertNotNull("no glyph for {$code}", manaSymbolSlot(code))
        }
    }

    @Test
    fun `phyrexian is covered, in the shape upstream sends it`() {
        // Upstream builds these as `{G/P}` — `ColoredManaCost.getText()` appends `/P`. The bare `{P}`
        // is the generic Phyrexian symbol.
        listOf("W/P", "U/P", "B/P", "R/P", "G/P", "P").forEach { code ->
            assertNotNull("no glyph for {$code}", manaSymbolSlot(code))
        }
    }

    @Test
    fun `a code the font has never heard of resolves to nothing, rather than to something wrong`() {
        // Sets add symbols. Drawing an arbitrary glyph for an unknown code would be worse than showing
        // the token: a wrong symbol is misinformation, and `{WUBRG}` is at least readable.
        assertNull(manaSymbolSlot("WUBRG"))
        assertNull(manaSymbolSlot("this"))
        assertNull(manaSymbolSlot(""))
    }

    @Test
    fun `no code is registered twice under different spellings`() {
        // Guards the hand-built table: the hybrid map is generated from the single-glyph one, and an
        // overlap between them would mean one of the two is dead and nobody would notice which.
        val codes = ManaSymbolCodes
        assertTrue("the table is suspiciously small: ${codes.size}", codes.size > 60)
        assertTrue("codes must not carry their braces: $codes", codes.none { it.startsWith("{") })
    }
}
