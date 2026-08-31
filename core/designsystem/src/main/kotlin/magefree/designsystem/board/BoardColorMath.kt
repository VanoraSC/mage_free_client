package magefree.designsystem.board

import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/*
 * The colour arithmetic the board's token requirements are stated in.
 *
 * The board carries meaning in shades of grey, so "these two greys are different enough" has to be a
 * number rather than an opinion. Two different numbers are needed, because they answer two different
 * questions:
 *
 * - **Perceptual lightness (CIE L*)** answers "can the eye separate these two surfaces?". It is
 *   uniform across the range, so a step of N near black looks like a step of N near white. The WCAG
 *   contrast ratio is not usable for this: it compresses badly in dark ranges, where the whole board
 *   lives, and would report two clearly distinct dark greys as nearly identical.
 * - **The WCAG contrast ratio** answers "is this mark visible against that background?". That is the
 *   question it was designed for, and it is the right one for a signal drawn over a surface.
 *
 * [colorDifference] is CIE76 ΔE*ab — the plain Euclidean distance in L*a*b*. It is the crudest of the
 * ΔE formulas and it is deliberately what is used here: the question it answers is "could a player
 * mistake this signal for that one", where the colours are far apart by construction and the extra
 * precision of ΔE2000 would change no verdict.
 */

/** The D65 white point L\*a\*b\* is measured against, matching sRGB's own illuminant. */
private const val WHITE_X = 0.95047
private const val WHITE_Y = 1.0
private const val WHITE_Z = 1.08883

/** The linear-segment threshold and slope of the sRGB transfer function. */
private const val SRGB_LINEAR_THRESHOLD = 0.04045
private const val SRGB_LINEAR_SLOPE = 12.92

/** CIE's ε (216/24389) and the linear-segment slope (841/108) of the L\* transfer function. */
private const val LAB_EPSILON = 216.0 / 24389.0
private const val LAB_SLOPE = 841.0 / 108.0
private const val LAB_OFFSET = 4.0 / 29.0

/** One channel of an sRGB colour, undone back to linear light. */
private fun linearize(channel: Float): Double {
    val c = channel.toDouble()
    return if (c <= SRGB_LINEAR_THRESHOLD) c / SRGB_LINEAR_SLOPE else ((c + 0.055) / 1.055).pow(2.4)
}

/** CIE XYZ (D65) for an sRGB colour. */
private fun Color.toXyz(): Triple<Double, Double, Double> {
    val r = linearize(red)
    val g = linearize(green)
    val b = linearize(blue)
    return Triple(
        0.4124 * r + 0.3576 * g + 0.1805 * b,
        0.2126 * r + 0.7152 * g + 0.0722 * b,
        0.0193 * r + 0.1192 * g + 0.9505 * b,
    )
}

/** The L\* transfer function, with its linear segment near zero so the cube root stays well-behaved. */
private fun labF(t: Double): Double = if (t > LAB_EPSILON) t.pow(1.0 / 3.0) else LAB_SLOPE * t + LAB_OFFSET

/**
 * Relative luminance (the Y of CIE XYZ), on 0..1. This is the quantity the WCAG contrast ratio is
 * built from, not a perceptual lightness — see [perceptualLightness].
 */
internal fun Color.relativeLuminance(): Double = toXyz().second

/**
 * Perceptual lightness (CIE L\*), on 0..100. Uniform: a difference of N looks like the same size of
 * step wherever it falls on the scale, which is what makes it the right measure for a grey ramp whose
 * steps all sit in the dark end.
 */
internal fun Color.perceptualLightness(): Double = 116.0 * labF(relativeLuminance() / WHITE_Y) - 16.0

/**
 * The WCAG contrast ratio between two colours, from 1.0 (identical) to 21.0 (black against white).
 * Order-independent.
 */
internal fun contrastRatio(
    a: Color,
    b: Color,
): Double {
    val first = a.relativeLuminance()
    val second = b.relativeLuminance()
    val lighter = maxOf(first, second)
    val darker = minOf(first, second)
    return (lighter + 0.05) / (darker + 0.05)
}

/** The absolute difference in perceptual lightness between two colours. */
internal fun lightnessDifference(
    a: Color,
    b: Color,
): Double = abs(a.perceptualLightness() - b.perceptualLightness())

/** CIE76 ΔE\*ab: how far apart two colours are as a whole, hue and lightness together. */
internal fun colorDifference(
    a: Color,
    b: Color,
): Double {
    val (aL, aA, aB) = a.toLab()
    val (bL, bA, bB) = b.toLab()
    return sqrt((aL - bL).pow(2) + (aA - bA).pow(2) + (aB - bB).pow(2))
}

/** L\*a\*b\* for an sRGB colour, against the D65 white point. */
private fun Color.toLab(): Triple<Double, Double, Double> {
    val (x, y, z) = toXyz()
    val fx = labF(x / WHITE_X)
    val fy = labF(y / WHITE_Y)
    val fz = labF(z / WHITE_Z)
    return Triple(116.0 * fy - 16.0, 500.0 * (fx - fy), 200.0 * (fy - fz))
}
