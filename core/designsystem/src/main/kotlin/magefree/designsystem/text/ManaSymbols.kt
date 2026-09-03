package magefree.designsystem.text

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath

/*
 * The shipped Mana font, drawn the way a mana symbol is actually printed.
 *
 * **The font is only half of a symbol.** Its glyphs are the foreground — the sun, the drop, the digit,
 * the tap arrow — and nothing else; the project's own stylesheet supplies the rest, giving each one a
 * coloured disc behind it (`background-color` plus `border-radius: 1em`) and splitting that disc
 * diagonally for a hybrid. Drawing the glyph alone produces what the first cut of this produced: a
 * small dark mark floating in white space, unreadable as `{R}` and indistinguishable from `{G}` at a
 * glance.
 *
 * So the disc is drawn here, from `mana.css`'s own colour values, and the glyph is placed on it by its
 * measured ink bounds rather than by font metrics. Measuring is what makes it sit centred: a glyph's
 * line box carries ascent and descent that have nothing to do with where its ink is, and laying out by
 * that box is what pushed the first version high and small inside its own placeholder.
 */

/**
 * Every glyph in the font this app draws, by the code the server puts between braces.
 *
 * Transcribed from `mana.css`'s `content:` rules, whose values are the same numbers in CSS notation (`\e600`).
 */
private val Glyphs: Map<String, Char> =
    mapOf(
        "W" to Char(0xE600),
        "U" to Char(0xE601),
        "B" to Char(0xE602),
        "R" to Char(0xE603),
        "G" to Char(0xE604),
        "0" to Char(0xE605),
        "1" to Char(0xE606),
        "2" to Char(0xE607),
        "3" to Char(0xE608),
        "4" to Char(0xE609),
        "5" to Char(0xE60A),
        "6" to Char(0xE60B),
        "7" to Char(0xE60C),
        "8" to Char(0xE60D),
        "9" to Char(0xE60E),
        "10" to Char(0xE60F),
        "11" to Char(0xE610),
        "12" to Char(0xE611),
        "13" to Char(0xE612),
        "14" to Char(0xE613),
        "15" to Char(0xE614),
        // 16 through 20 are not contiguous with the rest - they were added later, at 0xE62A.
        "16" to Char(0xE62A),
        "17" to Char(0xE62B),
        "18" to Char(0xE62C),
        "19" to Char(0xE62D),
        "20" to Char(0xE62E),
        "X" to Char(0xE615),
        "Y" to Char(0xE616),
        "Z" to Char(0xE617),
        "S" to Char(0xE619),
        "C" to Char(0xE904),
        "E" to Char(0xE907),
        // Upstream sends `{P}` for the generic Phyrexian symbol; the font names the same glyph `h`.
        "P" to Char(0xE618),
        "H" to Char(0xE618),
        // The stylesheet calls these `tap` and `untap`; upstream's codes are `T` and `Q`.
        "T" to Char(0xE61A),
        "Q" to Char(0xE61B),
    )

private val PhyrexianGlyph = Char(0xE618)

/** The five colours, in the order upstream writes them. */
private val Colours = listOf("W", "U", "B", "R", "G")

/**
 * Disc colours, taken from `mana.css` rather than chosen.
 *
 * The stylesheet keeps two sets and uses them in different places: a solid symbol's disc is a little
 * more saturated than either half of a split one. Both are reproduced, because using one set for both
 * makes hybrids read as heavier than the plain symbols beside them.
 */
private val SolidDisc: Map<String, Color> =
    mapOf(
        "W" to Color(0xFFF0F2C0),
        "U" to Color(0xFFB5CDE3),
        "B" to Color(0xFFACA29A),
        "R" to Color(0xFFDB8664),
        "G" to Color(0xFF93B483),
    )

private val SplitDisc: Map<String, Color> =
    mapOf(
        "W" to Color(0xFFFDFBCE),
        "U" to Color(0xFFBCDAF7),
        "B" to Color(0xFFA7999E),
        "R" to Color(0xFFF19B79),
        "G" to Color(0xFF9FCBA6),
        "C" to Color(0xFFD0C6BB),
    )

/** Generic and colourless costs, and everything that is not a colour at all: `{T}`, `{X}`, `{S}`. */
private val NeutralDisc = Color(0xFFBEB9B2)

/** The foreground, on every disc. `mana.css` sets it once, for all of them. */
private val SymbolInk = Color(0xFF111111)

/** What one symbol is made of. */
private sealed interface SymbolArt {
    /** A disc of one colour with one glyph centred on it. */
    data class Single(
        val glyph: Char,
        val disc: Color,
        val fill: Float = SINGLE_GLYPH_FILL,
    ) : SymbolArt

    /**
     * A disc split along the anti-diagonal, with a glyph in each half.
     *
     * Which half is which is not decoration: `{2/W}` is *two generic or one white*, and the halves say
     * so in the order they are written.
     */
    data class Split(
        val first: Char,
        val second: Char,
        val top: Color,
        val bottom: Color,
    ) : SymbolArt
}

/**
 * Every code the app can draw, and what it draws.
 *
 * Built rather than listed, because the hybrids are systematic and a hand-written table of seventy
 * entries is a table with a typo in it. Both orders of every pair are registered: upstream sends
 * `{W/U}` and `{U/W}` for the same printed symbol, and knowing only one would fall back to text for
 * half of them, which reads as an intermittent bug rather than a missing feature.
 */
