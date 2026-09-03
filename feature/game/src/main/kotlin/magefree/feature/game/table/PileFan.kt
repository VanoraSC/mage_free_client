package magefree.feature.game.table

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import magefree.designsystem.board.BoardSurface
import magefree.designsystem.board.BoardTypography
import magefree.designsystem.card.BoardCard
import magefree.designsystem.card.CounterPalette
import kotlin.math.roundToInt

/*
 * A stack of identical permanents, drawn as a short fan and a count.
 *
 * **Three faces, then a number.** A fan that grew with the pile would defeat the point of piling: ten
 * Plains fanned is ten Plains' worth of board, just overlapped. Three is enough to read the stack as a
 * stack rather than as one card, and past three the exact number is what the player wants, not more
 * pictures of the same land — so the fan caps and a count takes over.
 *
 * **The count appears only when it is telling you something.** One, two and three are visible by
 * looking; a badge over them would be saying what the picture already says. It appears at four, which
 * is the first number a glance cannot give you.
 *
 * That is also why tapping a stack is legible without any extra explanation: four Plains show three
 * faces and a `×4`; tap one and the untapped stack has three faces and *no* badge, with a tapped stack
 * of one beside it. The badge vanishing is not a special case — three is simply back to being countable.
 */

/**
 * One stack: up to [PILE_FAN_LIMIT] card faces, offset, with a count when there are more.
 *
 * @param pile the identical permanents.
 * @param width the card width, as everything else on the board is drawn at.
 * @param palette the board's counter palette, so a counter kind keeps its colour across every card.
 * @param artFor resolves the card's art from the printing the server named.
 * @param onInspect called with [TablePile.actionId] when the stack is tapped — any member, because
 *   they are identical and the game draws no distinction between them.
 * @param modifier the [Modifier] for the stack.
 */
@Composable
internal fun PileFan(
    pile: TablePile,
    width: Dp,
    palette: CounterPalette,
    artFor: TableArtResolver?,
    onInspect: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val faces = minOf(pile.count, PILE_FAN_LIMIT)
    val permanent = pile.representative

    Box(modifier = modifier.testTag(BattlefieldTestTags.pile(pile.actionId))) {
        FanLayout(step = PileFanStep) {
            repeat(faces) {
                BoardCard(
                    state = permanent.state,
                    width = width,
                    art = artFor?.invoke(permanent.art, permanent.state.card),
                    onTap = onInspect?.let { inspect -> { inspect(pile.actionId) } },
                    counterPalette = palette,
                )
            }
        }

        if (pile.count > PILE_FAN_LIMIT) {
            Text(
                text = "×${pile.count}",
                style = BoardTypography.counter,
                color = BoardSurface.onSurface,
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .background(BoardSurface.zone, CountShape)
                        .padding(horizontal = CountPadding)
                        .testTag(BattlefieldTestTags.pileCount(pile.actionId)),
            )
        }
    }
}

/**
 * Lays children out overlapping, each [step] further along than the one before it.
 *
 * A layout rather than offset modifiers because the fan has to **claim** the space it covers: a stack
 * placed with offsets measures as one card wide, so the next thing along the row is drawn on top of
 * it. It also has to work for tapped cards without knowing about them — a tapped card's footprint is
 * landscape, and measuring the children is how the fan finds that out rather than being told.
 *
 * Later children draw over earlier ones, so the last face is the one fully visible.
 */
@Composable
private fun FanLayout(
    step: Dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        if (placeables.isEmpty()) return@Layout layout(0, 0) {}

        val stepPx = step.toPx().roundToInt()
        val width = placeables.maxOf { it.width } + stepPx * (placeables.size - 1)
        val height = placeables.maxOf { it.height }
        layout(width, height) {
            placeables.forEachIndexed { index, placeable ->
                placeable.placeRelative(x = stepPx * index, y = (height - placeable.height) / 2)
            }
        }
    }
}

/**
 * How far each face sits from the one behind it.
 *
 * Enough that the stack reads as several cards at a glance, and not so much that three of them cost
 * the width of two — which would give back exactly the space piling exists to save.
 */
internal val PileFanStep = 7.dp

private val CountShape = RoundedCornerShape(2.dp)
private val CountPadding = 2.dp
