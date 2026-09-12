package magefree.feature.game.table

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.delay
import magefree.cards.art.CardArtRequest
import magefree.designsystem.card.BoardCard
import magefree.designsystem.card.BoardCardState
import magefree.designsystem.card.CounterPalette

/*
 * Cards arriving on the stack, drawn travelling.
 *
 * **A spell appearing on the stack out of nowhere is the one moment the board stops being a table.**
 * A player casts from their hand and the card should go *somewhere*; an ability is activated and the
 * permanent it came from should be visibly the thing that produced it. Upstream's own client makes the
 * second point bluntly — `StackAbilityView` carries a nested `sourceCard` and the reference GUI
 * replaces the ability with it outright — and the first is how the game is played on a table.
 *
 * **Where it flies from is the server's wherever the server says.** For an ability that is
 * `TableStackObject.sourceId` — upstream's `sourceCard.getId()`, the permanent it was activated from,
 * carried over the wire for exactly this — and an id settles it.
 *
 * **A spell has no such id, and that is upstream's shape rather than a gap in the bridge.** A stack
 * spell's view is built from the `Spell` itself, and `Spell.getId()` is `ability.getId()` — a fresh
 * UUID per ability instance. The card's own id lives on `Spell.getSourceId()`, which no `CardView`
 * field exposes. So the two ends of a cast cannot be joined by id at all, and the hand anchors under
 * its card's **name** instead: see [handAnchorId].
 *
 * **Where it flies from and to is measured, never derived.** Both ends come out of [BoardAnchors],
 * which every card on this board already reports itself to. A hand card that has just been cast is
 * *gone* by the time the snapshot naming it on the stack arrives — the anchors keep its last box
 * anyway, and that last box is exactly where the player saw it.
 *
 * **The real stack card is underneath the whole time.** Nothing here delays or replaces it: the flight
 * is an overlay that lands precisely on it and then stops existing. So a dropped frame, a missing
 * anchor or an animation that never runs costs the player nothing but the movement — the board is
 * correct before it starts and correct after it ends.
 */

/**
 * One card in flight: what it looks like, where it came from, and where it is going.
 *
 * A face and a printing rather than a stack entry, because a card travels to more places than the
 * stack — see [ZoneFlights] — and the drawing of it does not care where it is headed.
 */
internal data class CardFlight(
    val id: String,
    val state: BoardCardState,
    val art: CardArtRequest?,
    val from: Rect,
    val to: Rect,
    /**
     * How long the card is held where it started, lifted, before it travels. A card revealed out of a hand
     * is shown there first, so a player sees *which* card it was before it goes. `0` sets off at once.
     */
    val holdMillis: Int = 0,
)

/**
 * What the stack's arrivals mean for this frame.
 *
 * @property flights the cards to draw travelling.
 * @property arriving every object whose arrival is still being played out — the ones in [flights],
 *   and the ones waiting for their destination to be measured. **The stack draws these in place but
 *   invisible.** A flight lands on a card that is already drawn underneath it, so without this the
 *   card appears at its destination *first* and the flight that follows reads as a second copy of it
 *   chasing the one that already arrived. Laid out either way, because the flight needs the box.
 */
internal data class StackFlights(
    val flights: List<CardFlight> = emptyList(),
    val arriving: Set<String> = emptySet(),
)

/**
 * The flights to draw for this snapshot's stack.
 *
 * Kept across recompositions so a flight survives the snapshots that arrive while it is running, and
 * so an object already on the stack is never flown twice — an arrival is *new to the stack*, which is
 * a fact about the previous stack rather than about the current one.
 *
 * @param visible whether the stack is being drawn. With it hidden — *Show battlefield* — an arrival
 *   has nowhere to land: [BoardAnchors] does not prune, so the box for an undrawn object is wherever
 *   it was last drawn, and a card would be flown to a place with nothing in it. The arrivals are still
 *   *recorded*, so a spell that landed while the board was clear does not fly in a second time when
 *   the stack comes back.
 */
