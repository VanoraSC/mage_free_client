package magefree.designsystem.card

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.times
import magefree.designsystem.text.symbolFontSizeFor

/*
 * How large a board card's title strip is written.
 *
 * **Arithmetic, kept apart from the drawing, because the drawing cannot be tested here.** Robolectric
 * stubs the font metrics — every glyph is one pixel wide whatever the size, and a text node reports the
 * height of the box it was handed rather than of the lines in it — so a rendered assertion that a name
 * grew, or wrapped, passes at any font size including zero. Three such tests were written and deleted.
 * What *can* be pinned is the sizing itself, so the sizing is a function.
 *
 * **Everything is a share of the strip, not a dp.** A card's width is derived from the board — a share
 * of the screen, taken down by whatever is crowding it — so one fixed size fills a small card's strip
 * and floats in a large one's. Type that is a fraction of the strip makes a card look the same at every
 * size it is drawn at, which is the whole point of deriving the size in the first place.
 */

/**
 * The type a title strip is written in.
 *
 * @property name the card's name.
 * @property line the leading between two lines of it, since a name may take two.
 * @property cost the *font* size for the mana cost, already converted from the diameter its discs are
 *   wanted at — see [symbolFontSizeFor]. A caller passing a diameter straight in as a font size gets a
 *   circle a fifth too big.
 */
data class BoardCardTitleType(
    val name: TextUnit,
    val line: TextUnit,
    val cost: TextUnit,
)

/**
 * The type for a strip [strip] tall.
 *
 * **Sized so two lines of the name fit inside the strip with their leading.** The same type is used
 * whether a name wraps or not, so a one-line name and a two-line name on the card beside it are the
 * same size — scaling with the card rather than with the text is what keeps a row of permanents
 * looking like a row rather than like a ransom note.
 */
fun Density.titleTypeFor(strip: Dp): BoardCardTitleType {
    val name = (strip * NAME_HEIGHT_SHARE).toSp()
    return BoardCardTitleType(
        name = name,
        line = name * NAME_LINE_SPACING,
        // **Asked for as a diameter.** Three quarters of the strip: large enough to read a colour at a
        // glance, small enough that a five-symbol cost still fits beside a name.
        cost = symbolFontSizeFor(diameter = (strip * MANA_DIAMETER_SHARE).toSp()),
    )
}

/** How many lines of a name a strip may hold, which is what [NAME_HEIGHT_SHARE] is derived from. */
const val BOARD_CARD_NAME_LINES: Int = 2

/**
 * The name's size, as a share of its strip.
 *
 * [BOARD_CARD_NAME_LINES] of it, at [NAME_LINE_SPACING] leading, come to 92% of the strip — so two
 * lines fit with a little room and are not pressed against the edges.
 */
private const val NAME_HEIGHT_SHARE = 0.40f

private const val NAME_LINE_SPACING = 1.15f

/** A mana symbol's diameter, as a share of the strip. Pete's own figure. */
private const val MANA_DIAMETER_SHARE = 0.75f
