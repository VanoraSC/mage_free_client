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
import magefree.designsystem.card.CARD_ART_ASPECT_RATIO
import magefree.designsystem.card.CounterPalette
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/*
 * One land, however many copies of it, in six fixed places.
 *
 * ```
 *   ┌──────┐                 one diagonal, three slots, staggered down-right by
 *   │ name │ 0               exactly one title bar, so every copy behind still
 *   ├──────┴───┐             says its name
 *   │   name   │ 1
 *   ├──────┬───┴──┐          a turned copy leans forty-five degrees in the slot
 *   │      │ name │ 2        it was standing in, its top corner landing at the
 *   └──────┤   ╱╲ ├───┐      bottom of that card's title bar, and reaching a
 *          │  ╱  ╲│   │      further √2 ⁄ 2 of a card past the upright edge
 *          └─╱────╲───┘
 * ```
 *
 * **The slots are fixed and the cards move between them.** That is the whole design: a stack is not a
 * list that re-flows when its length changes, it is six places, and tapping is one card leaning over
 * and dropping onto the slot it is already in. Everything the player sees follows from that — the
 * upright half fills from the back (slot 0) forward, so it empties from the front and the cards behind
 * never shift.
 *
 * **A tapped copy turns where it stood.** The turned half continues the diagonal from wherever the
 * upright half ends rather than being anchored to the front of it: with two upright copies the third
 * one taps in slot two, and with none at all the only copy taps in slot zero. Anchoring the turned half
 * to the front regardless is what sent a lone tapped land sliding to the far corner of an otherwise
 * empty stack — it stood at slot zero, and tapping it flew it to slot two for no reason a player could
 * see.
 *
 * **The two halves share one diagonal.** A turned copy lies across the upright copy in the same slot,
 * covering everything below its title bar, which is what a tapped land on a table looks like and is
 * much the narrower arrangement — the turned half costs the lean's overhang, not a second stack.
 *
 * **The lean is forty-five degrees and the footprint says so.** A card is square at this size, so
 * modelling a tap as a quarter turn — width and height swapping — reserves it exactly the room it
 * already had. It leans forty-five instead, and a square on its corner is √2 across: every turned copy
 * used to draw a fifth of a card outside the footprint its stack claimed, over the stack beside it. The
 * geometry now measures the leaning card's own box, so nothing a stack draws leaves the space it asked
 * for.
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
 * the untapped top slot to the place it is arriving at, leaning over as it goes. The stack
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
 * @param halves which halves this stack keeps room for. Both for a land, which taps in place; a token
 *   pile passes only the one it has, because tap state is what makes it its own pile.
 * @param modifier the [Modifier] for the stack.
 */
