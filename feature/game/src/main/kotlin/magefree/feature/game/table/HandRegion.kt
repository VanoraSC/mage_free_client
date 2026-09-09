package magefree.feature.game.table

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import magefree.designsystem.board.BoardSurface
import magefree.designsystem.board.BoardTypography
import magefree.designsystem.card.BOARD_CARD_ASPECT_RATIO
import magefree.designsystem.card.BoardCard
import magefree.designsystem.card.BoardCardState
import kotlin.math.roundToInt

/*
 * The hand, on the base layer, never a gesture away.
 *
 * §7.4 is unusually specific here, and it is a rule about interaction rather than about layout: *"The
 * hand never collapses. The player reads their hand constantly to make decisions, so hiding it behind
 * a peek edge and an expand gesture takes the most-consulted information on screen and puts it a
 * gesture away."*
 *
 * **A scrolling hand is a collapsing hand by another name.** Cards past the edge are still a gesture
 * away; the gesture is just a swipe instead of a tap. So when the hand outgrows its width the tiles
 * *overlap* — every card keeps an edge on screen, and the overlap is only ever as much as it has to
 * be. A seven-card hand at a comfortable size does not overlap at all.
 *
 * What an overlap leaves showing is a card's **left edge**, and a Magic card prints its name across the
 * top-left of its own face — so an overlapped hand still reads as a list of names. That is a property
 * of the card and not of anything here, which is exactly why it is written down: the layout depends on
 * it and cannot enforce it.
 *
 * **A hand card is a board card.** A card does not change what it looks like by being in a hand: it
 * is the same object, and choosing one to cast means comparing it against what is already on the
 * table. So the hand draws the Board tier, frame and title bar and all.
 */

/**
 * The viewer's hand.
 *
 * @param cards the hand, from [handCards], in the server's own order.
 * @param tileWidth how wide each tile is drawn. Handed in rather than worked out here, because it is a
 *   question about the whole board — a tile tall enough to squeeze the battlefields is the wrong size
 *   however comfortably it fits across the hand — and the board is what knows its own height.
 * @param modifier the [Modifier] for the region.
 * @param artFor resolves each card's art from the printing the server named.
 * @param onPlay called with a card's id when it is tapped — §7.1's *act on this object*.
 * @param onInspect called with a card's id on long press — §7.1's *inspect*, which is the same gesture
 *   on every card-like object in every screen.
 */
