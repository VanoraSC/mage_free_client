package magefree.feature.game.table

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.style.TextAlign
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
 * One land, however many copies of it, drawn as at most two cards and a tally.
 *
 * ```
 *   ┌─┬────────┐        ┌─┬────────┐        ┌─┬────────┐
 *   │4│  name  │        │2│  name  │        │ │        │
 *   │ │        │        │2├───╲────┤        │4│   ╲    │
 *   │ │  art   │        │ │ name╲  │        │ │ name╲  │
 *   └─┴────────┘        └─┴──────╲─┘        └─┴──────╲─┘
 *   all four upright    two and two         all four turned
 * ```
 *
 * **A stack is one card, in one or both states.** It used to fan six copies down a diagonal, three
 * upright and three leaning, and count only past what the fan could draw. That spent a card and a half
 * of width and nearly two of height on a picture of *how many* — which is a number, and which the
 * tally now says exactly. What the picture is for is *which land it is* and *can I still use it*, and
 * one face of each state answers both.
 *
 * **The tally is the count, and it is the only count.** Nothing here is countable by looking any more:
 * four upright Plains and one upright Plains draw the same card. So the numbers are always shown when
 * they are not zero, rather than appearing at four the way the fan's badges did — a half with nothing
 * in it writes nothing, which is what makes the bar read as *this many standing, this many turned*.
 *
 * **Off-white for upright and grey for turned**, because that is the difference the numbers are there
 * to carry: what you can still tap is the brighter one. Colour rather than position alone, so a glance
 * at a busy land corner separates the two without reading either.
 *
 * **A turned copy lies across the upright one**, covering everything below its title bar, which is
 * what a tapped land on a table looks like. With none upright it lies where the upright one would have
 * stood rather than sliding somewhere else — a lone tapped land must not appear to have come loose.
 *
 * **The footprint never changes when a land taps.** It always allows for the turned card, occupied or
 * not. A stack that grew as its first land tapped would resize the land corner, which resizes every
 * card on the board; §7.3 is clear that movement means a game action happened, and one land turning
 * must not make the opponent's creatures jump.
 *
 * **The lean is forty-five degrees and the footprint says so.** A card is square at this size, so
 * modelling a tap as a quarter turn — width and height swapping — reserves it exactly the room it
 * already had. A square on its corner is √2 across, and the geometry measures that.
 *
 * **The travelling card is drawn, not moved.** The copies are identical, so tracking which server id
 * is upright would be work in service of a distinction nobody can see. The two faces are drawn from
 * counts, and when the tapped count rises a single card is animated from the upright position to the
 * turned one, leaning as it goes.
 */

/**
 * One land stack: the upright face, the turned face, and the tally.
 *
 * @param stack the copies, both halves.
 * @param width the card width the board is drawing at.
 * @param palette the board's counter palette, so a counter kind keeps its colour across every card.
 * @param artFor resolves the card's art from the printing the server named.
 * @param onPress called with the half that was pressed. Hit testing does the work: the turned card is
 *   drawn over the upright one, so a press on the exposed strip below it reaches a turned card and a
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
        Tally(
            untapped = stack.untapped.size,
            tapped = stack.tapped.size,
            stackId = stack.inspectId,
            modifier = Modifier.width(geometry.tallyWidth).fillMaxHeight(),
        )

        // **The anchor goes on the front card**, which is the turned one when there is one: it is the
        // card a player would point at, and an arrow measured against the whole box would leave it —
        // the box is wider than a card and holds the lean as well.
        val anchoredHalf = if (stack.tapped.isNotEmpty()) LandStackHalf.Turned else LandStackHalf.Upright

        if (stack.untapped.isNotEmpty()) {
            StackedCard(
                stack = stack,
                geometry = geometry,
                centre = geometry.uprightCentre(),
                turn = 0f,
                width = width,
                palette = palette,
                artFor = artFor,
                onPress = onPress?.let { press -> { press(LandStackHalf.Upright) } },
                modifier = if (anchoredHalf == LandStackHalf.Upright) anchorModifier else Modifier,
            )
        }

        if (stack.tapped.isNotEmpty()) {
            // **Drawn where it is going, or on its way there.** A card halfway through a tap is part
            // of the way over and part of the way across; where it ends up is settled before it starts
            // moving, so nothing flicks from one place to another at the instant it lands.
            val arriving = travel.value < 1f
            StackedCard(
                stack = stack,
                geometry = geometry,
                centre =
                    if (arriving) {
                        lerp(geometry.uprightCentre(), geometry.turnedCentre(), travel.value)
                    } else {
                        geometry.turnedCentre()
                    },
                turn = if (arriving) travel.value else 1f,
                width = width,
                palette = palette,
                artFor = artFor,
                // A card in flight is not a target. Pressing where it *was* would act on a stack that
                // has already changed underneath the finger.
                onPress = if (arriving) null else onPress?.let { press -> { press(LandStackHalf.Turned) } },
                modifier = if (anchoredHalf == LandStackHalf.Turned) anchorModifier else Modifier,
            )
        }
    }
}

/**
 * How many are standing and how many are turned, down the left edge of the stack.
 *
 * **The only place the count exists.** One Plains and four Plains draw the same face now, so the
 * picture has stopped answering *how many* and this is the answer. A half with nothing in it writes
 * nothing — a grey zero beside an untouched stack is noise, and the absence says the same thing.
 */
