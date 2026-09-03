package magefree.designsystem.text

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import magefree.designsystem.R

/*
 * The mana and tap symbols, drawn from a font the app ships.
 *
 * **Shipped rather than downloaded.** Upstream's client fetches symbol art from Scryfall at runtime
 * and caches it per user, because it may not redistribute it. The Mana font may be redistributed, so
 * this carries it instead: nothing to fetch on first run, no cache to manage, nothing to go stale, and
 * it works on a phone that has never been online.
 *
 * **Licensing, which is not all one thing.** The Mana project's stylesheets are MIT — but the *font*,
 * which is the part shipped here, is **SIL OFL 1.1**, and the symbol artwork itself remains
 * © Wizards of the Coast. OFL expressly permits bundling a font inside software, including
 * commercially, provided the notice travels with it and the font is not sold on its own. Upstream
 * ships no licence file at all, so this project carries the notice itself — see
 * `docs/third-party-notices.md`.
 *
 * **How the font is put together**, read out of `mana.css` rather than guessed. Each ordinary symbol
 * is one glyph in the Private Use Area: `{W}` is ``, `{T}` is ``. A **hybrid has no glyph
 * of its own** — the stylesheet composes it from the two halves at 0.55em, offset up-left and
 * down-right, and that is reproduced rather than approximated here, because a hybrid drawn as one of
 * its halves is a different cost.
 *
 * The codepoints are written as `Char(0x…)` on purpose. They are Private Use Area characters: pasted
 * literally they are invisible in a diff, survive nothing that touches encoding, and cannot be checked
 * against the stylesheet by eye.
 */

/** The shipped Mana font. */
internal val ManaFontFamily = FontFamily(Font(R.font.mana))

/**
 * Every symbol that is a single glyph, by the code the server puts between braces.
 *
 * Transcribed from `mana.css`'s `content:` rules, whose values are the same numbers in CSS notation (`\e600`).
 */
private val SingleGlyphs: Map<String, Char> =
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

/**
 * The symbols drawn as two glyphs, and which two.
 *
 * A hybrid is a choice — `{W/U}` is white *or* blue, `{2/W}` is two generic *or* one white, `{G/P}` is
 * green *or* two life. The pairs are built from the single-glyph table rather than by splitting the
 * code's characters, because the halves are not always what those characters say: every Phyrexian
 * pairing uses one shared glyph whatever colour it is combined with.
 *
 * Both orders are listed because upstream sends both — `{W/U}` and `{U/W}` are the same symbol printed
 * one way or the other, and a lookup that knew only one would fall back to text for half of them.
 */
private val HybridGlyphs: Map<String, Pair<Char, Char>> =
    buildMap {
        val colours = listOf("W", "U", "B", "R", "G")
        colours.forEach { first ->
            colours.forEach { second ->
                if (first != second) {
                    put("$first/$second", SingleGlyphs.getValue(first) to SingleGlyphs.getValue(second))
                }
            }
        }
        colours.forEach { colour ->
            put("2/$colour", SingleGlyphs.getValue("2") to SingleGlyphs.getValue(colour))
            put("C/$colour", SingleGlyphs.getValue("C") to SingleGlyphs.getValue(colour))
            put("$colour/P", SingleGlyphs.getValue(colour) to SingleGlyphs.getValue("P"))
        }
    }

/** Every code this font can draw. Exposed for the test that checks the set against the wire's own. */
internal val ManaSymbolCodes: Set<String> get() = SingleGlyphs.keys + HybridGlyphs.keys

/**
 * The symbol for [code], or `null` when the font has none.
 *
 * `null` is the honest answer rather than a blank: [SymbolText] falls back to the literal token, so a
 * code from a set newer than the font still reads as what the server sent.
 */
fun manaSymbolSlot(code: String): SymbolSlot? {
    SingleGlyphs[code]?.let { glyph ->
        return { modifier -> ManaGlyph(glyph = glyph, modifier = modifier) }
    }
    HybridGlyphs[code]?.let { (first, second) ->
        return { modifier -> HybridGlyph(first = first, second = second, modifier = modifier) }
    }
    return null
}

/** One glyph, at the size the text laid out for it. */
@Composable
private fun ManaGlyph(
    glyph: Char,
    modifier: Modifier = Modifier,
    scale: TextUnit = 1.em,
) {
    Text(
        text = glyph.toString(),
        modifier = modifier,
        style = TextStyle(fontFamily = ManaFontFamily, fontSize = scale, textAlign = TextAlign.Center),
        color = Color.Unspecified,
        maxLines = 1,
    )
}

/**
 * A hybrid: two smaller glyphs, one up-left and one down-right, as the stylesheet draws them.
 *
 * Drawing only one half would be a different cost; drawing them side by side would read as two
 * separate symbols in a row.
 */
@Composable
private fun HybridGlyph(
    first: Char,
    second: Char,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        ManaGlyph(glyph = first, scale = HYBRID_GLYPH_SCALE.em, modifier = Modifier.align(Alignment.TopStart))
        ManaGlyph(glyph = second, scale = HYBRID_GLYPH_SCALE.em, modifier = Modifier.align(Alignment.BottomEnd))
    }
}

/** Each half of a split symbol, near the stylesheet's own `font-size: 0.55em`. */
private const val HYBRID_GLYPH_SCALE = 0.62f