@Composable
fun HandRegion(
    cards: List<TableCard>,
    tileWidth: Dp,
    modifier: Modifier = Modifier,
    artFor: TableArtResolver? = null,
    onPlay: ((String) -> Unit)? = null,
    onInspect: ((String) -> Unit)? = null,
    elsewhere: List<TableCard> = emptyList(),
    anchors: BoardAnchors? = null,
) {
    if (cards.isEmpty() && elsewhere.isEmpty()) return

    // **The region is only as tall as the part of a card that shows.** The tiles are drawn from its
    // top downward at their full height, so the rest hangs past the bottom of the region — and, since
    // the region is anchored to the bottom of the board, past the bottom of the screen. That is what
    // puts the cut at the screen edge rather than at some line chosen above it.
    BoxWithConstraints(modifier = modifier.height(handVisibleHeight(tileWidth)).testTag(HandTestTags.HAND)) {
        // **The other group is laid out first, from the right edge inward.** It is small — a game with
        // three castable graveyard cards at once is a rare game — and it is fixed, so the hand takes
        // what is left and keeps its own centring. Sizing the hand around a group that grows would
        // make drawing a card shuffle the other group, which says something the game did not.
        val elsewhereWidth =
            if (elsewhere.isEmpty()) {
                0.dp
            } else {
                tileWidth + (tileWidth + TileGap) * (elsewhere.size - 1) + ElsewhereGap
            }

        val available = maxWidth - HandPadding * 2 - elsewhereWidth
        val step = handStep(count = cards.size, tileWidth = tileWidth, available = available)
        val used = tileWidth + step * (cards.size - 1)

        // Centred, so a two-card hand is not pinned to one corner of the space it has.
        val start = HandPadding + ((available - used) / 2).coerceAtLeast(0.dp)

        elsewhere.forEachIndexed { index, card ->
            ElsewhereCard(
                card = card,
                tileWidth = tileWidth,
                artFor = artFor,
                onPlay = onPlay,
                onInspect = onInspect,
                anchors = anchors,
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = -(tileWidth + TileGap) * (elsewhere.size - 1 - index))
                        .width(tileWidth),
            )
        }

        cards.forEachIndexed { index, card ->
            // Placed absolutely, each from the region's own left edge. Nesting them in a wrapper that
            // centred itself was the first attempt and it compounds: the wrapper measures as one tile,
            // centres *that*, and every offset then starts from the middle of the region — which walks
            // the far end of a large hand off the screen, exactly the failure this layout exists to
            // prevent.
            //
            // Later cards are drawn on top, so an overlap reads left to right and the rightmost card is
            // whole — the same convention the land stacks use for the copy you would reach for.

            // Dragged upward out of the hand, a playable card does what its button does — §7.1's
            // accelerator, which always has a tap path. Offered only for a card the server marked
            // playable: dragging an uncastable card would either do nothing, which is confusing, or
            // submit an action the server had not offered, which is worse.
            var dragged by remember(card.id) { mutableFloatStateOf(0f) }
            val draggable =
                if (onPlay == null || !card.isPlayable) {
                    Modifier
                } else {
                    Modifier.pointerInput(card.id) {
                        detectVerticalDragGestures(
                            onDragEnd = {
                                if (dragged <= -DragThreshold.toPx()) onPlay(card.id)
                                dragged = 0f
                            },
                            onDragCancel = { dragged = 0f },
                        ) { _, delta -> dragged += delta }
                    }
                }

            Box(
                modifier =
                    Modifier
                        .offset(x = start + step * index)
                        .offset { IntOffset(x = 0, y = dragged.roundToInt()) }
                        .width(tileWidth)
                        .align(Alignment.TopStart)
                        .then(draggable),
            ) {
                // **The same card the battlefield draws.** A card does not change what it looks like
                // by being in a hand: it is the same object, and a player picking one to cast is
                // comparing it against what is already on the table. Two tiers for the two places was
                // a difference the game does not have — and it was the difference between reading a
                // name off a black-on-white strip and reading it off a printed frame at a quarter of
                // the size.
                BoardCard(
                    state = BoardCardState(card = card.card, signals = setOfNotNull(card.signal), isSelected = card.isSelected),
                    width = tileWidth,
                    art = artFor?.invoke(card.boardArt, card.card),
                    onTap = { onPlay?.invoke(card.id) },
                    onLongPress = onInspect?.let { inspect -> { inspect(card.id) } },
                    // Where this card is, so casting it can be drawn as the card travelling to the
                    // stack. The anchors keep the box after the card has gone, which is what makes
                    // the origin available at the moment it is needed — see [CardFlights].
                    modifier =
                        Modifier
                            .then(anchors?.anchorModifier(handAnchorId(card.id)) ?: Modifier)
                            .testTag(HandTestTags.card(card.id)),
                )
            }
        }
    }
}

/**
 * How wide a hand tile is drawn, given the height the board can spare for the hand.
 *
 * The same rule the battlefield follows: a preferred size that other constraints can only take down.
 * The hand is a strip and a tile is taller than it is wide, so the height is what usually binds — and
 * past the floor the tiles overlap rather than shrinking further, because a hand of twenty at a
 * legible size is a hand you can read and a hand of twenty at four dp each is not.
 *
 * A hand tile is the card face and nothing else, so the budget is the card's own height and the width
 * follows from its ratio. That is also why it can be larger than it was: the caption used to take a
 * third of the tile to repeat what the art already printed.
 */
fun handTileWidth(heightBudget: Dp): Dp = minOf(PreferredTileWidth, heightBudget * BOARD_CARD_ASPECT_RATIO).coerceAtLeast(MinTileWidth)

/**
 * A card the server is offering that is **not** in the hand.
 *
 * Drawn as the same card, in the same row, because it is the same decision: it is one of the things
 * this player can cast right now. Set apart by the gap beside it and by the mark on it, because it is
 * *not in hand* — and a player who read it as a hand card would be counting a card they do not hold.
 */
