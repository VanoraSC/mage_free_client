package magefree.feature.game.table

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import magefree.designsystem.board.BoardSurface
import magefree.designsystem.card.BOARD_CARD_ASPECT_RATIO
import magefree.designsystem.card.BoardCard
import magefree.designsystem.card.BoardCardState
import magefree.designsystem.card.CounterPalette
import magefree.designsystem.component.phase.PhaseBarTurn
import magefree.designsystem.component.phase.PhaseRail
import magefree.designsystem.component.phase.PhaseRailState
import magefree.designsystem.component.phase.PhaseRailStep

/*
 * The left rail: whose turn it is, where in it the game is, and how each player is doing.
 *
 * ```
 *   ┌────────┐
 *   │ [card] │  ← their graveyard, top card up
 *   │ ▤5 ✝2  │  ← their counts
 *   │ ░│UP│  │
 *   │ ░│M1│  │  ← the turn, running down; a column per side
 *   │ ░│EN│● │
 *   │ ▤3 ✝1  │  ← your counts
 *   │ [card] │  ← your graveyard
 *   └────────┘
 * ```
 *
 * **One column, and it costs the board no width.** The counts and a card were already the widest
 * things on the left; the turn moved in between them rather than beside them, so the battlefield gives
 * up nothing for it. That was 0112's hardest-won property and this does not spend it.
 *
 * **Mirrored like the board.** The opponents' end is the top, the viewer's is the bottom, so which
 * seat a number or a pile belongs to is said by where it is rather than by a label.
 *
 * **A graveyard shows its top card, and that is not a replacement for the count.** The count is the
 * information — *how many* — and the card is there because a graveyard looks like a graveyard and
 * because a card going to one needs somewhere to land. Both are drawn.
 */

/**
 * The left rail: the two seats' status, the turn between them, and a graveyard at each end.
 *
 * @param vitals every seat, from [tableVitals].
 * @param palette the board's live counter palette, so a kind keeps one colour across the whole board.
 * @param modifier the [Modifier] for the rail.
 * @param rail the turn, from [phaseRailState], or `null` for a board with no game in it yet — the
 *   same rule every other region here follows.
 * @param graveyards each seat's graveyard, from [tableZones]. A seat with none draws a placeholder
 *   rather than nothing: the rail's whole job is to stay put, and a region that vanished when empty
 *   would move everything under it the first time a creature died.
 * @param artFor resolves a card's art from the printing the server named.
 * @param onExpand opens a seat's full window, or `null` for a rail that is only being read.
 * @param onOpenZone opens one pile, by seat and kind — a press on a graveyard, or on a count.
 * @param onToggleStop invoked with the step and the side whose cell was pressed.
 */
@Composable
fun StatusRail(
    vitals: List<TableVitals>,
    palette: CounterPalette,
    modifier: Modifier = Modifier,
    rail: PhaseRailState? = null,
    graveyards: List<TableZonePile> = emptyList(),
    artFor: TableArtResolver? = null,
    onExpand: ((TableVitals) -> Unit)? = null,
    onOpenZone: ((String, TableZoneKind) -> Unit)? = null,
    onToggleStop: ((PhaseRailStep, PhaseBarTurn) -> Unit)? = null,
) {
    val opponents = vitals.filterNot { it.isViewer }
    val viewer = vitals.filter { it.isViewer }

    Column(
        modifier = modifier.testTag(StatusRailTestTags.RAIL),
        verticalArrangement = Arrangement.spacedBy(SeatGap),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        opponents.forEach { seat -> Graveyard(seat.playerId, graveyards, artFor, onOpenZone) }
        opponents.forEach { seat -> Seat(seat, palette, onExpand, onOpenZone) }

        // **The turn takes what is left**, which is what keeps the rail one screen tall however many
        // seats there are. The graveyards and the counts are the fixed ends; the steps divide the
        // middle between them.
        if (rail != null) {
            PhaseRail(
                state = rail,
                onToggleStop = onToggleStop,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        } else {
            Box(modifier = Modifier.fillMaxWidth().weight(1f))
        }

        viewer.forEach { seat -> Seat(seat, palette, onExpand, onOpenZone) }
        viewer.forEach { seat -> Graveyard(seat.playerId, graveyards, artFor, onOpenZone) }
    }
}

@Composable
private fun Seat(
    seat: TableVitals,
    palette: CounterPalette,
    onExpand: ((TableVitals) -> Unit)?,
    onOpenZone: ((String, TableZoneKind) -> Unit)?,
) {
    VitalsStrip(
        vitals = seat,
        palette = palette,
        onExpand = onExpand?.let { expand -> { expand(seat) } },
        onZonePress = onOpenZone?.let { open -> { kind -> open(seat.playerId, kind) } },
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * One seat's graveyard, drawn as the card on top of it.
 *
 * **A placeholder when it is empty**, rather than nothing. A region that appeared the first time a
 * creature died would push the whole rail around at the exact moment a player is trying to read what
 * just happened — and this rail's one promise is that it stays where it was.
 */
@Composable
private fun Graveyard(
    playerId: String,
    graveyards: List<TableZonePile>,
    artFor: TableArtResolver?,
    onOpenZone: ((String, TableZoneKind) -> Unit)?,
) {
    val pile = graveyards.firstOrNull { it.playerId == playerId && it.kind == TableZoneKind.Graveyard }
    val top = pile?.topCard

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .aspectRatio(BOARD_CARD_ASPECT_RATIO)
                .then(
                    if (onOpenZone != null) {
                        Modifier.clickable { onOpenZone(playerId, TableZoneKind.Graveyard) }
                    } else {
                        Modifier
                    },
                ).testTag(StatusRailTestTags.graveyard(playerId)),
        contentAlignment = Alignment.Center,
    ) {
        if (top == null) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(BoardSurface.zone, EmptyPileShape)
                        .testTag(StatusRailTestTags.emptyGraveyard(playerId)),
            )
        } else {
            BoardCard(
                state = BoardCardState(card = top.card, power = top.power, toughness = top.toughness),
                width = maxOf(0.dp, RailCardWidth),
                art = artFor?.invoke(top.art, top.card),
            )
        }
    }
}

/** Test tags for the rail, which is told apart by position rather than by text. */
object StatusRailTestTags {
    const val RAIL: String = "status-rail"

    /** One seat's graveyard on the rail — the thing a press opens the pile from. */
    fun graveyard(playerId: String): String = "rail-graveyard-$playerId"

    /** The placeholder a seat with an empty graveyard draws, so the rail keeps its shape. */
    fun emptyGraveyard(playerId: String): String = "rail-graveyard-empty-$playerId"
}

private val SeatGap = 6.dp

private val EmptyPileShape = RoundedCornerShape(4.dp)

/**
 * How wide the rail draws a card.
 *
 * The rail's own width less its margins: the card is the widest thing in the column and the column was
 * sized for it, so it takes what there is rather than being given a size of its own to drift from.
 */
private val RailCardWidth = 66.dp
