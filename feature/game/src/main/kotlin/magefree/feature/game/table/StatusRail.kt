package magefree.feature.game.table

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import magefree.designsystem.board.BoardSurface
import magefree.designsystem.board.BoardTypography
import magefree.designsystem.card.BOARD_CARD_ASPECT_RATIO
import magefree.designsystem.card.BoardCard
import magefree.designsystem.card.BoardCardState
import magefree.designsystem.card.CounterPalette

/*
 * The status rail: everything about a player that is not on their battlefield.
 *
 * **A column, on the left, that never moves.** The two things in it are read occasionally and needed
 * instantly — how a player is doing, and what is in their graveyard — and both are useless if the
 * player has to find them first. So the rail keeps a fixed place and a fixed width, and the regions
 * inside it keep their height whether or not there is anything in them. That is the one place the
 * board's "no empty region holds height" rule (§7.4) does not apply, and it is deliberate: a
 * battlefield row that vanishes when empty costs nothing, but a graveyard that vanished when empty
 * would move a player's own life total the first time one of their creatures died.
 *
 * **Mirrored like the board.** The opponents' rails sit against the top, the viewer's against the
 * bottom, so which seat a number belongs to is said by where it is rather than by a label.
 */

/**
 * One column of seats' status.
 *
 * @param graveyards every seat's graveyard, from [tableGraveyards].
 * @param vitals every seat, from [tableVitals]. A seat with no vitals draws its graveyard alone, and
 *   vice versa — the rail is two independent regions per seat, not one component that needs both.
 * @param cardWidth the width a graveyard's top card is drawn at, which is what sets the rail's own.
 * @param palette the board's live counter palette, so a kind keeps one colour across the whole board.
 * @param modifier the [Modifier] for the rail.
 * @param artFor resolves the top card's art from the printing the server named.
 * @param onOpenGraveyard called with a seat's id when its graveyard is pressed, or `null` for a rail
 *   that is only being read. Pressing an *empty* graveyard still calls it: an empty zone is an answer,
 *   and a control that only sometimes responds teaches the player not to trust it.
 * @param onExpandVitals opens a seat's full list, or `null` for a rail that is only being read.
 */
@Composable
fun StatusRail(
    graveyards: List<TableGraveyard>,
    vitals: List<TableVitals>,
    cardWidth: Dp,
    palette: CounterPalette,
    modifier: Modifier = Modifier,
    artFor: TableArtResolver? = null,
    onOpenGraveyard: ((String) -> Unit)? = null,
    onExpandVitals: ((TableVitals) -> Unit)? = null,
) {
    val seats = (graveyards.map { it.playerId } + vitals.map { it.playerId }).distinct()
    val opponents = seats.filterNot { id -> isViewerSeat(id, graveyards, vitals) }
    val viewer = seats.filter { id -> isViewerSeat(id, graveyards, vitals) }

    Box(modifier = modifier.testTag(StatusRailTestTags.RAIL)) {
        Column(
            modifier = Modifier.align(Alignment.TopStart).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(SeatGap),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // An opponent's numbers first and their graveyard under them, so the two seats' regions
            // mirror across the middle of the rail exactly as their halves of the board do.
            opponents.forEach { id ->
                SeatStatus(
                    graveyard = graveyards.firstOrNull { it.playerId == id },
                    vitals = vitals.firstOrNull { it.playerId == id },
                    cardWidth = cardWidth,
                    palette = palette,
                    vitalsFirst = true,
                    artFor = artFor,
                    onOpenGraveyard = onOpenGraveyard,
                    onExpandVitals = onExpandVitals,
                )
            }
        }

        Column(
            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(SeatGap),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            viewer.forEach { id ->
                SeatStatus(
                    graveyard = graveyards.firstOrNull { it.playerId == id },
                    vitals = vitals.firstOrNull { it.playerId == id },
                    cardWidth = cardWidth,
                    palette = palette,
                    vitalsFirst = false,
                    artFor = artFor,
                    onOpenGraveyard = onOpenGraveyard,
                    onExpandVitals = onExpandVitals,
                )
            }
        }
    }
}

