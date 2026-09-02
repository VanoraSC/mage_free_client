package magefree.designsystem.card

import androidx.compose.ui.graphics.Color
import magefree.designsystem.board.contrastRatio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Counter colour.
 *
 * The property a player can actually notice is that **a counter kind does not change colour while
 * they are looking at it**. Global consistency is not achievable — the kinds are an open set — and it
 * is not what is needed: the colour only has to say "this is a different kind from that one", with
 * the number carrying the precision.
 */
class CounterPaletteTest {
    @Test
    fun `the three kinds whose colour means something are fixed`() {
        val palette = CounterPalette()

        assertEquals(Color(0xFF4ADE80), palette.colorFor("+1/+1"))
        assertEquals(Color(0xFFEF4444), palette.colorFor("-1/-1"))
        assertEquals(Color(0xFFF5D67B), palette.colorFor("loyalty"))
    }

    @Test
    fun `a fixed kind never consumes a queue slot`() {
        val palette = CounterPalette()

        palette.colorFor("+1/+1")
        palette.colorFor("-1/-1")
        palette.colorFor("loyalty")

        assertEquals("a fixed kind took a colour from the queue", 0, palette.allocatedCount)
    }

    @Test
    fun `a kind keeps its colour for as long as the palette lives`() {
        val palette = CounterPalette()

        val first = palette.colorFor("poison")
        repeat(20) { palette.colorFor("kind-$it") }

        assertEquals("poison changed colour mid-game", first, palette.colorFor("poison"))
    }

    @Test
    fun `different kinds get different colours until the queue is exhausted`() {
        val palette = CounterPalette()
        val kinds = (1..10).map { "kind-$it" }

        val colours = kinds.map { palette.colorFor(it) }

        assertEquals("two kinds shared a colour before the queue ran out", colours.size, colours.toSet().size)
    }

    @Test
    fun `a queue colour is never one of the fixed three`() {
        val palette = CounterPalette()
        val fixed = setOf(Color(0xFF4ADE80), Color(0xFFEF4444), Color(0xFFF5D67B))

        (1..30).forEach { index ->
            val allocated = palette.colorFor("kind-$index")
            assertTrue(
                "an allocated colour collides with a fixed kind, so it reads as +1/+1 or loyalty",
                allocated !in fixed,
            )
        }
    }

    @Test
    fun `allocation order is by first sight, not by name`() {
        val ordered = CounterPalette()
        val reversed = CounterPalette()

        val first = ordered.colorFor("zebra")
        reversed.colorFor("aardvark")

        assertEquals("the first kind seen takes the head of the queue", first, reversed.colorFor("aardvark"))
    }

    @Test
    fun `the digit is always readable on its own fill`() {
        // The ring keeps the circle visible against any background; nothing else protects the number
        // from the fill it sits on, so the flip has to hold for every colour the palette can produce.
        val palette = CounterPalette()
        val produced =
            listOf("+1/+1", "-1/-1", "loyalty") + (1..30).map { "kind-$it" }

        produced.map(palette::colorFor).forEach { fill ->
            val digit = counterDigitColor(fill)
            val contrast = contrastRatio(digit, fill)
            assertTrue(
                "a $digit digit on $fill holds only $contrast, so the count is hard to read",
                contrast >= MIN_DIGIT_CONTRAST,
            )
        }
    }

    @Test
    fun `a light fill takes a dark digit and a dark fill a light one`() {
        assertEquals(Color.Black, counterDigitColor(Color.White))
        assertEquals(Color.White, counterDigitColor(Color.Black))
        assertNotEquals(counterDigitColor(Color.White), counterDigitColor(Color.Black))
    }

    private companion object {
        /** The WCAG minimum for large text, which is what a bold digit in a circle is. */
        const val MIN_DIGIT_CONTRAST = 3.0
    }
}
