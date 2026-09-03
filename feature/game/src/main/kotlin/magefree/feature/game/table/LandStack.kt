package magefree.feature.game.table

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import magefree.designsystem.board.BoardSurface
import magefree.designsystem.board.BoardTypography
import magefree.designsystem.card.BoardCard
import magefree.designsystem.card.CARD_ASPECT_RATIO
import magefree.designsystem.card.CounterPalette

/*
 * One land, however many copies of it, in six fixed places.
 *
 * ```
 *    ┌───┐                     three untapped slots, staggered down-right,
 *    │ 0 ┌───┐                 as though the copies were attached to each other
 *    └───│ 1 ┌───┐   ┌───────┐
 *        └───│ 2 │   │   0   ├───────┐        three tapped slots, the same
 *            └───┘   └───┬───│   1   ├───────┐  diagonal, turned a quarter
 *                        └───│───────│   2   │
 *                            └───────┴───────┘
 * ```
 *
 * **The slots are fixed and the cards move between them.** That is the whole design: a stack is not a
 * list that re-flows when its length changes, it is six places, and tapping is one card travelling from
 * one of them to another. Everything the player sees follows from that — the untapped half fills from
 * the back (slot 0) forward, so it empties from the front and the cards behind never shift; the tapped
 * half fills from the front (slot 2) backward, so each newly-tapped card lands in the nearest free
 * place and the ones already there never move.
 *
 * **The top card is the lowest and furthest right**, on both halves and for both players. It is the one
 * a hand would reach for, so it is the one a tap acts on and the one a travelling card leaves from.
 *
 * **The count appears only where the picture stops answering the question.** One, two and three are
 * visible by looking. It appears at four — which is why the worked example needs no special case: four
 * Plains show three faces and a count; tap one and three remain, so the count simply goes away.
 *
 * **The travelling card is drawn, not moved.** The copies are identical, so tracking which server id
 * sits in which slot would be work in service of a distinction nobody can see. Instead the slots are
 * drawn from counts, and when the tapped count rises a single card is animated along the diagonal from
 * the untapped top slot to the place it is arriving at, rotating a quarter turn as it goes. The stack
 * behind it never has to re-flow, which is exactly what the fixed slots bought.
 */

/**
 * One land stack: up to three upright copies, up to three turned ones, and a count.
 *
 * @param stack the copies, both halves.
 * @param width the card width the board is drawing at.
 * @param palette the board's counter palette, so a counter kind keeps its colour across every card.
 * @param artFor resolves the card's art from the printing the server named.
 * @param onInspect called when the stack is tapped, with the topmost *untapped* copy — the one a hand
 *   would reach for. It falls back to any member once they are all tapped, since a fully tapped stack
 *   can still be looked at.
 * @param modifier the [Modifier] for the stack.
 */
@Composable
internal fun LandStack(
    stack: TableLandStack,
    width: Dp,
    palette: CounterPalette,
    artFor: TableArtResolver?,
    onInspect: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val geometry = LandStackGeometry(width)
    val upright = minOf(stack.untapped.size, PILE_FAN_LIMIT)
    val turned = minOf(stack.tapped.size, PILE_FAN_LIMIT)

    // The card that has just been tapped, travelling. Driven by the tapped count rather than by which
    // permanent moved: the copies are identical, so the count is the only thing that changed that
    // anybody can see.
    val travel = remember { Animatable(1f) }
    var lastTapped by remember { mutableIntStateOf(stack.tapped.size) }
    LaunchedEffect(stack.tapped.size) {
        if (stack.tapped.size > lastTapped) {
            travel.snapTo(0f)
            travel.animateTo(targetValue = 1f, animationSpec = tween(durationMillis = TAP_TRAVEL_MILLIS))
        }
        lastTapped = stack.tapped.size
    }

    Box(
        modifier =
            modifier
                .size(width = geometry.totalWidth(turned > 0), height = geometry.totalHeight)
                .testTag(BattlefieldTestTags.stack(stack.inspectId)),
    ) {
        // Back to front, so the lowest and furthest right of each half is drawn on top.
        repeat(upright) { slot ->
            StackedCard(
                stack = stack,
                geometry = geometry,
                centre = geometry.uprightCentre(slot),
                turn = 0f,
                width = width,
                palette = palette,
                artFor = artFor,
                onInspect = onInspect,
            )
        }
        // Arrivals fill slot 2 first, then 1, then 0 — the nearest free place each time — but they are
        // *drawn* back to front, so the lowest and furthest right is on top in this half exactly as it
        // is in the other. Drawing them in arrival order instead puts the newest card on top and the
        // two halves end up mirror images of each other, which reads as a mistake rather than a rule.
        val firstTurnedSlot = PILE_FAN_LIMIT - turned
        for (slot in firstTurnedSlot until PILE_FAN_LIMIT) {
            val newest = slot == firstTurnedSlot && travel.value < 1f
            if (!newest) {
                StackedCard(
                    stack = stack,
                    geometry = geometry,
                    centre = geometry.turnedCentre(slot),
                    turn = 1f,
                    width = width,
                    palette = palette,
                    artFor = artFor,
                    onInspect = onInspect,
                )
            }
        }

        if (travel.value < 1f) {
            // One card in flight, from the untapped top slot to the place it is arriving at. Past
            // three tapped there is no free slot, so it flies to the front one and is simply gone when
            // it lands — by then the count badge has taken over saying how many there are.
            val from = geometry.uprightCentre(maxOf(minOf(stack.untapped.size + 1, PILE_FAN_LIMIT) - 1, 0))
            val to = geometry.turnedCentre(maxOf(PILE_FAN_LIMIT - stack.tapped.size, 0))
            StackedCard(
                stack = stack,
                geometry = geometry,
                centre = lerp(from, to, travel.value),
                turn = travel.value,
                width = width,
                palette = palette,
                artFor = artFor,
                onInspect = null,
            )
        }

        // **A count per half, not per stack.** The total is the wrong number: with three upright and
        // one turned there are four copies, and a badge saying so would be counting cards the player
        // can already see, sitting beside three that are plainly visible. Each half answers only for
        // itself, so tapping one of four makes the count disappear — three is countable again — which
        // is exactly what it should do.
        HalfCount(
            count = stack.untapped.size,
            box = geometry.uprightBox(),
            tag = BattlefieldTestTags.stackCount(stack.inspectId),
        )
        HalfCount(
            count = stack.tapped.size,
            box = geometry.turnedBox(),
            tag = BattlefieldTestTags.stackTappedCount(stack.inspectId),
        )
    }
}

