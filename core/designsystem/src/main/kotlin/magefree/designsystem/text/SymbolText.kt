package magefree.designsystem.text

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp

/*
 * Mana and tap symbols, rendered inside the server's own text.
 *
 * **Every string the server sends carries them.** A mana cost is `{1}{G}`, not `1G`; a land's rules
 * text is `{T}: Add {G}.`; a payment prompt reads `Pay {2}{R}`. Those braces are not a placeholder
 * format we chose — they are what `ManaCost.getText()` produces upstream, and they arrive on every
 * card, every rules line and every prompt message.
 *
 * Until now the app drew them literally, so a player paying two red mana read the characters
 * `Pay {2}{R}`.
 *
 * **The design system knows how to place a symbol and nothing about how to draw one.** It has no image
 * dependency and is not getting one: the token is parsed here, and the picture comes from
 * [LocalSymbolSlots], which a host installs. Without a host the text falls back to the literal token —
 * which is what it looked like before, so a missing renderer degrades to today rather than to blank.
 */

/**
 * Draws one symbol, by its code — `"T"`, `"W"`, `"W/U"`, `"2/W"`.
 *
 * The same shape as `CardArtSlot`, for the same reason: the module that lays things out must not be
 * the module that loads images.
 */
typealias SymbolSlot = @Composable (Modifier) -> Unit

/**
 * How to draw each symbol beneath this point in the composition, or `null` for one nothing can draw.
 *
 * Static, because it changes only if something replaces it. The default is the shipped Mana font, so
 * every surface draws symbols without wiring; a resolver that returns null for a code falls back to
 * the literal token, which is what the app showed before any of this existed.
 */
val LocalSymbolSlots = staticCompositionLocalOf<(String) -> SymbolSlot?> { ::manaSymbolSlot }

/** One piece of a parsed string: literal text, or a symbol to be drawn. */
sealed interface SymbolChunk {
    /** Text with no symbols in it. */
    data class Literal(
        val text: String,
    ) : SymbolChunk

    /**
     * A symbol.
     *
     * @property code the part inside the braces, exactly as sent — `"T"`, `"W/U"`, `"2/W"`. Slashes
     *   are kept, because they are part of the symbol's identity: `{W/U}` is one hybrid symbol and not
     *   two.
     */
    data class Symbol(
        val code: String,
    ) : SymbolChunk
}

/**
 * Splits [text] into literals and symbols.
 *
 * A brace that never closes is literal text, not a symbol — server messages contain prose, and half a
 * token is far more likely to be a stray character than an intent to draw something.
 */
fun parseSymbolText(text: String): List<SymbolChunk> {
    if (!text.contains('{')) return if (text.isEmpty()) emptyList() else listOf(SymbolChunk.Literal(text))

    val chunks = mutableListOf<SymbolChunk>()
    val literal = StringBuilder()
    var index = 0
    while (index < text.length) {
        val open = text.indexOf('{', index)
        if (open < 0) {
            literal.append(text, index, text.length)
            break
        }
        val close = text.indexOf('}', open + 1)
        if (close < 0) {
            literal.append(text, index, text.length)
            break
        }
        val code = text.substring(open + 1, close)
        if (code.isEmpty()) {
            // `{}` is not a symbol; keep it as it arrived rather than dropping characters.
            literal.append(text, index, close + 1)
        } else {
            literal.append(text, index, open)
            if (literal.isNotEmpty()) {
                chunks += SymbolChunk.Literal(literal.toString())
                literal.clear()
            }
            chunks += SymbolChunk.Symbol(code)
        }
        index = close + 1
    }
    if (literal.isNotEmpty()) chunks += SymbolChunk.Literal(literal.toString())
    return chunks
}

/**
 * [text] with its symbols drawn, and the rest of it as text.
 *
 * @param text the server's own string. Never rewritten — the braces are removed only where a symbol
 *   is actually drawn in their place.
 * @param style the text style; the symbols are sized from its font size so they sit on the line.
 * @param color the text colour. Symbols carry their own.
 * @param maxLines / @param overflow as [androidx.compose.material3.Text].
 * @param modifier the [Modifier] for the text.
 */
@Composable
fun SymbolText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    textAlign: TextAlign? = null,
) {
    val slots = LocalSymbolSlots.current
    val chunks = parseSymbolText(text)

    // Sized from the line's own font size, so a symbol grows with the text it sits in rather than
    // being pinned to a dp that is right in one place and wrong in the next.
    val fontSize = if (style.fontSize.isSpecified) style.fontSize else DefaultSymbolSize
    val symbolSize = fontSize * SYMBOL_SCALE

    val drawable = chunks.filterIsInstance<SymbolChunk.Symbol>().mapNotNull { chunk -> slots(chunk.code)?.let { chunk.code to it } }.toMap()

    val annotated: AnnotatedString =
        buildAnnotatedString {
            chunks.forEach { chunk ->
                when (chunk) {
                    is SymbolChunk.Literal -> append(chunk.text)
                    is SymbolChunk.Symbol ->
                        if (drawable.containsKey(chunk.code)) {
                            appendInlineContent(id = chunk.code, alternateText = "{${chunk.code}}")
                        } else {
                            // Nothing can draw it, so it stays exactly as the server sent it. A symbol
                            // silently vanishing would take real information with it.
                            append("{${chunk.code}}")
                        }
                }
            }
        }

    Text(
        text = annotated,
        modifier = modifier,
        style = style,
        color = color,
        maxLines = maxLines,
        overflow = overflow,
        textAlign = textAlign ?: TextAlign.Unspecified,
        inlineContent =
            drawable.mapValues { (_, slot) ->
                InlineTextContent(
                    placeholder =
                        Placeholder(
                            width = symbolSize,
                            height = symbolSize,
                            // Centred on the text rather than on the line: a line's box includes
                            // leading, so aligning to it rides the symbol above the words it belongs
                            // to — which is exactly how the first cut of this looked.
                            placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
                        ),
                ) {
                    // The slot fills the box it was given. It has to: the symbol is drawn to the space
                    // it is handed, and a slot left to size itself drew a small mark in the corner of
                    // a large placeholder, which read as both undersized and badly spaced.
                    slot(Modifier.fillMaxSize())
                }
            },
    )
}

/**
 * How large a symbol is relative to the text it sits in.
 *
 * Slightly *over* the line, which is what printed cards and `mana.css` both do — the stylesheet's disc
 * is `1.3em` of a `0.95em` glyph, a shade over 1.2× the surrounding text. A symbol drawn at exactly the
 * font size reads as small, because a disc's full width is doing the work a letter's x-height does.
 */
private const val SYMBOL_SCALE = 1.2f

/** Used only when a style carries no font size at all. */
private val DefaultSymbolSize = 14.sp