@Composable
internal fun rememberCardFlights(
    stack: List<TableStackObject>,
    anchors: BoardAnchors,
    visible: Boolean = true,
): StackFlights {
    var known by remember { mutableStateOf(emptySet<String>()) }
    var arriving by remember { mutableStateOf(emptyList<TableStackObject>()) }
    var flights by remember { mutableStateOf(emptyList<CardFlight>()) }

    val onStack = stack.map { it.id }.toSet()
    if (onStack != known) {
        val arrived = if (visible) stack.filter { it.id !in known } else emptyList()
        known = onStack
        // A flight — or an arrival still waiting for one — whose object has already left the stack is
        // dropped: a spell that resolved before its own animation finished has nowhere left to land.
        flights = flights.filter { it.id in onStack }
        arriving = arriving.filter { it.id in onStack } + arrived
    }

    // **An arrival waits for its own destination to be measured.** This is where flights failed
    // outright: `to` is where the card is going, and on the composition that first sees an object on
    // the stack that card has not been laid out yet — [BoardAnchors] is written from
    // `onGloballyPositioned`, which runs after. So every arrival resolved to a null destination and
    // was thrown away, while `known` had already absorbed its id, and no card ever flew. The test
    // could not see it: it placed both anchors before changing the stack, which is the one order a
    // real board never produces.
    //
    // Holding it costs nothing and needs no effect — the anchors are snapshot state, so the box being
    // written *is* the recomposition that resolves this.
    if (arriving.isNotEmpty()) {
        val stillArriving = mutableListOf<TableStackObject>()
        val started = mutableListOf<CardFlight>()
        arriving.forEach { entry ->
            val to = anchors.boxOf(entry.id)
            if (to == null) {
                stillArriving += entry
                return@forEach
            }
            // **The source is asked first, because it is the one answer that is an id.** An ability
            // comes out of the permanent that produced it and the server says which — so that is
            // where it flies from, and nothing else gets a say.
            //
            // The hand is the fallback, and it is a *name* — see [handAnchorId], which is where the
            // id a spell does not keep is explained. A name is weaker than an id in exactly the way
            // that bit: an ability is named after its source card, so an activated Liliana of the
            // Veil matched the box the Liliana card had sat in back when it was in hand, and its +1
            // flew out of the hand instead of out of the planeswalker on the board.
            //
            // Neither: the board never measured an origin — an opponent's card, out of a hand this
            // client cannot see — and there is no flight to draw. That is settled once the
            // destination is known, so it stops waiting either way.
            val from =
                entry.sourceId?.let(anchors::boxOf)
                    ?: anchors.boxOf(handAnchorId(entry.state.card.name))
            if (from != null && from != to) {
                started += CardFlight(id = entry.id, state = entry.state, art = entry.art, from = from, to = to)
            }
        }
        if (started.isNotEmpty() || stillArriving.size != arriving.size) {
            flights = flights + started
            arriving = stillArriving
        }
    }
    return StackFlights(
        flights = flights,
        arriving = (flights.map { it.id } + arriving.map { it.id }).toSet(),
    )
}

/**
 * The stack as the board draws it: the server's, and any card that has just left it before it has been
 * on screen for [STACK_DWELL_MILLIS].
 *
 * **A spell that resolves at once was never seen.** Out of Full Control the server passes for the caster,
 * and an opponent with nothing to say passes too, so a spell can be cast and resolved between two snapshots
 * the board draws — its flight dropped for having nowhere left to land, and the stack never showing it.
 * Pete: *"at least 500ms so it can be seen animating."* §7.3 allows exactly this: *"the board's
 * presentation may trail the server, and that is intended."*
 *
 * **Only the stack region trails.** The arrows and the card detail still read the server's stack, so a
 * card that has resolved is drawn, but not pointed from or acted on.
 */
@Composable
internal fun rememberPresentedStack(stack: List<TableStackObject>): List<TableStackObject> {
    val presented = remember { PresentedStack() }
    val shown = presented.take(stack)
    shown.forEach { entry ->
        key(entry.id) {
            LaunchedEffect(entry.id) {
                delay(STACK_DWELL_MILLIS)
                presented.dwelt(entry.id)
            }
        }
    }
    return shown
}

/** What the stack region is drawing, and which of it has been on screen long enough to go. */
@Stable
private class PresentedStack {
    private var shown by mutableStateOf(emptyList<TableStackObject>())
    private var dwelt by mutableStateOf(emptySet<String>())

    fun take(stack: List<TableStackObject>): List<TableStackObject> {
        val current = stack.associateBy { it.id }
        val before = shown.map { it.id }.toSet()
        // A new object goes on top, which is where anything put on the stack goes. Everything already shown
        // keeps its place with the server's latest for it, and one that has left stays until it has dwelt.
        val next =
            stack.filter { it.id !in before } +
                shown.mapNotNull { old -> current[old.id] ?: old.takeIf { it.id !in dwelt } }
        if (next != shown) {
            shown = next
            val ids = next.map { it.id }.toSet()
            dwelt = dwelt.filterTo(mutableSetOf()) { it in ids }
        }
        return next
    }

    fun dwelt(id: String) {
        dwelt = dwelt + id
    }
}

/**
 * The overlay that draws them.
 *
 * Each flight grows from its origin's size to the stack card's, along a straight line. Straight
 * because the two points are already meaningful — a hand and the centre line, a permanent and the
 * centre line — and a curve would be decoration on top of a fact.
 *
 * @param onLanded called with a flight's id once it has arrived, so the caller can forget it.
 */
