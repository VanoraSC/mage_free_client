package magefree.designsystem.board

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The board's colour requirements, held as numbers.
 *
 * A palette is usually judged by eye and then quietly eroded — a step darkened here, a highlight
 * desaturated there, each change defensible on its own and the set no longer readable. These are the
 * two properties that make the board work, so they are asserted rather than remembered.
 */
class BoardColorsTest {
    @Test
    fun `the colour maths agrees with the published values for black and white`() {
        // Anchors the whole file: if these are wrong, every threshold below is measuring the wrong
        // thing while still passing.
        assertEquals(0.0, BoardTestColors.BLACK.perceptualLightness(), 0.01)
        assertEquals(100.0, BoardTestColors.WHITE.perceptualLightness(), 0.01)
        assertEquals(21.0, contrastRatio(BoardTestColors.BLACK, BoardTestColors.WHITE), 0.01)
        assertEquals(
            "mid grey sits near L* 53.6, not near 50 — the scale is perceptual, not linear in sRGB",
            53.59,
            BoardTestColors.MID_GREY.perceptualLightness(),
            0.05,
        )
    }

    @Test
    fun `the value ramp rises, so a surface is never darker than the one it sits on`() {
        BoardSurface.valueRamp.zipWithNext().forEach { (lower, upper) ->
            assertTrue(
                "$upper must be lighter than $lower: the ramp is ordered deepest-first and the " +
                    "board's layering reads off that order",
                upper.perceptualLightness() > lower.perceptualLightness(),
            )
        }
    }

    @Test
    fun `adjacent surfaces are separable by value alone`() {
        BoardSurface.valueRamp.zipWithNext().forEach { (lower, upper) ->
            val step = lightnessDifference(lower, upper)
            assertTrue(
                "$lower and $upper are $step apart in L*, under the ${BoardSurface.MIN_LIGHTNESS_STEP} " +
                    "the board needs. The board carries zone boundaries in value alone, so two " +
                    "surfaces this close stop being two surfaces.",
                step >= BoardSurface.MIN_LIGHTNESS_STEP,
            )
        }
    }

    @Test
    fun `the ramp carries no hue, so nothing on the ground competes with a signal`() {
        BoardSurface.valueRamp.forEach { surface ->
            assertTrue(
                "$surface is not neutral. A tinted ground is a ground that argues with the " +
                    "information colours drawn on it.",
                surface.red == surface.green && surface.green == surface.blue,
            )
        }
    }

    @Test
    fun `text reads on every surface`() {
        BoardSurface.valueRamp.forEach { surface ->
            val contrast = contrastRatio(BoardSurface.onSurface, surface)
            assertTrue(
                "onSurface holds only $contrast against $surface",
                contrast >= BoardSurface.MIN_TEXT_CONTRAST,
            )
            val mutedContrast = contrastRatio(BoardSurface.onSurfaceMuted, surface)
            assertTrue(
                "onSurfaceMuted holds only $mutedContrast against $surface",
                mutedContrast >= BoardSurface.MIN_MUTED_CONTRAST,
            )
        }
    }

    @Test
    fun `every signal reads against every ground it can be drawn on`() {
        BoardSignal.all.forEach { signal ->
            BoardSurface.signalGrounds.forEach { ground ->
                val contrast = contrastRatio(signal, ground)
                assertTrue(
                    "$signal holds only $contrast against $ground, under the " +
                        "${BoardSignal.MIN_SIGNAL_CONTRAST} a meaningful graphical element needs. A " +
                        "signal that has to be looked for is not a signal.",
                    contrast >= BoardSignal.MIN_SIGNAL_CONTRAST,
                )
            }
        }
    }

    @Test
    fun `no two signals can be mistaken for each other`() {
        val pairs =
            BoardSignal.all.flatMapIndexed { index, first ->
                BoardSignal.all.drop(index + 1).map { second -> first to second }
            }
        assertEquals("every pair of the six signals is compared", 15, pairs.size)
        pairs.forEach { (first, second) ->
            val difference = colorDifference(first, second)
            assertTrue(
                "$first and $second are only $difference apart. They mean different things and are " +
                    "read at card size, so a shade between them is not enough.",
                difference >= BoardSignal.MIN_SIGNAL_DIFFERENCE,
            )
        }
    }

    @Test
    fun `a signal is saturated, so colour on the board always means something`() {
        BoardSignal.all.forEach { signal ->
            val channels = listOf(signal.red, signal.green, signal.blue)
            assertTrue(
                "$signal is too close to neutral to read as information against a grey ground",
                channels.max() - channels.min() > 0.2f,
            )
        }
    }
}
