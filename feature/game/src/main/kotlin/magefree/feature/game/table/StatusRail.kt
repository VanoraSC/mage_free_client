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
 * **A column, on the left, that never moves.** What is in it is read occasionally and needed
 * instantly — how a player is doing, and what is in their piles — and both are useless if the player
 * has to find them first. So the rail keeps a fixed place and a fixed width, and the regions inside it
 * keep their height whether or not there is anything in them. That is the one place the board's "no
 * empty region holds height" rule (§7.4) does not apply, and it is deliberate: a battlefield row that
 * vanishes when empty costs nothing, but a graveyard that vanished when empty would move a player's
 * own life total the first time one of their creatures died.
 *
 * **Mirrored, and the mirror is the point.** Reading down the rail: an opponent's numbers, then their
 * piles running away from them; then your piles running back toward you, then your numbers. Each
 * seat's own status is against its own edge of the screen, exactly as its half of the board is, so
 * which seat a number belongs to is said by where it is rather than by a label.
 */

/**
 * One column of seats' status.
 *
 * @param zones every seat's piles, from [tableZones].
 * @param vitals every seat, from [tableVitals]. A seat with no vitals draws its piles alone, and vice
 *   versa — the rail is a set of independent regions per seat, not one component that needs them all.
 * @param cardWidth the width a pile's top card is drawn at, which is what sets the rail's own.
 * @param palette the board's live counter palette, so a kind keeps one colour across the whole board.
 * @param modifier the [Modifier] for the rail.
 * @param artFor resolves the top card's art from the printing the server named.
 * @param onOpenZone called with the pile that was pressed, or `null` for a rail that is only being
 *   read. Pressing an *empty* pile still calls it: an empty zone is an answer, and a control that only
 *   sometimes responds teaches the player not to trust it.
 * @param onExpandVitals opens a seat's full list, or `null` for a rail that is only being read.
 */
@Composable
fun StatusRail(
    zones: List<TableZonePile>,
    vitals: List<TableVitals>,
    cardWidth: Dp,
    palette: CounterPalette,
    modifier: Modifier = Modifier,
    artFor: TableArtResolver? = null,
    onOpenZone: ((TableZonePile) -> Unit)? = null,
    onExpandVitals: ((TableVitals) -> Unit)? = null,
) {
    val seats = (zones.map { it.playerId } + vitals.map { it.playerId }).distinct()
    val opponents = seats.filterNot { id -> isViewerSeat(id, zones, vitals) }
    val viewer = seats.filter { id -> isViewerSeat(id, zones, vitals) }

    Box(modifier = modifier.testTag(StatusRailTestTags.RAIL)) {
        Column(
            modifier = Modifier.align(Alignment.TopStart).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(SeatGap),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            opponents.forEach { id ->
                SeatStatus(
                    zones = zones.filter { it.playerId == id },
                    vitals = vitals.firstOrNull { it.playerId == id },
                    cardWidth = cardWidth,
                    palette = palette,
                    isViewer = false,
                    artFor = artFor,
                    onOpenZone = onOpenZone,
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
                    zones = zones.filter { it.playerId == id },
                    vitals = vitals.firstOrNull { it.playerId == id },
                    cardWidth = cardWidth,
                    palette = palette,
                    isViewer = true,
                    artFor = artFor,
                    onOpenZone = onOpenZone,
                    onExpandVitals = onExpandVitals,
                )
            }
        }
    }
}

/**
 * One seat's part of the rail, in the order its half of the board reads.
 *
 * An opponent's runs outward from the top edge — numbers, graveyard, the special exile, exile — and
 * the viewer's runs back inward to the bottom edge, which is the same order upside down. The two
 * seats' piles meet in the middle of the rail the way their battlefields meet in the middle of the
 * board.
 */
@Composable
private fun SeatStatus(
    zones: List<TableZonePile>,
    vitals: TableVitals?,
    cardWidth: Dp,
    palette: CounterPalette,
    isViewer: Boolean,
    artFor: TableArtResolver?,
    onOpenZone: ((TableZonePile) -> Unit)?,
    onExpandVitals: ((TableVitals) -> Unit)?,
) {
    val order = if (isViewer) ViewerZoneOrder else OpponentZoneOrder

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
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        if (!isViewer) numbers()

        order.forEach { kind ->
            zones.firstOrNull { it.kind == kind }?.let { zone ->
                ZonePile(
                    zone = zone,
                    cardWidth = cardWidth,
                    artFor = artFor,
                    onOpen = onOpenZone?.let { open -> { open(zone) } },
                )
            }
        }

        if (isViewer) numbers()
    }
}

