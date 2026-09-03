package magefree.designsystem.text

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import magefree.designsystem.R

/*
 * The shipped Mana font, as a source of pictures rather than of letters.
 *
 * It arrived for mana costs, but its glyph range is much wider than that: it also draws the evergreen
 * keywords, the counter kinds, the card types and the double-faced markers. Everywhere this app was
 * previously drawing a placeholder — three-letter badges reading `FLY` and `DTH`, a counter told apart
 * only by its colour — there is a glyph for the real thing already sitting in the file.
 *
 * **A glyph is placed by its measured ink, never by the line box.** A font's baseline and metrics
 * describe where *letters* sit. An icon font's glyphs are pictures that happen to be encoded as
 * characters, and their ink can sit anywhere relative to that baseline. Laying one out as text puts it
 * off-centre and at the wrong size — which is precisely what the first cut of the mana symbols did.
 * Measuring is a few lines and it is exact, so everything here measures.
 */

internal val ManaFontFamily = FontFamily(Font(R.font.mana))

/**
 * The font as a `Typeface`, or `null` where the platform will not give one up.
 *
 * Resolved through Compose's own resolver, so this module still needs nothing beyond the font resource
 * it already ships. `null` is a real outcome under a stub renderer, and every caller treats it as
 * "draw nothing" rather than as "draw a guess".
 */
@Composable
internal fun rememberManaTypeface(): android.graphics.Typeface? {
    val resolver = LocalFontFamilyResolver.current
    return remember(resolver) { resolver.resolve(ManaFontFamily).value as? android.graphics.Typeface }
}

/**
 * One glyph from the font, centred in whatever space it is given and tinted [color].
 *
 * @param glyph the codepoint, from [ManaFontGlyphs] or one of the tables beside it.
 * @param color the ink. Unlike a mana symbol — which carries its own disc and ink — a badge or a
 *   counter is drawn in a colour the surface chooses, so it is a parameter here.
 * @param fill how much of the box the glyph's longer side covers, leaving the rest as breathing room.
 * @param modifier the [Modifier] for the glyph; it fills what it is handed.
 */
@Composable
internal fun ManaFontGlyph(
    glyph: Char,
    color: Color,
    modifier: Modifier = Modifier,
    fill: Float = 1f,
) {
    val typeface = rememberManaTypeface()
    val paint = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG) }
    val bounds = remember { android.graphics.Rect() }
    Canvas(modifier = modifier) {
        val box = minOf(size.width, size.height)
        if (box <= 0f) return@Canvas
        drawFontGlyph(
            typeface = typeface,
            paint = paint,
            bounds = bounds,
            glyph = glyph,
            centre = Offset(size.width / 2f, size.height / 2f),
            target = box * fill,
            color = color,
        )
    }
}

/**
 * Draws [glyph] with its **ink** centred on [centre] and its longer side [target] across.
 *
 * Measured rather than assumed: `drawText` places a glyph by its baseline, and a font's baseline says
 * where letters sit, not where an icon's picture is. So the glyph is measured once at a reference
 * size, scaled to the size wanted, and measured again — `getTextBounds` is not exactly linear in text
 * size — and then positioned from that box.
 *
 * If the platform reports no bounds at all, nothing is drawn. That is a stub renderer rather than a
 * real one, which is what happens under Robolectric, and drawing a guess there would put a wrong
 * picture in front of whoever was looking.
 */
internal fun DrawScope.drawFontGlyph(
    typeface: android.graphics.Typeface?,
    paint: android.graphics.Paint,
    bounds: android.graphics.Rect,
    glyph: Char,
    centre: Offset,
    target: Float,
    color: Color,
) {
    if (typeface == null || target <= 0f) return
    val text = glyph.toString()
    paint.typeface = typeface
    paint.color = color.toArgb()

    paint.textSize = GLYPH_REFERENCE_SIZE
    paint.getTextBounds(text, 0, 1, bounds)
    val measured = maxOf(bounds.width(), bounds.height())
    if (measured <= 0) return

    paint.textSize = GLYPH_REFERENCE_SIZE * (target / measured)
    paint.getTextBounds(text, 0, 1, bounds)

    drawIntoCanvas { canvas ->
        canvas.nativeCanvas.drawText(
            text,
            centre.x - bounds.exactCenterX(),
            centre.y - bounds.exactCenterY(),
            paint,
        )
    }
}

/** Measured at a size large enough that rounding in the reported bounds does not matter. */
private const val GLYPH_REFERENCE_SIZE = 200f