/**
 * How many are in one half, shown only once the half has more than it can draw.
 *
 * Placed at the top-right of its own half's footprint, which is the one corner the staggering leaves
 * empty — the cards run down and to the right, so the space above the front one is clear. Anchored to
 * the front card instead it sat on that card's name band, hiding the thing the stagger exists to show.
 */
@Composable
private fun HalfCount(
    count: Int,
    box: HalfBox,
    tag: String,
) {
    if (count <= PILE_FAN_LIMIT) return
    Box(
        modifier = Modifier.offset(x = box.left, y = 0.dp).size(width = box.width, height = box.height),
        contentAlignment = Alignment.TopEnd,
    ) {
        Text(
            text = "×$count",
            style = BoardTypography.counter,
            color = BoardSurface.onSurface,
            modifier =
                Modifier
                    .background(BoardSurface.zone, CountShape)
                    .padding(horizontal = CountPadding)
                    .testTag(tag),
        )
    }
}

/** One half's footprint inside the stack, so a badge can sit in its corner. */
internal data class HalfBox(
    val left: Dp,
    val width: Dp,
    val height: Dp,
)

/**
 * One copy, drawn portrait and turned by [turn] about its own centre.
 *
 * The card is always laid out upright and rotated here rather than being handed `tapped = true`,
 * because the stack needs *partial* turns — a card halfway through a tap is at forty-five degrees, and
 * a footprint that flipped from portrait to landscape at some point during that would jump. The board's
 * own tapped footprint logic is right everywhere it is used and wrong inside a fixed-slot layout, which
 * has already decided where everything goes.
 */
@Composable
private fun StackedCard(
    stack: TableLandStack,
    geometry: LandStackGeometry,
    centre: Offset2,
    turn: Float,
    width: Dp,
    palette: CounterPalette,
    artFor: TableArtResolver?,
    onInspect: ((String) -> Unit)?,
) {
    val permanent = stack.representative
    Box(
        modifier =
            Modifier
                .offset(x = centre.x - width / 2, y = centre.y - geometry.cardHeight / 2)
                .requiredSize(width = width, height = geometry.cardHeight)
                .graphicsLayer { rotationZ = TAPPED_TURN_DEGREES * turn },
    ) {
        BoardCard(
            state = permanent.state.copy(tapped = false),
            width = width,
            art = artFor?.invoke(permanent.art, permanent.state.card),
            onTap = onInspect?.let { inspect -> { inspect(stack.tapActionId ?: stack.inspectId) } },
            counterPalette = palette,
        )
    }
}

/**
 * Where the six slots are, for a card [width] wide.
 *
 * Every distance is a fraction of the card rather than a fixed dp, for the reason the card tier
 * already learned the hard way: a step that reads well at 60dp disappears at 200dp, and a board that
 * derives its card size draws both.
 */
