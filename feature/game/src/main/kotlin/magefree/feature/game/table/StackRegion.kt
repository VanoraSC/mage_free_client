package magefree.feature.game.table

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import magefree.designsystem.board.BoardSurface
import magefree.designsystem.board.BoardTypography
import magefree.designsystem.card.BoardCard
import magefree.designsystem.card.CounterPalette

/*
 * The stack, on the centre line.
 *
 * ```
 *  ┌──────┬───────────┬──────────────────────────────────┐
 *  │ opp  │           │  [ their permanents ]            │
 *  │      │  opponent ├──────────────────────────────────┤
 *  │ 20   │   lands   │  ┌────┐ Lightning Bolt           │  ← the stack
 *  │      │           │  │ 🗲  │ deals 3 damage to any…   │
 *  │      ├───────────┤  └─┬──┘                          │
 *  │ 14   │   your    ├────┼─────────────────────────────┤
 *  │      │   lands   │  [ your permanents ]  ←──────────┘
 *  └──────┴───────────┴──────────────────────────────────┘
 * ```
 *
 * **It goes on the centre line because that is where it is.** The board already leaves a gap there so
 * the two front rows read as two armies rather than one crowd; the stack opens in that gap and closes
 * again when it empties. That is the board's existing rule that no empty region holds height, and the
 * movement it costs is honest under §7.3 — a spell arriving *is* a game action.
 *
 * It is also the only place from which the arrows can work. A panel over the board — which is what
 * "give the stack the screen" first suggests — covers the permanents the arrows point at, and a stack
 * that cannot show what it is targeting has lost the more useful half of what it knows.
 *
 * **Top first**, which is the order it resolves in and the order [tableStack] hands it over in. The
 * object about to happen is the one nearest the left, where reading starts.
 *
 * **The text beside the card is the server's own**, and it is the game-aware text rather than the
 * printing's — the ability as it exists now, after whatever has modified it. It is here because the
 * Board tier draws no card text: on the battlefield that is right, and for the one object the whole
 * game is currently waiting on it is not.
 */

/**
 * The stack region.
 *
 * @param stack the objects, top first, from [tableStack].
 * @param cardWidth how wide to draw each object's card.
 * @param palette the board's live counter palette, so a kind keeps one colour across the whole board.
 * @param artFor resolves each object's art from the printing the server named.
 * @param anchors where each object is drawn, so an arrow can start from it.
 * @param onInspect called with an object's id when it is pressed — the same press every other card on
 *   this board answers to.
 * @param modifier the [Modifier] for the region.
 */
@Composable
internal fun StackRegion(
    stack: List<TableStackObject>,
    cardWidth: Dp,
    palette: CounterPalette,
    artFor: TableArtResolver?,
    anchors: BoardAnchors,
    onInspect: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    if (stack.isEmpty()) return

    Row(
        modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag(StackTestTags.REGION),
        horizontalArrangement = Arrangement.spacedBy(ObjectGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        stack.forEach { entry ->
            Row(
                modifier = Modifier.fillMaxHeight().testTag(StackTestTags.entry(entry.id)),
                horizontalArrangement = Arrangement.spacedBy(TextGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BoardCard(
                    state = entry.state,
                    width = cardWidth,
                    art = artFor?.invoke(entry.art, entry.state.card),
                    onTap = onInspect?.let { inspect -> { inspect(entry.id) } },
                    counterPalette = palette,
                    // The arrow leaves from the card, not from the text beside it: the card is the
                    // object, and the text is what it says.
                    modifier = anchors.anchorModifier(entry.id),
                )

                // A vanilla creature spell has no rules text, and draws none rather than an empty
                // column that makes the row look broken.
                if (entry.rules.isNotEmpty()) {
                    Column(
                        modifier = Modifier.width(TextWidth).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(LineGap),
                    ) {
                        entry.rules.forEach { line ->
                            Text(
                                text = line,
                                style = BoardTypography.cardName,
                                color = BoardSurface.onSurface,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Test tags for the stack, which is told apart by position rather than by any text of its own. */
object StackTestTags {
    const val REGION: String = "stack"

    /** One object on it, by the id an arrow starts from and a press names. */
    fun entry(objectId: String): String = "stack-$objectId"
}

/** Between two objects on the stack — wider than between cards in a row, since each is a card plus its text. */
private val ObjectGap = 16.dp

/** Between an object's card and what it says. */
private val TextGap = 8.dp

private val LineGap = 2.dp

/**
 * How wide an object's rules text is allowed to be.
 *
 * Wide enough for a sentence of Magic rules text to break two or three times rather than twenty, and
 * narrow enough that two objects on the stack both fit across a landscape board before it scrolls.
 */
private val TextWidth = 190.dp
