package magefree.feature.game.table

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import magefree.designsystem.card.CounterPalette

/*
 * The status rail: how each player is doing, in a column that never moves.
 *
 * **A line of numbers per seat, and nothing else.** It carries what is read at a glance and needed
 * instantly — life, the four zone counts, whatever counters are on the player — and it is useless if
 * the player has to find it first, so it keeps a fixed place and a fixed width.
 *
 * **What it does not carry is cards.** It drew the top card of each pile for a while, which cost four
 * card-heights of a column one card wide and left every pile too small to read. A count says as much
 * in a line of text, and pressing a seat opens [PlayerOverlay], which has the room to show the cards
 * properly — all of that seat's piles at once, side by side.
 *
 * **Mirrored like the board.** The opponents' status sits against the top, the viewer's against the
 * bottom, so which seat a number belongs to is said by where it is rather than by a label.
 */

/**
 * One column of seats' status.
 *
 * @param vitals every seat, from [tableVitals].
 * @param palette the board's live counter palette, so a kind keeps one colour across the whole board.
 * @param modifier the [Modifier] for the rail.
 * @param onExpand opens a seat's full window, or `null` for a rail that is only being read.
 */
@Composable
fun StatusRail(
    vitals: List<TableVitals>,
    palette: CounterPalette,
    modifier: Modifier = Modifier,
    onExpand: ((TableVitals) -> Unit)? = null,
) {
    Box(modifier = modifier.testTag(StatusRailTestTags.RAIL)) {
        Column(
            modifier = Modifier.align(Alignment.TopStart).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(SeatGap),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            vitals.filterNot { it.isViewer }.forEach { seat -> Seat(seat, palette, onExpand) }
        }

        Column(
            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(SeatGap),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            vitals.filter { it.isViewer }.forEach { seat -> Seat(seat, palette, onExpand) }
        }
    }
}

@Composable
private fun Seat(
    seat: TableVitals,
    palette: CounterPalette,
    onExpand: ((TableVitals) -> Unit)?,
) {
    VitalsStrip(
        vitals = seat,
        palette = palette,
        onExpand = onExpand?.let { expand -> { expand(seat) } },
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Test tags for the rail, which is told apart by position rather than by text. */
object StatusRailTestTags {
    const val RAIL: String = "status-rail"
}

private val SeatGap = 8.dp