@Composable
internal fun LandStack(
    stack: TableLandStack,
    width: Dp,
    palette: CounterPalette,
    artFor: TableArtResolver?,
    onPress: ((LandStackHalf) -> Unit)?,
    anchorModifier: Modifier = Modifier,
    halves: StackHalves = StackHalves.Both,
    modifier: Modifier = Modifier,
) {
    val geometry = LandStackGeometry(width, halves)
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
        // **The anchor goes on the front card, not on the pile.** An arrow measured against the whole
        // fan leaves its bounding box — visibly out of the empty air beside the cards — because the
        // box is a card and a half wide and holds the staggering as well. The front card is the one a
        // player would point at, so it is the one an arrow leaves.
        val anchoredHalf = if (turned > 0) LandStackHalf.Turned else LandStackHalf.Upright
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
                modifier =
                    if (anchoredHalf == LandStackHalf.Upright && slot == upright - 1) anchorModifier else Modifier,
            )
        }
        // **The turned half continues the diagonal where the upright half stops**, so a card taps in
        // the place it was standing in. Its front slot is where the frontmost upright copy would be if
        // it were still upright — `upright + turned` places along the diagonal, capped at the three the
        // diagonal holds — and each new arrival takes the slot behind the ones already turned, which is
        // the one it just vacated. Nothing already lying down ever moves.
        //
        // They are *drawn* back to front, so the lowest and furthest right is on top in this half
        // exactly as it is in the other. Drawing them in arrival order instead puts the newest card on
        // top and the two halves end up mirror images of each other, which reads as a mistake.
        //
        // **The travelling card is drawn in its own place in that order, not on top of everything.**
        // Drawing it last and then handing over to a static card that belongs further back makes the
        // card flick from front to back at the instant it lands, which reads as a glitch rather than as
        // a card being put down. Its z-position is settled before it starts moving and never changes.
        val firstTurnedSlot = minOf(upright + turned, PILE_FAN_LIMIT) - turned
        val arriving = travel.value < 1f
        // Past three there is no free place, so the arrival is a transient rather than one of the
        // drawn slots — otherwise the slot it "took" would lose the card that is genuinely in it.
        val takesASlot = stack.tapped.size <= PILE_FAN_LIMIT
        val leaving = geometry.uprightCentre(maxOf(minOf(stack.untapped.size + 1, PILE_FAN_LIMIT) - 1, 0))

        // Past three turned there is no free place for the arrival to take, so it leans over onto the
        // front one and is simply gone when it lands — by then the count has taken over saying how many
        // there are. **Drawn before the turned half, so it travels behind it**, which is where it is
        // going: drawn after, it flew over the three cards already lying there and then disappeared
        // underneath them at the instant it landed, which is the same flick the slotted case was fixed
        // for. Where a card ends up is decided before it starts moving, in both cases.
        if (arriving && !takesASlot) {
            StackedCard(
                stack = stack,
                geometry = geometry,
                centre = lerp(leaving, geometry.turnedCentre(PILE_FAN_LIMIT - 1), travel.value),
                turn = travel.value,
                width = width,
                palette = palette,
                artFor = artFor,
                onPress = null,
            )
        }

        for (slot in firstTurnedSlot until firstTurnedSlot + turned) {
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
                modifier =
                    if (anchoredHalf == LandStackHalf.Turned && slot == firstTurnedSlot + turned - 1) {
                        anchorModifier
                    } else {
                        Modifier
                    },
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
 * One copy, laid out square and leaned by [turn] of the full lean about its own centre.
 *
 * The card is always laid out upright and rotated here rather than being handed `tapped = true`,
 * because the stack needs *partial* turns — a card halfway through a tap is only part of the way over,
 * and a footprint that changed shape at some point during that would jump. The board's own tapped
 * footprint logic is right everywhere it is used and wrong inside a fixed-slot layout, which has
 * already decided where everything goes.
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
    modifier: Modifier = Modifier,
) {
    // **Each half is drawn from its own copies.** The halves are interchangeable in everything the
    // stack's key compares, but not in the one signal the key deliberately ignores: an untapped land is
    // in `canPlayObjects` and a tapped one is not, so drawing every copy from a single representative
    // put a green *playable* border on lands that had already been used. Which half a card is in is
    // decided by how far over it is, so a card halfway through a tap changes hands at the midpoint —
    // which is when it stops being a land you could tap.
    val permanent =
        if (turn > 0.5f) {
            stack.tapped.firstOrNull() ?: stack.representative
        } else {
            stack.untapped.firstOrNull() ?: stack.representative
        }
    Box(
        modifier =
            modifier
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
            focus = LocalBoardFocus.current,
        )
    }
}

/**
 * The stack's whole shape, in units of the card's own width.
 *
 * **One derivation, read two ways.** The footprint used to be worked out twice — in Dp here for
 * drawing, and again in card widths by [stackWidthInCards] for the board's own sizing — and two copies
 * of the same arithmetic is exactly the arrangement that lets a correction land in one of them. The
 * composable and the board now read the same numbers.
 *
 * Every distance is a fraction of the card rather than a fixed dp, for the reason the card tier
 * already learned the hard way: a step that reads well at 60dp disappears at 200dp, and a board that
 * derives its card size draws both.
 */
private object StackShape {
    /** The card is square at board size. Nothing below assumes that; it reads the ratio. */
    const val CARD_HEIGHT: Float = 1f / BOARD_CARD_ASPECT_RATIO

    /** How far down and right the diagonal runs across the three slots it holds. */
    val fanX: Float get() = stepX * (PILE_FAN_LIMIT - 1)

    val fanY: Float get() = stepY * (PILE_FAN_LIMIT - 1)

    private val turn: Float = TAPPED_TURN_DEGREES * PI.toFloat() / 180f
    private val turnCos: Float = abs(cos(turn))
    private val turnSin: Float = abs(sin(turn))

    /**
     * Half the upright box a leaning card occupies — the correction the square card needed.
     *
     * The old arithmetic modelled a tap as a **quarter** turn, where a card's width and height simply
     * swap. That is a no-op on a square, so a turned copy was reserved exactly the room an upright one
     * takes. The lean is forty-five degrees and a square on its corner is √2 across, so every turned
     * copy drew a fifth of a card outside its stack's footprint, over the stack beside it.
     */
    val turnedHalfWidth: Float = (turnCos + CARD_HEIGHT * turnSin) / 2f
    val turnedHalfHeight: Float = (turnSin + CARD_HEIGHT * turnCos) / 2f