private val Arts: Map<String, SymbolArt> =
    buildMap {
        Glyphs.forEach { (code, glyph) ->
            put(code, SymbolArt.Single(glyph = glyph, disc = SolidDisc[code] ?: NeutralDisc))
        }
        // The bare Phyrexian symbol is drawn larger than an ordinary glyph, as the stylesheet does
        // with `transform: scale(1.2)` — it is a thin outline and shrinks away otherwise.
        put("P", SymbolArt.Single(PhyrexianGlyph, NeutralDisc, fill = PHYREXIAN_GLYPH_FILL))
        put("H", SymbolArt.Single(PhyrexianGlyph, NeutralDisc, fill = PHYREXIAN_GLYPH_FILL))

        Colours.forEach { first ->
            Colours.forEach { second ->
                if (first != second) {
                    put(
                        "$first/$second",
                        SymbolArt.Split(
                            first = Glyphs.getValue(first),
                            second = Glyphs.getValue(second),
                            top = SplitDisc.getValue(first),
                            bottom = SplitDisc.getValue(second),
                        ),
                    )
                }
            }
        }
        Colours.forEach { colour ->
            put(
                "2/$colour",
                SymbolArt.Split(
                    first = Glyphs.getValue("2"),
                    second = Glyphs.getValue(colour),
                    top = SplitDisc.getValue("C"),
                    bottom = SplitDisc.getValue(colour),
                ),
            )
            put(
                "C/$colour",
                SymbolArt.Split(
                    first = Glyphs.getValue("C"),
                    second = Glyphs.getValue(colour),
                    top = SplitDisc.getValue("C"),
                    bottom = SplitDisc.getValue(colour),
                ),
            )
            // Phyrexian mana is *not* a split symbol, however its code is spelled. `{G/P}` is one
            // Phyrexian glyph on a green disc — the alternative payment is life, which has no glyph
            // and is not drawn. Building it as a hybrid, which the first cut did, put a green tree
            // and a Phyrexian mark side by side and said something the card does not.
            put(
                "$colour/P",
                SymbolArt.Single(
                    glyph = PhyrexianGlyph,
                    disc = SolidDisc.getValue(colour),
                    fill = PHYREXIAN_GLYPH_FILL,
                ),
            )
        }
    }

/** Every code this font can draw. Exposed for the test that checks the set against the wire's own. */
internal val ManaSymbolCodes: Set<String> get() = Arts.keys

/**
 * The symbol for [code], or `null` when the font has none.
 *
 * `null` is the honest answer rather than a blank: [SymbolText] falls back to the literal token, so a
 * code from a set newer than the font still reads as what the server sent.
 */
fun manaSymbolSlot(code: String): SymbolSlot? {
    val art = Arts[code] ?: return null
    return { modifier -> ManaSymbol(art = art, modifier = modifier) }
}

/** One symbol, filling whatever space it is handed. */
@Composable
private fun ManaSymbol(
    art: SymbolArt,
    modifier: Modifier = Modifier,
) {
    // The font as a `Typeface`, so the glyph can be measured and placed by its ink rather than by the
    // line box a `Text` would give it. Resolved through Compose's own resolver so this module still
    // needs nothing beyond the font resource it already ships.
    val typeface = rememberManaTypeface()
    val paint = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG) }
    val bounds = remember { android.graphics.Rect() }

    Canvas(modifier = modifier) {
        val diameter = minOf(size.width, size.height)
        if (diameter <= 0f) return@Canvas
        val centre = Offset(size.width / 2f, size.height / 2f)
        val radius = diameter / 2f

        when (art) {
            is SymbolArt.Single -> {
                drawCircle(color = art.disc, radius = radius, center = centre)
                drawFontGlyph(typeface, paint, bounds, art.glyph, centre, diameter * art.fill, SymbolInk)
            }

            is SymbolArt.Split -> {
                drawCircle(color = art.top, radius = radius, center = centre)
                // The half below the anti-diagonal, clipped to the disc. `mana.css` draws this as a
                // hard-stopped 135° gradient, which is the same edge by another route.
                val lower =
                    Path().apply {
                        moveTo(centre.x - radius, centre.y + radius)
                        lineTo(centre.x + radius, centre.y - radius)
                        lineTo(centre.x + radius, centre.y + radius)
                        close()
                    }
                clipPath(Path().apply { addOval(circleBounds(centre, radius)) }) {
                    drawPath(lower, art.bottom)
                }
                val offset = radius * SPLIT_GLYPH_OFFSET
                val half = diameter * SPLIT_GLYPH_FILL
                drawFontGlyph(typeface, paint, bounds, art.first, centre - Offset(offset, offset), half, SymbolInk)
                drawFontGlyph(typeface, paint, bounds, art.second, centre + Offset(offset, offset), half, SymbolInk)
            }
        }
    }
}

/** The square the disc is inscribed in. */
private fun circleBounds(
    centre: Offset,
    radius: Float,
) = androidx.compose.ui.geometry.Rect(
    left = centre.x - radius,
    top = centre.y - radius,
    right = centre.x + radius,
    bottom = centre.y + radius,
)

/**
 * How much of the disc an ordinary glyph covers.
 *
 * `mana.css` gets the same proportion by setting the glyph to `0.95em` inside a `1.3em` disc. Here it
 * is stated as what it is — a fraction of the disc — because the glyph is placed by measurement and
 * the font's em size never enters into it.
 */
private const val SINGLE_GLYPH_FILL = 0.58f

/** Phyrexian is a thin outline and is drawn larger, as `transform: scale(1.2)` does upstream. */
private const val PHYREXIAN_GLYPH_FILL = 0.70f

/** Each half of a split symbol, near the stylesheet's own `font-size: 0.55em`. */
private const val SPLIT_GLYPH_FILL = 0.42f

/** How far each half sits from the centre, along the diagonal it is split on. */
private const val SPLIT_GLYPH_OFFSET = 0.40f