@Composable
private fun ElsewhereCard(
    card: TableCard,
    tileWidth: Dp,
    artFor: TableArtResolver?,
    onPlay: ((String) -> Unit)?,
    onInspect: ((String) -> Unit)?,
    anchors: BoardAnchors?,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        BoardCard(
            state = BoardCardState(card = card.card, signals = setOfNotNull(card.signal), isSelected = card.isSelected),
            width = tileWidth,
            art = artFor?.invoke(card.boardArt, card.card),
            onTap = { onPlay?.invoke(card.id) },
            onLongPress = onInspect?.let { inspect -> { inspect(card.id) } },
            modifier =
                Modifier
                    .then(anchors?.anchorModifier(handAnchorId(card.id)) ?: Modifier)
                    .testTag(HandTestTags.card(card.id)),
        )

        // Which pile it is in, on the card. The gap says *not in hand*; this says *where*, which is
        // the next question and the one a player has to answer before they can plan around it.
        Text(
            text = card.zone.label,
            style = BoardTypography.annotation,
            color = BoardSurface.onSurface,
            maxLines = 1,
            softWrap = false,
            modifier =
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(ZoneLabelInset)
                    .background(BoardSurface.zone.copy(alpha = ZONE_LABEL_OPACITY), ZoneLabelShape)
                    .padding(horizontal = ZoneLabelInset)
                    .testTag(HandTestTags.zoneLabel(card.id)),
        )
    }
}

/**
 * How far apart the tiles are placed.
 *
 * A whole tile plus a gap while they fit; otherwise exactly enough that the last one ends at the right
 * edge. That is the overlap: it appears only when it must, and never more than it must.
 */
private fun handStep(
    count: Int,
    tileWidth: Dp,
    available: Dp,
): Dp {
    if (count <= 1) return 0.dp
    val roomy = tileWidth + TileGap
    val needed = tileWidth + roomy * (count - 1)
    if (needed <= available) return roomy
    return ((available - tileWidth) / (count - 1)).coerceAtLeast(MinStep)
}

/** Test tags for the hand, whose cards carry their names but not their identity. */
object HandTestTags {
    const val HAND: String = "hand"

    /** One card, by the id an action on it would name. */
    fun card(cardId: String): String = "hand-card-$cardId"

    /** The pile a card beside the hand is actually in. */
    fun zoneLabel(cardId: String): String = "hand-zone-$cardId"
}

/**
 * The size a hand tile is drawn at when there is room for it.
 *
 * Larger than a board card: the hand is read closely and repeatedly, and it is the one region where a
 * card's type line and cost are being weighed rather than glanced at.
 */
private val PreferredTileWidth = 132.dp

/**
 * Below this a tile stops being a card and becomes a stripe.
 *
 * Reached only by a hand far larger than a game produces; past it the tiles keep this width and simply
 * overlap more, which is the trade the region is built to make.
 */
private val MinTileWidth = 56.dp

/** However much they overlap, this much of each card is always left showing. */
private val MinStep = 14.dp

private val TileGap = 4.dp

/**
 * Between the hand and the cards that are not in it.
 *
 * Several times [TileGap], because that is the whole job: the eye has to read one group as the hand
 * and the other as something else at a glance, without counting.
 */
private val ElsewhereGap = 40.dp

private val ZoneLabelInset = 2.dp
private val ZoneLabelShape = RoundedCornerShape(2.dp)
private const val ZONE_LABEL_OPACITY = 0.85f
private val HandPadding = 8.dp

/**
 * How far a card has to leave the hand before the drag counts as playing it.
 *
 * Far enough that a shaky tap is not a cast — this is a gesture that submits a game action, and the
 * cost of triggering it by accident is a spell on the stack the player did not intend.
 */
private val DragThreshold = 64.dp

/**
 * How much of a hand tile is on screen, the rest hanging off the bottom edge.
 *
 * A hand sits in front of a player with its far edge over the lip of the table: what they read to
 * decide is the name, the cost and the art, all in the top of a card, and the bottom is rules text
 * they would open the card to read properly anyway. Cutting it there buys the battlefield the height
 * back without taking anything a player was using.
 */
private const val HAND_VISIBLE_FRACTION = 0.75f

/** The on-screen height of a hand tile [tileWidth] wide — its full height, cut to what shows. */
fun handVisibleHeight(tileWidth: Dp): Dp = tileWidth / BOARD_CARD_ASPECT_RATIO * HAND_VISIBLE_FRACTION