@Composable
private fun Tally(
    untapped: Int,
    tapped: Int,
    stackId: String,
    modifier: Modifier = Modifier,
) {
    if (untapped == 0 && tapped == 0) return

    Column(
        modifier = modifier.testTag(BattlefieldTestTags.stackTally(stackId)),
        verticalArrangement = Arrangement.spacedBy(TallyGap, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (untapped > 0) {
            TallyNumber(
                count = untapped,
                // Off-white: what is still standing is what the player can still use, so it is the
                // brighter of the two and the one a glance lands on.
                color = BoardSurface.onSurface,
                tag = BattlefieldTestTags.stackCount(stackId),
            )
        }
        if (tapped > 0) {
            TallyNumber(
                count = tapped,
                // Grey: already spent. Dimmer than the standing count on purpose — it is the number
                // you check second, and only when you are counting what is left.
                color = BoardSurface.onSurfaceMuted,
                tag = BattlefieldTestTags.stackTappedCount(stackId),
            )
        }
    }
}

@Composable
private fun TallyNumber(
    count: Int,
    color: androidx.compose.ui.graphics.Color,
    tag: String,
) {
    Text(
        text = "$count",
        style = BoardTypography.cardStats,
        color = color,
        maxLines = 1,
        softWrap = false,
        textAlign = TextAlign.Center,
        modifier =
            Modifier
                .background(BoardSurface.zone, TallyShape)
                .testTag(tag),
    )
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
    /** An upright copy: the top half, the part no turned card is lying across. */
    Upright,

    /** A turned copy: the strip below the upright card, where a tapped one shows past it. */
    Turned,
}

/**
 * One copy, laid out square and leaned by [turn] of the full lean about its own centre.
 *
 * The card is always laid out upright and rotated here rather than being handed `tapped = true`,
 * because the stack needs *partial* turns — a card halfway through a tap is only part of the way over,
 * and a footprint that changed shape at some point during that would jump. The board's own tapped
 * footprint logic is right everywhere it is used and wrong inside a fixed-place layout, which has
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
 * **One derivation, read two ways.** The footprint is worked out here for drawing and read by
 * [stackWidthInCards] for the board's own sizing, from the same numbers — two copies of this
 * arithmetic is exactly the arrangement that lets a correction land in one of them.
 *
 * Every distance is a fraction of the card rather than a fixed dp, for the reason the card tier
 * already learned the hard way: a step that reads well at 60dp disappears at 200dp, and a board that
 * derives its card size draws both.
 */
private object StackShape {
    /** The card is square at board size. Nothing below assumes that; it reads the ratio. */
    const val CARD_HEIGHT: Float = 1f / BOARD_CARD_ASPECT_RATIO

    private val turn: Float = TAPPED_TURN_DEGREES * PI.toFloat() / 180f
    private val turnCos: Float = abs(cos(turn))
    private val turnSin: Float = abs(sin(turn))

    /**
     * Half the upright box a leaning card occupies.
     *
     * The lean is forty-five degrees and a square on its corner is √2 across, so a turned copy needs
     * measurably more room than an upright one — modelling a tap as a quarter turn, where width and
     * height swap, is a no-op on a square and reserved it nothing.
     */
    val turnedHalfWidth: Float = (turnCos + CARD_HEIGHT * turnSin) / 2f
    val turnedHalfHeight: Float = (turnSin + CARD_HEIGHT * turnCos) / 2f

    /**
     * The strip the square leaves over the art: the card's own title bar, where the name and cost are.
     *
     * What the turned card leaves showing is that bar, so the bar **is** the drop rather than a
     * fraction calibrated to approximate it. Measured off the drawn card, because that is the thing
     * being uncovered.
     */
    val titleBand: Float = maxOf(0f, CARD_HEIGHT - 1f / CARD_ART_ASPECT_RATIO)

    /** How wide the tally is, as a fraction of the card it sits beside. */
    val tally: Float = TALLY_WIDTH_FRACTION

    /**
     * How far the whole stack shifts right to keep the leaning card at a non-negative offset.
     *
     * **Only when the leaning card is the wider one.** Written as a maximum rather than as a
     * subtraction because that is the difference between the two cases, and this arithmetic has been
     * inverted once already. A stack with no turned half has nothing leaning past its left edge.
     */
    fun overhang(halves: StackHalves): Float = if (halves.turned) maxOf(0f, turnedHalfWidth - 0.5f) else 0f

    /**
     * Where the turned card's centre sits below the top of the stack.
     *
     * **The drop is a relationship to the card underneath, so a stack with no upright half does not
     * pay it.** With one, the turned card lands on that card's title bar and the drop is the distance
     * to it. With none there is nothing to lie across, and dropping anyway left a pile of tapped
     * tokens hanging a title bar below every card beside it.
     */
    fun turnedCentreY(halves: StackHalves): Float = if (halves.upright) turnedHalfHeight + titleBand else turnedHalfHeight

    /**
     * **A land stack's footprint never changes when a land taps.** It allows for the turned card
     * whether or not anything is in it, because a land moves between the halves of the stack it is
     * already in: one that grew as its first land tapped would resize the column, which resizes every
     * card on the board.
     *
     * **A token pile is the other case, and it is why this takes an argument at all.** Tap state is
     * part of a token pile's identity — a tapped Zombie is doing something, so it is drawn as its own
     * pile — which means a pile is uniformly upright or uniformly turned and can never gain the half
     * it does not have. Reserving one anyway made every pile taller and wider than the card in it, and
     * the board pays for that in card size everywhere.
     */
    fun totalWidth(halves: StackHalves): Float =
        tally + overhang(halves) +
            maxOf(
                if (halves.upright) 1f else 0f,
                if (halves.turned) 0.5f + turnedHalfWidth else 0f,
            )

    fun totalHeight(halves: StackHalves): Float =
        maxOf(
            if (halves.upright) CARD_HEIGHT else 0f,
            if (halves.turned) turnedCentreY(halves) + turnedHalfHeight else 0f,
        )
}

/** [StackShape] in dp, for a card [cardWidth] wide holding [halves]. */
private class LandStackGeometry(
    val cardWidth: Dp,
    val halves: StackHalves,
) {
    val cardHeight: Dp = cardWidth * StackShape.CARD_HEIGHT
    val totalWidth: Dp = cardWidth * StackShape.totalWidth(halves)
    val totalHeight: Dp = cardWidth * StackShape.totalHeight(halves)
    val tallyWidth: Dp = cardWidth * StackShape.tally

    /** The upright card's centre, measured from the stack's own top-left. */
    fun uprightCentre(): Offset2 =
        Offset2(
            x = cardWidth * (StackShape.tally + StackShape.overhang(halves) + 0.5f),
            y = cardWidth * (StackShape.CARD_HEIGHT / 2f),
        )

    /**
     * The turned card's centre.
     *
     * It shares the upright card's vertical, so a card taps in the place it was standing in rather
     * than sliding somewhere else; it drops far enough that the upright card keeps its name and its
     * cost, which is what a tapped land laid over an untapped one looks like on a table.
     */
    fun turnedCentre(): Offset2 =
        Offset2(
            x = uprightCentre().x,
            y = cardWidth * StackShape.turnedCentreY(halves),
        )
}

/** A point inside the stack, in dp from its own top-left. */
internal data class Offset2(
    val x: Dp,
    val y: Dp,
)

private fun lerp(
    from: Offset2,
    to: Offset2,
    t: Float,
): Offset2 = Offset2(x = from.x + (to.x - from.x) * t, y = from.y + (to.y - from.y) * t)

/** The lean a tapped card takes, matching the card tier's own. */
private const val TAPPED_TURN_DEGREES = 45f

/** Long enough to read as a card turning over, short enough not to hold up the next tap. */
private const val TAP_TRAVEL_MILLIS = 260

/**
 * How wide the tally is, as a fraction of the card beside it.
 *
 * A fraction rather than a fixed dp, like every other distance here: a bar that reads well beside a
 * 112dp land is a smear beside a 60dp one. Wide enough for two digits at the board's stats size, which
 * is as many as a land count reaches in a game anybody finishes.
 */
private const val TALLY_WIDTH_FRACTION = 0.22f

private val TallyShape = RoundedCornerShape(2.dp)
private val TallyGap = 2.dp

/**
 * How much room a stack needs, in card widths — the same whatever is in the halves it keeps.
 *
 * Exposed so the board can size its lands by asking rather than by re-deriving the geometry. It used
 * to re-derive it, and the two copies disagreed the moment the card became square and the lean became
 * forty-five degrees.
 *
 * [halves] defaults to both, which is a land stack — the caller that must never change size. A token
 * pile passes its own, since it can only ever hold the one it was built from.
 */
internal fun stackWidthInCards(halves: StackHalves = StackHalves.Both): Float = StackShape.totalWidth(halves)

/** How tall a stack is, in card widths — again the same whatever is in the halves it keeps. */
internal fun stackHeightInCards(halves: StackHalves = StackHalves.Both): Float = StackShape.totalHeight(halves)

/** One card's height, in card widths — the unit everything above measures in. */
internal fun cardHeightInCards(): Float = StackShape.CARD_HEIGHT

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
