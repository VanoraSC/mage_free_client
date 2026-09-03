package magefree.feature.game.table

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import magefree.designsystem.card.CARD_ASPECT_RATIO
import magefree.designsystem.card.CardTile

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
 * What an overlap leaves showing is a card's **left edge**, which is where the Tile tier prints its
 * name. That is not a coincidence to rely on quietly, so it is written down: the tier's caption puts
 * the name first, and this layout depends on it.
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
    cards: List<TableHandCard>,
    tileWidth: Dp,
    modifier: Modifier = Modifier,
    artFor: TableArtResolver? = null,
    onPlay: ((String) -> Unit)? = null,
    onInspect: ((String) -> Unit)? = null,
) {
    if (cards.isEmpty()) return

    BoxWithConstraints(modifier = modifier.testTag(HandTestTags.HAND)) {
        val available = maxWidth - HandPadding * 2
        val step = handStep(count = cards.size, tileWidth = tileWidth, available = available)
        val used = tileWidth + step * (cards.size - 1)

        // Centred, so a two-card hand is not pinned to one corner of the space it has.
        val start = HandPadding + ((available - used) / 2).coerceAtLeast(0.dp)

        cards.forEachIndexed { index, card ->
            // Placed absolutely, each from the region's own left edge. Nesting them in a wrapper that
            // centred itself was the first attempt and it compounds: the wrapper measures as one tile,
            // centres *that*, and every offset then starts from the middle of the region — which walks
            // the far end of a large hand off the screen, exactly the failure this layout exists to
            // prevent.
            //
            // Later cards are drawn on top, so an overlap reads left to right and the rightmost card is
            // whole — the same convention the land stacks use for the copy you would reach for.
            Box(modifier = Modifier.offset(x = start + step * index).width(tileWidth).align(Alignment.CenterStart)) {
                CardTile(
                    card = card.card,
                    onTap = { onPlay?.invoke(card.id) },
                    onLongPressPeek = onInspect?.let { inspect -> { inspect(card.id) } },
                    art = artFor?.invoke(card.art, card.card),
                    signal = card.signal,
                    modifier = Modifier.testTag(HandTestTags.card(card.id)),
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
 * The height budget covers the art; a tile's caption sits under it and takes what the text needs, so
 * the estimate is deliberately a little conservative rather than pretending to know a font's metrics.
 */
fun handTileWidth(heightBudget: Dp): Dp =
    minOf(PreferredTileWidth, heightBudget * ART_SHARE_OF_TILE * CARD_ASPECT_RATIO)
        .coerceAtLeast(MinTileWidth)

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
}

/**
 * The size a hand tile is drawn at when there is room for it.
 *
 * Larger than a board card: the hand is read closely and repeatedly, and it is the one region where a
 * card's type line and cost are being weighed rather than glanced at.
 */
private val PreferredTileWidth = 96.dp

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
private val HandPadding = 8.dp

/**
 * How much of a tile's height is its art, the rest being the caption.
 *
 * A rough share rather than a measurement: the caption is text and takes what the font asks for, and
 * pretending to know that in advance is how a layout ends up clipping at a large font scale. Being a
 * little conservative costs a few dp of tile and buys a hand that never overflows its strip.
 */
private const val ART_SHARE_OF_TILE = 0.72f
