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
 * @param zones every seat's piles, from [tableZones]. A seat whose graveyard is empty draws a placeholder
 *   rather than nothing: the rail's whole job is to stay put, and a region that vanished when empty
 *   would move everything under it the first time a creature died.
 * @param artFor resolves a card's art from the printing the server named.
 * @param onExpand opens a seat's full window, or `null` for a rail that is only being read.
 * @param onOpenPiles opens some piles: a graveyard on its own from the rail, or everything behind
 *   the counts from a press on them.
 * @param onToggleStop invoked with the step and the side whose cell was pressed.
 * @param anchors where each graveyard and each seat's counts report their boxes, so a card can fly to
 *   them — see [ZoneFlights]. `null` for a rail nothing flies to.
 * @param arriving cards on their way to a graveyard. A graveyard keeps showing the card that was on top
 *   until one of these lands on it, rather than showing it before it has arrived.
 */
@Composable
fun StatusRail(
    vitals: List<TableVitals>,
    palette: CounterPalette,
    modifier: Modifier = Modifier,
    rail: PhaseRailState? = null,
    zones: List<TableZonePile> = emptyList(),
    artFor: TableArtResolver? = null,
    onExpand: ((TableVitals) -> Unit)? = null,
    onOpenPiles: ((List<TableZonePile>) -> Unit)? = null,
    onToggleStop: ((PhaseRailStep, PhaseBarTurn) -> Unit)? = null,
    anchors: BoardAnchors? = null,
    arriving: Set<String> = emptySet(),
) {
    val opponents = vitals.filterNot { it.isViewer }
    val viewer = vitals.filter { it.isViewer }

    Column(
        modifier = modifier.testTag(StatusRailTestTags.RAIL),
        verticalArrangement = Arrangement.spacedBy(SeatGap),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        opponents.forEach { seat -> Graveyard(seat.playerId, zones, artFor, onOpenPiles, anchors, arriving) }
        opponents.forEach { seat -> Seat(seat, palette, onExpand, zones, onOpenPiles, anchors) }

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

        viewer.forEach { seat -> Seat(seat, palette, onExpand, zones, onOpenPiles, anchors) }
        viewer.forEach { seat -> Graveyard(seat.playerId, zones, artFor, onOpenPiles, anchors, arriving) }
    }
}

@Composable
private fun Seat(
    seat: TableVitals,
    palette: CounterPalette,
    onExpand: ((TableVitals) -> Unit)?,
    zones: List<TableZonePile>,
    onOpenPiles: ((List<TableZonePile>) -> Unit)?,
    anchors: BoardAnchors?,
) {
    VitalsStrip(
        vitals = seat,
        palette = palette,
        onExpand = onExpand?.let { expand -> { expand(seat) } },
        // **The counts are one door, not four.** A press on any of them opens everything behind them
        // at once — see [pilesBehindTheCounts] — because "what has this player got that is not on the
        // board" is one question, and answering it a pile at a time makes the player ask it four
        // times to find out that three of the answers were empty.
        onZonePress = onOpenPiles?.let { open -> { open(zones.pilesBehindTheCounts(seat.playerId)) } },
        // The panel a card lands on when it goes anywhere the board draws only as a count.
        modifier = Modifier.fillMaxWidth().then(anchors?.anchorModifier(zoneCountsAnchorId(seat.playerId)) ?: Modifier),
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
    zones: List<TableZonePile>,
    artFor: TableArtResolver?,
    onOpenPiles: ((List<TableZonePile>) -> Unit)?,
    anchors: BoardAnchors?,
    arriving: Set<String>,
) {
    val pile = zones.pileFor(playerId, TableZoneKind.Graveyard)
    // The top card that has already *arrived*: one still in flight to this graveyard is not on it yet.
    val top = pile?.cards?.lastOrNull { it.id !in arriving }

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .aspectRatio(BOARD_CARD_ASPECT_RATIO)
                .then(
                    if (onOpenPiles != null && pile != null) {
                        Modifier.clickable { onOpenPiles(listOf(pile)) }
                    } else {
                        Modifier
                    },
                ).then(anchors?.anchorModifier(graveyardAnchorId(playerId)) ?: Modifier)
                .testTag(StatusRailTestTags.graveyard(playerId)),
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
                // The art crop, exactly as the hand and the battlefield ask for it — a full card
                // scan drawn inside a Board-tier frame is a card inside a card.
                art = artFor?.invoke(top.boardArt, top.card),
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
