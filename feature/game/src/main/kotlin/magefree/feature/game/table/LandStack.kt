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
import magefree.designsystem.card.BOARD_CARD_ASPECT_RATIO
import magefree.designsystem.card.BoardCard
import magefree.designsystem.card.CounterPalette

/*
 * One land, however many copies of it, in six fixed places.
 *
 * ```
 *      ┌────┐                one diagonal, three slots, staggered down-right as
 *      │ 0  │                though the copies were attached to each other
 *   ┌──┴──┬─┴──┐
 *   │  0  │ 1  │             a turned copy lies ACROSS the upright one in its own
 *   └──┬──┴──┬─┴──┐          slot, covering the bottom half and leaving the name,
 *      │  1  │ 2  │          the cost and most of the art to read
 *      └──┬──┴──┬─┴──┐
 *         │  2  │    │
 *         └─────┴────┘
 * ```
 *
 * **The slots are fixed and the cards move between them.** That is the whole design: a stack is not a
 * list that re-flows when its length changes, it is six places, and tapping is one card turning a
 * quarter and dropping onto the slot it is already in. Everything the player sees follows from that —
 * the upright half fills from the back (slot 0) forward, so it empties from the front and the cards
 * behind never shift; the turned half fills from the front (slot 2) backward, so each newly-tapped card
 * takes the nearest free place and the ones already there never move.
 *
 * **The two halves share one diagonal.** A turned copy lies across the upright copy in the same slot,
 * covering its bottom half, which is what a tapped land on a table looks like and is much the narrower
 * arrangement — the turned half costs the overhang of a card on its side, not a second stack.
 *
 * **The footprint never changes when a land taps.** It always allows for the turned half, occupied or
 * not. A stack that grew as its first land tapped would resize the land corner, which resizes every
 * card on the board; §7.3 is clear that movement means a game action happened, and one land turning
 * must not make the opponent's creatures jump.
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
 * @param onPress called with the half that was pressed. Hit testing does the work: the turned cards are
 *   drawn over the upright ones, so a press on the exposed strip below them reaches a turned card and a
 *   press on the top half reaches an upright one, with no coordinate arithmetic anywhere.
 * @param modifier the [Modifier] for the stack.
 */
