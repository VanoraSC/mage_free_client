package magefree.feature.game.table

import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
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
import magefree.designsystem.card.BoardCard
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
 * **Where it flies from is the server's, not a guess.** A spell on the stack has the same object id it
 * had in hand, so the card that left is the card that arrived. An ability has no such continuity, and
 * the origin is `TableStackObject.sourceId` — upstream's `sourceCard.getId()`, carried over the wire
 * for this.
 *
 * **A spell's two ends therefore share an id, and the hand anchors under [handAnchorId] because of
 * it.** Anchored under the bare id, the stack's own placement overwrites the hand's the moment it is
 * drawn, the origin and the destination become the same box, and the flight silently never happens.
 * Found by the test below rather than on a board, which is the only reason it is not shipping.
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

/** One card in flight: what it is, where it came from, and where it is going. */
internal data class CardFlight(
    val id: String,
    val entry: TableStackObject,
    val from: Rect,
    val to: Rect,
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
): List<CardFlight> {
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
            // A spell was in hand under its own id; an ability was never anywhere, and comes out of
            // the permanent that produced it. The hand is tried first, because a card cast from hand
            // is the case a player sees most. Neither: the board never measured an origin — an
            // opponent's card out of a hand this client cannot see — and there is no flight to draw.
            // That is settled once the destination is known, so it stops waiting either way.
            val from = anchors.boxOf(handAnchorId(entry.id)) ?: entry.sourceId?.let(anchors::boxOf)
            if (from != null && from != to) started += CardFlight(id = entry.id, entry = entry, from = from, to = to)
        }
        if (started.isNotEmpty() || stillArriving.size != arriving.size) {
            flights = flights + started
            arriving = stillArriving
        }
    }
    return flights
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
    // Starts at 0 and is asked for 1 on the first composition, so the flight begins the moment the
    // card is on screen rather than needing an event to start it.
    var started by remember { mutableStateOf(false) }
    val progress by animateFloatAsState(
        targetValue = if (started) 1f else 0f,
        animationSpec = tween(durationMillis = FLIGHT_MILLIS, easing = LinearOutSlowInEasing),
        finishedListener = { if (it == 1f) onLanded(flight.id) },
        label = "cardFlight",
    )
    started = true

    val density = LocalDensity.current
    val width = with(density) { flight.lerpWidth(progress).toDp() }

    BoardCard(
        state = flight.entry.state,
        width = width,
        art = artFor?.invoke(flight.entry.art, flight.entry.state.card),
        counterPalette = palette,
        modifier =
            Modifier
                .graphicsLayer {
                    translationX = flight.lerpLeft(progress)
                    translationY = flight.lerpTop(progress)
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
 * The anchor id a card in hand reports itself under.
 *
 * **Its own namespace, because a spell's two ends share an object id.** A card cast from hand keeps
 * that id onto the stack, so anchoring both under it means the stack's placement overwrites the
 * hand's and the flight has nowhere to start from — it would simply never animate, silently.
 */
internal fun handAnchorId(cardId: String): String = "hand:$cardId"
