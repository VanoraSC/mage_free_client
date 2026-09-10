package magefree.designsystem.card

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How large a title strip is written.
 *
 * **This is the only place the type can be tested at all.** A rendered assertion is worthless here:
 * Robolectric stubs the font metrics — a glyph is one pixel wide whatever its size, and a text node
 * reports the height of the box it was handed rather than of the lines in it — so a test claiming a
 * name had grown or wrapped would pass at any font size including zero. Three such tests were written
 * against the component and deleted. The arithmetic is a function, so the arithmetic is what is pinned;
 * how it *looks* is an eyes-on check on a real device.
 */
class BoardCardTypeTest {
    @Test
    fun `type grows with the strip it is written in`() {
        // The whole reason this is a share rather than a dp. A card's width comes from the board — a
        // fraction of the screen, taken down by whatever is crowding it — so one fixed size fills a
        // small card's strip and floats in a large one's.
        val small = density.titleTypeFor(20.dp)
        val large = density.titleTypeFor(40.dp)

        assertTrue("${large.name} should be larger than ${small.name}", large.name > small.name)
        assertTrue("${large.cost} should be larger than ${small.cost}", large.cost > small.cost)
    }

    @Test
    fun `it scales exactly, so a card looks the same at every size`() {
        // Proportional and not merely monotonic: a strip twice as tall is written twice as large, which
        // is what makes two cards of different sizes read as the same design rather than as two.
        val small = density.titleTypeFor(20.dp)
        val large = density.titleTypeFor(40.dp)

        assertEquals(2f, large.name.value / small.name.value, TOLERANCE)
        assertEquals(2f, large.cost.value / small.cost.value, TOLERANCE)
    }

    @Test
    fun `two lines of the name fit inside the strip`() {
        // The name may wrap, and both lines have to be inside the strip they are written in — a second
        // line drawn past the bottom of the title bar would be drawn over the art.
        val strip = 30f
        val type = density.titleTypeFor(strip.dp)

        val used = type.line.value * BOARD_CARD_NAME_LINES
        assertTrue("$BOARD_CARD_NAME_LINES lines came to $used in a strip of $strip", used <= strip)
    }

    @Test
    fun `a mana symbol is half the strip across`() {
        // **Pete's own figure, and the reason the cost is asked for as a diameter.** A disc is drawn
        // slightly over the line it sits in, so a caller passing the wanted diameter in as a font size
        // gets a circle a fifth too big — the conversion lives in `symbolFontSizeFor` and this is what
        // it has to come out as.
        val strip = 40f
        val type = density.titleTypeFor(strip.dp)

        val diameter = symbolDiameterOf(type.cost.value)
        assertEquals("a symbol should be half of a $strip strip", strip * 0.50f, diameter, TOLERANCE)
    }

    @Test
    fun `the cost is drawn close to the name's size, neither dwarfing it nor lost beside it`() {
        // Half the strip against a name sized for two lines of it, so they sit within a few percent of
        // each other. Three quarters was the first figure and it made a three-symbol cost the loudest
        // thing on the card; a symbol much *smaller* than the name would be a colour nobody can pick
        // out at board size, which is the one thing the cost is there for.
        val type = density.titleTypeFor(30.dp)

        val ratio = type.cost.value / type.name.value
        assertTrue("cost ${type.cost} against name ${type.name} is a ratio of $ratio", ratio in 0.8f..1.4f)
    }

    /**
     * The diameter a symbol is drawn at, given its font size — the inverse of `symbolFontSizeFor`.
     *
     * Written out here rather than exported, so this test fails if the ratio inside the renderer moves
     * without this being reconsidered. That is the point: the two have to agree, and a test that asked
     * the renderer for its own constant would agree with itself.
     */
    private fun symbolDiameterOf(fontSize: Float): Float = fontSize * 1.2f

    private val density = Density(density = 1f, fontScale = 1f)

    private companion object {
        const val TOLERANCE = 0.01f
    }
}