    /**
     * The strip the square leaves over the art: the card's own title bar, where the name and cost are.
     *
     * What the stagger exposes is that bar, so the bar **is** the stagger rather than a fraction
     * calibrated to approximate it. Measured off the drawn card — the title bar is whatever the art's
     * aspect leaves — because that is the thing being uncovered, not the whole printing it came from.
     */
    val titleBand: Float = maxOf(0f, CARD_HEIGHT - 1f / CARD_ART_ASPECT_RATIO)

    val stepX: Float = STACK_STEP_X_FRACTION
    val stepY: Float = titleBand

    /**
     * How far the turned card's own centre sits below the upright one it lies across.
     *
     * Placed so its topmost corner falls at the bottom of that card's title bar: the upright card keeps
     * its name and its cost, and the turned one reads as having been laid over the rest of it, which is
     * what tapping a land on a table looks like.
     */
    val turnedDrop: Float = turnedHalfHeight - CARD_HEIGHT / 2f + titleBand

    /**
     * How far the whole stack shifts right to keep every slot at a non-negative offset.
     *
     * **Only when the leaning card is the wider one.** Written as a maximum rather than as a
     * subtraction because that is the difference between the two cases, and this arithmetic has been
     * inverted once already. A stack with no turned half has nothing leaning past its left edge and so
     * shifts nothing.
     */
    fun overhang(halves: StackHalves): Float = if (halves.turned) maxOf(0f, turnedHalfWidth - 0.5f) else 0f

    /**
     * Where the first turned card's centre sits below the top of the stack.
     *
     * **[turnedDrop] is a relationship to the upright card underneath, so a stack with no upright half
     * does not pay it.** With one, the turned card lands on that card's title bar and the drop is the
     * distance to it. With none — every copy in this pile is tapped — there is nothing to lie across,
     * and dropping anyway left the pile's cards a title bar lower than every card beside them while
     * its box still started level with them. That is the sag Pete saw on a pile of two tapped Zombies
     * and did not see on a single one, which is drawn as a card and never had a half to reserve.
     */
    fun turnedTopCentre(halves: StackHalves): Float = if (halves.upright) CARD_HEIGHT / 2f + turnedDrop else turnedHalfHeight

    /**
     * **A land stack's footprint never changes when a land taps.** It allows for the turned half
     * whether or not anything is in it, because a land moves between the halves of the stack it is
     * already in: one that grew as its first land tapped would resize the column, which resizes every
     * card on the board, and §7.3 is clear that movement means a game action happened.
     *
     * **A token pile is the other case, and it is why this takes an argument at all.** Tap state is
     * part of a token pile's identity — a tapped Zombie is doing something, so it is drawn as its own
     * pile rather than as the turned half of one — which means a pile is uniformly upright or
     * uniformly turned and can never gain the half it does not have. Reserving one anyway made every
     * pile on the board a full half-card taller and wider than the cards in it, and the board pays for
     * that in card size everywhere.
     *
     * The width is the furthest right edge on the diagonal: an upright card's own, or a leaning card's
     * centred on the same point, whichever reaches further. The height is the same question downward,
     * measured from whichever half starts highest.
     */
    fun totalWidth(halves: StackHalves): Float =
        overhang(halves) + fanX +
            maxOf(
                if (halves.upright) 1f else 0f,
                if (halves.turned) 0.5f + turnedHalfWidth else 0f,
            )

    fun totalHeight(halves: StackHalves): Float =
        fanY +
            maxOf(
                if (halves.upright) CARD_HEIGHT else 0f,
                if (halves.turned) turnedTopCentre(halves) + turnedHalfHeight else 0f,
            )

    /** The upright half's own right edge, which is nearer than the stack's — a leaning card reaches past it. */
    fun uprightWidth(halves: StackHalves): Float = overhang(halves) + fanX + 1f
}

/**
 * Which halves a stack keeps room for.
 *
 * **Not which halves it currently holds** — for a land stack the answer is both, always, because a
 * land taps into the stack it is already in and a footprint that changed under it would move every
 * card on the board. It is a question about what this stack *can* hold, which is why it is a property
 * of the caller rather than something read off the contents.
 */