/**
 * A pile, drawn as the card on top of it.
 *
 * With a count beside it, because the top card says what just went there and the count says how much
 * is under it — and the two together are the whole reason to look at a pile without opening it.
 */
@Composable
private fun ZonePile(
    zone: TableZonePile,
    cardWidth: Dp,
    artFor: TableArtResolver?,
    onOpen: (() -> Unit)?,
) {
    val top = zone.topCard
    Box(contentAlignment = Alignment.BottomEnd) {
        if (top == null) {
            ZonePlaceholder(zone = zone, cardWidth = cardWidth, onOpen = onOpen)
        } else {
            BoardCard(
                state = BoardCardState(card = top.card),
                width = cardWidth,
                art = artFor?.invoke(top.art, top.card),
                onTap = onOpen,
                modifier = Modifier.testTag(StatusRailTestTags.zone(zone.playerId, zone.kind)),
            )
            Text(
                text = "${zone.count}",
                style = BoardTypography.cardStats,
                color = BoardSurface.onSurface,
                modifier =
                    Modifier
                        .padding(CountInset)
                        .testTag(StatusRailTestTags.zoneCount(zone.playerId, zone.kind)),
            )
        }
    }
}

/**
 * An empty pile: an outline the size of the card that is not there, saying what it is.
 *
 * Named rather than left blank because these are the regions a player has to be able to find while
 * they are empty — the first thing that dies goes to the graveyard, and a player deciding whether to
 * trade needs to know where to look before there is anything to look at.
 */
@Composable
private fun ZonePlaceholder(
    zone: TableZonePile,
    cardWidth: Dp,
    onOpen: (() -> Unit)?,
) {
    Box(
        modifier =
            Modifier
                .size(width = cardWidth, height = cardWidth / BOARD_CARD_ASPECT_RATIO)
                .border(PlaceholderBorder, BoardSurface.zoneRaised, PlaceholderShape)
                .let { base -> onOpen?.let { base.clickable(onClick = it) } ?: base }
                .testTag(StatusRailTestTags.zonePlaceholder(zone.playerId, zone.kind)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = zone.kind.label,
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
    zones: List<TableZonePile>,
    vitals: List<TableVitals>,
): Boolean =
    zones.firstOrNull { it.playerId == playerId }?.isViewer
        ?: vitals.firstOrNull { it.playerId == playerId }?.isViewer
        ?: false

/** Reading down from the top edge, an opponent's piles run away from their own numbers. */
private val OpponentZoneOrder =
    listOf(TableZoneKind.Graveyard, TableZoneKind.SpecialExile, TableZoneKind.Exile)

/** The viewer's are the same order upside down, so the two seats mirror across the rail's middle. */
private val ViewerZoneOrder = OpponentZoneOrder.reversed()

/** Test tags for the rail's regions, which are told apart by position rather than by text. */
object StatusRailTestTags {
    const val RAIL: String = "status-rail"

    /** A seat's pile, when there is a card in it. */
    fun zone(
        playerId: String,
        kind: TableZoneKind,
    ): String = "status-zone-$playerId-${kind.name}"

    /** A seat's pile when it is empty — a different tag, because it is a different affordance. */
    fun zonePlaceholder(
        playerId: String,
        kind: TableZoneKind,
    ): String = "status-zone-empty-$playerId-${kind.name}"

    /** How many cards are under the one on top. */
    fun zoneCount(
        playerId: String,
        kind: TableZoneKind,
    ): String = "status-zone-count-$playerId-${kind.name}"
}

private val SeatGap = 8.dp
private val RegionGap = 3.dp
private val CountInset = 3.dp
private val PlaceholderBorder = 1.dp
private val PlaceholderShape = RoundedCornerShape(4.dp)
private val PlaceholderPadding = 2.dp