@Composable
internal fun LandStack(
    stack: TableLandStack,
    width: Dp,
    palette: CounterPalette,
    artFor: TableArtResolver?,
    onPress: ((LandStackHalf) -> Unit)?,
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
                .size(width = geometry.totalWidth, height = geometry.totalHeight)
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
                onPress = onPress?.let { press -> { press(LandStackHalf.Upright) } },
            )
        }
        // Arrivals fill slot 2 first, then 1, then 0 — the nearest free place each time — but they are
        // *drawn* back to front, so the lowest and furthest right is on top in this half exactly as it
        // is in the other. Drawing them in arrival order instead puts the newest card on top and the
        // two halves end up mirror images of each other, which reads as a mistake rather than a rule.
        //
        // **The travelling card is drawn in its own place in that order, not on top of everything.**
        // Drawing it last and then handing over to a static card that belongs further back makes the
        // card flick from front to back at the instant it lands, which reads as a glitch rather than as
        // a card being put down. Its z-position is settled before it starts moving and never changes.
        val firstTurnedSlot = PILE_FAN_LIMIT - turned
        val arriving = travel.value < 1f
        // Past three there is no free place, so the arrival is a transient rather than one of the
        // drawn slots — otherwise the slot it "took" would lose the card that is genuinely in it.
        val takesASlot = stack.tapped.size <= PILE_FAN_LIMIT
        val leaving = geometry.uprightCentre(maxOf(minOf(stack.untapped.size + 1, PILE_FAN_LIMIT) - 1, 0))
        for (slot in firstTurnedSlot until PILE_FAN_LIMIT) {
            val newest = slot == firstTurnedSlot && arriving && takesASlot
            StackedCard(
                stack = stack,
                geometry = geometry,
                centre = if (newest) lerp(leaving, geometry.turnedCentre(slot), travel.value) else geometry.turnedCentre(slot),
                turn = if (newest) travel.value else 1f,
                width = width,
                palette = palette,
                artFor = artFor,
                // A card in flight is not a target. Pressing where it *was* would act on a stack that
                // has already changed underneath the finger.
                onPress = if (newest) null else onPress?.let { press -> { press(LandStackHalf.Turned) } },
            )
        }

        // Past three turned there is no free place for the arrival to take, so it flies to the front
        // one and is simply gone when it lands — by then the count has taken over saying how many
        // there are. Drawn behind the whole turned half, which is where it is going.
        if (arriving && !takesASlot) {
            StackedCard(
                stack = stack,
                geometry = geometry,
                centre = lerp(leaving, geometry.turnedCentre(0), travel.value),
                turn = travel.value,
                width = width,
                palette = palette,
                artFor = artFor,
                onPress = null,
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
        contentAlignment = if (box.atTop) Alignment.TopEnd else Alignment.BottomEnd,
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

/** One half's footprint inside the stack, and which corner of it a count belongs in. */
internal data class HalfBox(
    val left: Dp,
    val width: Dp,
    val height: Dp,
    val atTop: Boolean,
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
    onPress: (() -> Unit)?,
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
            onTap = onPress,
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
    val cardHeight: Dp = cardWidth / BOARD_CARD_ASPECT_RATIO
    private val stepX: Dp = cardWidth * STACK_STEP_X_FRACTION
    private val stepY: Dp = cardHeight * STACK_STEP_Y_FRACTION

    /**
     * How far the turned card's own centre sits below the upright one it lies across.
     *
     * Placed so its top edge falls at the upright card's waist: the upright card keeps its whole top
     * half — the name, the cost, most of the art — and the turned one reads as having been laid over
     * the bottom of it, which is what tapping a land on a table looks like.
     */
    private val turnedDrop: Dp = cardWidth / 2

    /**
     * A turned card is wider than an upright one, so it hangs off both sides of the diagonal. The
     * whole stack shifts right by that overhang, which keeps every slot at a non-negative offset.
     */
    private val turnedOverhang: Dp = (cardHeight - cardWidth) / 2

    /**
     * **The footprint never changes when a land taps.** It always allows for the turned half, whether
     * or not anything is in it. A stack that grew as its first land tapped would resize the corner,
     * which resizes every card on the board — and §7.3 is clear that movement means a game action
     * happened. One land turning must not make the opponent's creatures jump.
     */
    val totalWidth: Dp = cardHeight + stepX * (PILE_FAN_LIMIT - 1)
    val totalHeight: Dp = cardHeight / 2 + turnedDrop + cardWidth / 2 + stepY * (PILE_FAN_LIMIT - 1)

    /** Slot 0 is furthest back — up and left; slot 2 is the top card, lowest and furthest right. */
    fun uprightCentre(slot: Int): Offset2 =
        Offset2(
            x = turnedOverhang + stepX * slot + cardWidth / 2,
            y = stepY * slot + cardHeight / 2,
        )

    /**
     * The same slot, a quarter turn round and dropped onto the card in it.
     *
     * The two halves share one diagonal rather than sitting side by side: a turned copy lies *across*
     * the upright copy at the same slot, covering its bottom and leaving its top to read. That is both
     * what a tapped land looks like on a table and much the narrower arrangement — the turned half
     * costs the overhang rather than a second stack's width.
     *
     * It also makes the travel honest. A card leaving slot two rotates and drops onto slot two; it does
     * not fly across the board to a separate pile, because there is no separate pile.
     */
    fun turnedCentre(slot: Int): Offset2 {
        val upright = uprightCentre(slot)
        return Offset2(x = upright.x, y = upright.y + turnedDrop)
    }

    /**
     * Each half's own count sits in a different corner, because the halves now overlap.
     *
     * The upright one goes top-right, above the cards, where the down-right stagger leaves the corner
     * clear. The turned one goes bottom-right, under them, for the same reason in the other direction.
     * Per half rather than per stack: a board with four upright and two turned says four over the
     * upright ones and nothing over the turned ones, which is the truth.
     */
    fun uprightBox(): HalfBox = HalfBox(left = 0.dp, width = totalWidth, height = totalHeight, atTop = true)

    fun turnedBox(): HalfBox = HalfBox(left = 0.dp, width = totalWidth, height = totalHeight, atTop = false)
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

private val CountShape = RoundedCornerShape(2.dp)
private val CountPadding = 2.dp

/**
 * How much room a stack needs, in card widths — the same whatever is in it.
 *
 * Exposed so the board can size its lands by asking rather than by re-deriving the geometry, which is
 * the kind of duplication that drifts. It is a card *height* across, not a width, because a turned copy
 * lies on its side and hangs off both edges of the diagonal.
 */
internal fun stackWidthInCards(): Float = 1f / BOARD_CARD_ASPECT_RATIO + STACK_STEP_X_FRACTION * (PILE_FAN_LIMIT - 1)

/**
 * How tall a stack is, in card widths — again the same whatever is in it.
 *
 * Half an upright card down to the waist, then the turned card laid across it, then the diagonal.
 */
internal fun stackHeightInCards(): Float {
    val diagonal = 1f / BOARD_CARD_ASPECT_RATIO * STACK_STEP_Y_FRACTION * (PILE_FAN_LIMIT - 1)
    return 1f / BOARD_CARD_ASPECT_RATIO / 2f + 0.5f + 0.5f + diagonal
}

/**
 * Which part of a stack was pressed.
 *
 * **The two halves are different affordances and the component says which, rather than deciding.** An
 * upright copy's exposed top half is the card you would pick up; a turned copy's exposed strip below it
 * is the card already lying down. What each means is a question about the game — pressing an upright
 * land activates its mana ability, and there is no such thing as untapping one at will — so the board
 * answers it, not the stack. Collapsing them into one press would leave the board unable to tell
 * "act on this land" from "look at the one you already used".
 */
enum class LandStackHalf {
    /** An upright copy: the top half of the diagonal, the part no turned card is lying across. */
    Upright,

    /** A turned copy: the strip below the upright cards, where a tapped one shows past them. */
    Turned,
}