internal data class StackHalves(
    val upright: Boolean,
    val turned: Boolean,
) {
    companion object {
        /** A land stack: a land can tap without leaving, so both halves are always reserved. */
        val Both: StackHalves = StackHalves(upright = true, turned = true)

        /**
         * A token pile, which is uniformly upright or uniformly turned by construction — see
         * [BattlefieldSide.entriesIn]. A pile with neither half is not a thing the row draws; it
         * reserves both rather than collapsing to nothing, since a zero-sized box is the one answer
         * that could not be right.
         */
        fun of(stack: TableLandStack): StackHalves =
            StackHalves(upright = stack.untapped.isNotEmpty(), turned = stack.tapped.isNotEmpty())
                .takeIf { it.upright || it.turned } ?: Both
    }
}

/** [StackShape] in dp, for a card [cardWidth] wide holding [halves]. */
private class LandStackGeometry(
    val cardWidth: Dp,
    val halves: StackHalves,
) {
    val cardHeight: Dp = cardWidth * StackShape.CARD_HEIGHT
    val totalWidth: Dp = cardWidth * StackShape.totalWidth(halves)
    val totalHeight: Dp = cardWidth * StackShape.totalHeight(halves)

    /** Slot 0 is furthest back — up and left; slot 2 is the top card, lowest and furthest right. */
    fun uprightCentre(slot: Int): Offset2 =
        Offset2(
            x = cardWidth * (StackShape.overhang(halves) + StackShape.stepX * slot + 0.5f),
            y = cardWidth * (StackShape.stepY * slot + StackShape.CARD_HEIGHT / 2f),
        )

    /**
     * The same slot, leaning over and dropped onto the card in it.
     *
     * The two halves share one diagonal rather than sitting side by side: a turned copy lies *across*
     * the upright copy at the same slot, covering everything under its title bar. That is both what a
     * tapped land looks like on a table and much the narrower arrangement — the turned half costs the
     * lean's overhang rather than a second stack's width.
     *
     * It also makes the travel honest. A card leaning over in slot two drops onto slot two; it does not
     * fly across the board to a separate pile, because there is no separate pile.
     */
    fun turnedCentre(slot: Int): Offset2 =
        Offset2(
            x = uprightCentre(slot).x,
            y = cardWidth * (StackShape.turnedTopCentre(halves) + StackShape.stepY * slot),
        )

    /**
     * Each half's own count sits in a different corner, because the halves overlap.
     *
     * The upright one goes top-right of the **upright** half — not of the stack, whose right edge only
     * a leaning card reaches, and which would leave the count hanging in space beside the cards it
     * counts. The turned one goes bottom-right of the whole stack, under them, for the same reason in
     * the other direction. Per half rather than per stack: a board with four upright and two turned
     * says four over the upright ones and nothing over the turned ones, which is the truth.
     */
    fun uprightBox(): HalfBox =
        HalfBox(left = 0.dp, width = cardWidth * StackShape.uprightWidth(halves), height = totalHeight, atTop = true)

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
 * How far each copy sits sideways from the one behind it, as a fraction of the card.
 *
 * Only the sideways step is a chosen number: the downward one is the title bar itself
 * ([StackShape.stepY]), because the bar is the thing being uncovered. This one exists so the stack
 * reads as separate cards rather than as one block, and is small enough that three copies cost well
 * under the width of two — which is the space the stack exists to save.
 */
private const val STACK_STEP_X_FRACTION = 0.13f

/** The lean a tapped card takes, matching the card tier's own. */
private const val TAPPED_TURN_DEGREES = 45f

/** Long enough to read as a card turning over, short enough not to hold up the next tap. */
private const val TAP_TRAVEL_MILLIS = 260

private val CountShape = RoundedCornerShape(2.dp)
private val CountPadding = 2.dp

/**
 * How much room a stack needs, in card widths — the same whatever is in the halves it keeps.
 *
 * Exposed so the board can size its lands by asking rather than by re-deriving the geometry. It used
 * to re-derive it, and the two copies disagreed the moment the card became square and the lean became
 * forty-five degrees: this one still described a card lying flat on its side.
 *
 * [halves] defaults to both, which is a land stack — the caller that must never change size. A token
 * pile passes its own, since it can only ever hold the one it was built from.
 */
internal fun stackWidthInCards(halves: StackHalves = StackHalves.Both): Float = StackShape.totalWidth(halves)

/** How tall a stack is, in card widths — again the same whatever is in the halves it keeps. */
internal fun stackHeightInCards(halves: StackHalves = StackHalves.Both): Float = StackShape.totalHeight(halves)

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

/** One card's height, in card widths — the unit everything above measures in. */
internal fun cardHeightInCards(): Float = StackShape.CARD_HEIGHT