private class LandStackGeometry(
    val cardWidth: Dp,
) {
    val cardHeight: Dp = cardWidth / CARD_ASPECT_RATIO
    private val stepX: Dp = cardWidth * STACK_STEP_X_FRACTION
    private val stepY: Dp = cardHeight * STACK_STEP_Y_FRACTION

    /** The upright half's footprint, and the whole stack's height — a portrait card is the taller. */
    val totalHeight: Dp = cardHeight + stepY * (PILE_FAN_LIMIT - 1)

    private val uprightWidth: Dp = cardWidth + stepX * (PILE_FAN_LIMIT - 1)
    private val turnedWidth: Dp = cardHeight + stepX * (PILE_FAN_LIMIT - 1)

    /** The turned half sits beside the upright one, and only claims room when something is in it. */
    fun totalWidth(hasTurned: Boolean): Dp = if (hasTurned) uprightWidth + cardWidth * HALVES_GAP_FRACTION + turnedWidth else uprightWidth

    /** Slot 0 is furthest back — up and left; slot 2 is the top card, lowest and furthest right. */
    fun uprightCentre(slot: Int): Offset2 =
        Offset2(
            x = stepX * slot + cardWidth / 2,
            y = stepY * slot + cardHeight / 2,
        )

    /**
     * The same diagonal, a quarter turn round, beside the upright half.
     *
     * Its front slot sits level with the upright half's front card, so a card leaving the top of one
     * arrives at the front of the other without changing height — the travel reads as a rotation and a
     * step sideways rather than as a swoop.
     */
    fun turnedCentre(slot: Int): Offset2 {
        val front = uprightCentre(PILE_FAN_LIMIT - 1)
        return Offset2(
            x = uprightWidth + cardWidth * HALVES_GAP_FRACTION + stepX * slot + cardHeight / 2,
            y = front.y - stepY * (PILE_FAN_LIMIT - 1 - slot),
        )
    }

    /**
     * Each half's own footprint, so its count sits over its own cards.
     *
     * Per half rather than per stack, so a board with four upright and two turned says four beside the
     * upright ones and nothing beside the turned ones — which is the truth, and is what makes the count
     * disappear when a tap takes an upright half from four to three.
     */
    fun uprightBox(): HalfBox = HalfBox(left = 0.dp, width = uprightWidth, height = totalHeight)

    fun turnedBox(): HalfBox =
        HalfBox(
            left = uprightWidth + cardWidth * HALVES_GAP_FRACTION,
            width = turnedWidth,
            height = totalHeight,
        )
}

/** A point in the stack's own space. Dp rather than pixels, because the slots are defined in cards. */
internal data class Offset2(
    val x: Dp,
    val y: Dp,
)

private fun lerp(
    from: Offset2,
    to: Offset2,
    fraction: Float,
) = Offset2(
    x = from.x + (to.x - from.x) * fraction,
    y = from.y + (to.y - from.y) * fraction,
)

/**
 * How far each copy sits from the one behind it, as a fraction of the card.
 *
 * Chosen so the card behind shows its top-left corner — where a real card prints its name and cost —
 * rather than an anonymous sliver of border. Small enough that three copies cost well under the width
 * of two, which is the space the stack exists to save.
 */
private const val STACK_STEP_X_FRACTION = 0.13f
private const val STACK_STEP_Y_FRACTION = 0.12f

/** A quarter turn, which is what tapping is. */
private const val TAPPED_TURN_DEGREES = 90f

/** Long enough to read as a card turning over, short enough not to hold up the next tap. */
private const val TAP_TRAVEL_MILLIS = 260

/** Between the upright half and the turned one. */
private const val HALVES_GAP_FRACTION = 0.06f

private val CountShape = RoundedCornerShape(2.dp)
private val CountPadding = 2.dp

/**
 * How much room this stack needs, in card widths.
 *
 * Exposed so the board can size its lands by asking rather than by re-deriving the geometry, which is
 * the kind of duplication that drifts. The turned half costs a card *height* rather than a width,
 * because a turned card lies on its side.
 */
internal fun TableLandStack.widthInCards(): Float {
    val upright = 1f + STACK_STEP_X_FRACTION * (PILE_FAN_LIMIT - 1)
    if (tapped.isEmpty()) return upright
    val turned = 1f / CARD_ASPECT_RATIO + STACK_STEP_X_FRACTION * (PILE_FAN_LIMIT - 1)
    return upright + HALVES_GAP_FRACTION + turned
}

/** How tall any stack is, in card widths. Constant: the staggering does not depend on the contents. */
internal fun stackHeightInCards(): Float = 1f / CARD_ASPECT_RATIO * (1f + STACK_STEP_Y_FRACTION * (PILE_FAN_LIMIT - 1))