@Composable
internal fun CardFlightOverlay(
    flights: List<CardFlight>,
    palette: CounterPalette,
    artFor: TableArtResolver?,
    onLanded: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize().testTag(CardFlightTestTags.OVERLAY)) {
        flights.forEach { flight ->
            key(flight.id) { FlyingCard(flight = flight, palette = palette, artFor = artFor, onLanded = onLanded) }
        }
    }
}

@Composable
private fun FlyingCard(
    flight: CardFlight,
    palette: CounterPalette,
    artFor: TableArtResolver?,
    onLanded: (String) -> Unit,
) {
    // **Run from an effect, not from a target flipped during composition.** The first cut asked
    // `animateFloatAsState` for 0 and then wrote `started = true` in the composable's own body,
    // relying on that write to invalidate the composition it was already in and come back around with
    // a target of 1. It never came back around: the card sat on its origin at progress zero for the
    // whole life of the object — and because the stack holds an arriving card's place without drawing
    // it, that left a hole in the panel where the card should be rather than a missing animation.
    //
    // An `Animatable` driven by a `LaunchedEffect` starts because it was *told* to, which is the
    // pattern the tapping animation next door already runs on. Landing is the effect's last line, so
    // it cannot be missed either.
    val progress = remember(flight.id) { Animatable(0f) }
    // **A revealed card is shown before it goes.** Held on its origin, lifted, for the flight's hold; then
    // it travels like any other.
    var holding by remember(flight.id) { mutableStateOf(flight.holdMillis > 0) }
    LaunchedEffect(flight.id) {
        if (flight.holdMillis > 0) {
            delay(flight.holdMillis.toLong())
            holding = false
        }
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = FLIGHT_MILLIS, easing = LinearOutSlowInEasing),
        )
        onLanded(flight.id)
    }

    val density = LocalDensity.current
    val width = with(density) { flight.lerpWidth(progress.value).toDp() }

    BoardCard(
        state = flight.state,
        width = width,
        art = artFor?.invoke(flight.art, flight.state.card),
        counterPalette = palette,
        modifier =
            Modifier
                .graphicsLayer {
                    translationX = flight.lerpLeft(progress.value)
                    translationY = flight.lerpTop(progress.value)
                    scaleX = if (holding) REVEAL_SCALE else 1f
                    scaleY = if (holding) REVEAL_SCALE else 1f
                }.testTag(CardFlightTestTags.card(flight.id)),
    )
}

private fun CardFlight.lerpWidth(t: Float): Float = from.width + (to.width - from.width) * t

private fun CardFlight.lerpLeft(t: Float): Float = from.left + (to.left - from.left) * t

private fun CardFlight.lerpTop(t: Float): Float = from.top + (to.top - from.top) * t

/** Test tags for the flight overlay. */
object CardFlightTestTags {
    const val OVERLAY: String = "card-flight-overlay"

    fun card(id: String): String = "card-flight-$id"
}

/**
 * How long a card takes to reach the stack.
 *
 * Long enough to be followed by eye across the board, short enough that a player casting three spells
 * in a row is never waiting on the last one. Decelerating, so it arrives rather than stopping.
 */
private const val FLIGHT_MILLIS = 320

/**
 * The least time a card is on the stack, counted from when it sets off towards it: its flight, then at
 * least half a second sitting there, however quickly the server resolves it.
 */
private const val STACK_DWELL_MILLIS = FLIGHT_MILLIS + 500L

/** How much a card held on its origin is lifted — enough to read as pulled out of the hand. */
private const val REVEAL_SCALE = 1.15f

/**
 * The anchor a card in hand reports itself under: **its name, not its object id.**
 *
 * **A spell on the stack does not carry the id of the card it was cast from, and this is the whole
 * reason no spell ever flew.** Upstream builds a stack spell's view from the `Spell` itself —
 * `new CardView(spell, …)` → `super(sourceCard.getId(), …)` — and `Spell.getId()` returns
 * `ability.getId()`, a fresh UUID per ability instance (`AbilityImpl`: `this.id = UUID.randomUUID()`).
 * The card's own id is on `Spell.getSourceId()`, which no `CardView` field exposes and so nothing the
 * bridge can translate. Matching the two ends by id cannot work and never could.
 *
 * What both ends *do* carry is the card's name, so that is the key. Two copies of one card in hand
 * resolve to whichever laid out last — which is a real card of that name, in the hand, and the only
 * thing the flight claims. It never says anything about the game: it is where a card was.
 *
 * **Its own namespace**, because these keys share a map with the server's object ids and a name is
 * not one. And keyed this way, the anchors never being pruned stops being an accident and becomes the
 * mechanism: when the last copy leaves the hand, its box stays under its name, which is exactly the
 * place the card flies out of.
 */
internal fun handAnchorId(cardName: String): String = "hand:$cardName"