/** One seat's part of the rail: its graveyard and its numbers, in the order its half of the board reads. */
@Composable
private fun SeatStatus(
    graveyard: TableGraveyard?,
    vitals: TableVitals?,
    cardWidth: Dp,
    palette: CounterPalette,
    vitalsFirst: Boolean,
    artFor: TableArtResolver?,
    onOpenGraveyard: ((String) -> Unit)?,
    onExpandVitals: ((TableVitals) -> Unit)?,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(RegionGap),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val numbers: @Composable () -> Unit = {
            vitals?.let { seat ->
                VitalsStrip(
                    vitals = seat,
                    palette = palette,
                    onExpand = onExpandVitals?.let { expand -> { expand(seat) } },
                )
            }
        }

        if (vitalsFirst) numbers()

        graveyard?.let { zone ->
            Graveyard(
                graveyard = zone,
                cardWidth = cardWidth,
                artFor = artFor,
                onOpen = onOpenGraveyard?.let { open -> { open(zone.playerId) } },
            )
        }

        if (!vitalsFirst) numbers()
    }
}

/**
 * A graveyard, drawn as the card on top of it.
 *
 * With a count beside it, because the top card says what just died and the count says how much is
 * under it — and the two together are the whole reason to look at a graveyard without opening it.
 */
@Composable
private fun Graveyard(
    graveyard: TableGraveyard,
    cardWidth: Dp,
    artFor: TableArtResolver?,
    onOpen: (() -> Unit)?,
) {
    val top = graveyard.topCard
    Box(contentAlignment = Alignment.BottomEnd) {
        if (top == null) {
            GraveyardPlaceholder(
                playerId = graveyard.playerId,
                cardWidth = cardWidth,
                onOpen = onOpen,
            )
        } else {
            BoardCard(
                state = BoardCardState(card = top.card),
                width = cardWidth,
                art = artFor?.invoke(top.art, top.card),
                onTap = onOpen,
                modifier = Modifier.testTag(StatusRailTestTags.graveyard(graveyard.playerId)),
            )
            Text(
                text = "${graveyard.count}",
                style = BoardTypography.cardStats,
                color = BoardSurface.onSurface,
                modifier =
                    Modifier
                        .padding(CountInset)
                        .testTag(StatusRailTestTags.graveyardCount(graveyard.playerId)),
            )
        }
    }
}

/**
 * An empty graveyard: an outline the size of the card that is not there, saying what it is.
 *
 * Named rather than left blank because this is the one region on the board a player has to be able to
 * find while it is empty — the first thing that dies goes here, and a player deciding whether to trade
 * needs to know where to look before there is anything to look at.
 */
@Composable
private fun GraveyardPlaceholder(
    playerId: String,
    cardWidth: Dp,
    onOpen: (() -> Unit)?,
) {
    Box(
        modifier =
            Modifier
                .size(width = cardWidth, height = cardWidth / BOARD_CARD_ASPECT_RATIO)
                .border(PlaceholderBorder, BoardSurface.zoneRaised, PlaceholderShape)
                .let { base -> onOpen?.let { base.clickable(onClick = it) } ?: base }
                .testTag(StatusRailTestTags.graveyardPlaceholder(playerId)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = GRAVEYARD_LABEL,
            style = BoardTypography.annotation,
            color = BoardSurface.onSurfaceMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxSize().padding(PlaceholderPadding),
        )
    }
}

/** Which half of the rail a seat belongs to, from whichever of the two models named it. */
private fun isViewerSeat(
    playerId: String,
    graveyards: List<TableGraveyard>,
    vitals: List<TableVitals>,
): Boolean =
    graveyards.firstOrNull { it.playerId == playerId }?.isViewer
        ?: vitals.firstOrNull { it.playerId == playerId }?.isViewer
        ?: false

/** What an empty graveyard says it is. */
const val GRAVEYARD_LABEL: String = "Graveyard"

/** Test tags for the rail's regions, which are told apart by position rather than by text. */
object StatusRailTestTags {
    const val RAIL: String = "status-rail"

    /** A seat's graveyard, when there is a card in it. */
    fun graveyard(playerId: String): String = "status-graveyard-$playerId"

    /** A seat's graveyard when it is empty — a different tag, because it is a different affordance. */
    fun graveyardPlaceholder(playerId: String): String = "status-graveyard-empty-$playerId"

    /** How many cards are under the one on top. */
    fun graveyardCount(playerId: String): String = "status-graveyard-count-$playerId"
}

private val SeatGap = 10.dp
private val RegionGap = 4.dp
private val CountInset = 3.dp
private val PlaceholderBorder = 1.dp
private val PlaceholderShape = RoundedCornerShape(4.dp)
private val PlaceholderPadding = 4.dp
