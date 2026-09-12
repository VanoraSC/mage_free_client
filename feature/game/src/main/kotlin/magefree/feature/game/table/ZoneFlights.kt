package magefree.feature.game.table

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import magefree.designsystem.card.BoardCardState
import magefree.designsystem.card.CardDisplay
import magefree.network.game.GameCard
import magefree.network.game.GameState

/*
 * Cards changing zone, drawn travelling — the lands a player plays, the cards they discard, and
 * everything that leaves the battlefield.
 *
 * **The same flight the stack draws**, from the same measured anchors: a card leaves the box it was last
 * drawn in, which the anchors keep precisely because nothing prunes them, and it lands on a box the board
 * has measured. See [CardFlights] for why neither end is ever derived.
 *
 * **The destination waits for the card.** A card appearing where it went *before* its flight arrives
 * reads as a second copy chasing the first, so the place it is going is held — a land's own stack drawn
 * invisible, a hand card drawn invisible, a graveyard still showing the card that was on top — until the
 * flight lands. Never for long: a destination that is never measured gives the card back after
 * [DESTINATION_WAIT_MILLIS] rather than hiding it for good.
 */

/**
 * The moves one snapshot produced, numbered so the same moves are never flown twice.
 *
 * Public because the board takes it; everything that turns it into flights stays inside this package.
 */
data class ZoneMoveBatch(
    val generation: Int = 0,
    val moves: List<ZoneMove> = emptyList(),
)

/**
 * The zone moves of each snapshot [snapshot] becomes, as it arrives.
 *
 * Kept by snapshot identity rather than by value, so a recomposition over the same snapshot reads the
 * same batch and a new snapshot is compared against the one before it.
 */
@Composable
internal fun rememberZoneMoves(snapshot: GameState?): ZoneMoveBatch {
    val memory = remember { MoveMemory() }
    if (snapshot != null && snapshot !== memory.last) {
        val moves = zoneMoves(memory.last, snapshot)
        memory.last = snapshot
        if (moves.isNotEmpty()) memory.batch = ZoneMoveBatch(generation = memory.batch.generation + 1, moves = moves)
    }
    return memory.batch
}

private class MoveMemory {
    var last: GameState? = null
    var batch: ZoneMoveBatch = ZoneMoveBatch()
}

/** One zone flight, and the card whose destination it is holding. */
internal data class ZoneFlight(
    val flight: CardFlight,
    val cardId: String,
)

private data class WaitingMove(
    val flightId: String,
    val move: ZoneMove,
    val generation: Int,
)

/**
 * The zone flights being drawn, and the cards whose destinations are being held for them.
 *
 * Its state is written while composing, exactly as the stack's flights are: the anchors are snapshot
 * state, so the box a waiting flight needs being written *is* the recomposition that starts it.
 */
@Stable
internal class ZoneFlights {
    private var flying by mutableStateOf(emptyList<ZoneFlight>())
    private var waiting by mutableStateOf(emptyList<WaitingMove>())
    private var seenGeneration: Int? = null

    /** The flights to draw. */
    val flights: List<CardFlight> get() = flying.map { it.flight }

    /** Every card whose destination is held — in flight, or waiting for somewhere to land. */
    val hidden: Set<String> get() = (flying.map { it.cardId } + waiting.map { it.move.cardId }).toSet()

    /** Whether [flightId] is one of these, rather than one of the stack's. */
    fun owns(flightId: String): Boolean = flying.any { it.flight.id == flightId }

    /** A flight has arrived: stop drawing it, and give its card back to its destination. */
    fun landed(flightId: String) {
        flying = flying.filterNot { it.flight.id == flightId }
    }

    internal fun expire(generation: Int) {
        waiting = waiting.filterNot { it.generation == generation }
    }

    internal fun take(
        batch: ZoneMoveBatch,
        anchors: BoardAnchors,
    ) {
        // **The batch a board opens on is not flown.** It describes a move that happened before this
        // board was drawn, from a place the board never measured.
        val seen = seenGeneration
        if (seen == null) {
            seenGeneration = batch.generation
            return
        }
        if (batch.generation != seen) {
            seenGeneration = batch.generation
            batch.moves.forEach { move -> if (move.freshDestination) move.to.lastOrNull()?.let(anchors::forget) }
            waiting = waiting + batch.moves.map { WaitingMove("zone:${batch.generation}:${it.cardId}", it, batch.generation) }
        }
        if (waiting.isEmpty()) return

        val still = mutableListOf<WaitingMove>()
        val started = mutableListOf<ZoneFlight>()
        waiting.forEach { waiter ->
            // An origin the board never measured — a hand it does not draw — has nothing to fly from,
            // and the honest failure is no movement: the card is already where it went.
            val from = anchors.boxOf(waiter.move.from) ?: return@forEach
            val to = waiter.move.to.firstNotNullOfOrNull(anchors::boxOf)
            if (to == null) {
                still += waiter
                return@forEach
            }
            if (to != from) {
                started +=
                    ZoneFlight(
                        flight =
                            CardFlight(
                                id = waiter.flightId,
                                state = waiter.move.card.flightFace(),
                                art = artRequestOf(waiter.move.card),
                                from = from,
                                to = to,
                            ),
                        cardId = waiter.move.cardId,
                    )
            }
        }
        if (started.isNotEmpty() || still.size != waiting.size) {
            flying = flying + started
            waiting = still
        }
    }
}

/** The zone flights for the moves [batch] carries, kept across the snapshots they are drawn over. */
@Composable
internal fun rememberZoneFlights(
    batch: ZoneMoveBatch,
    anchors: BoardAnchors,
): ZoneFlights {
    val flights = remember { ZoneFlights() }
    flights.take(batch, anchors)
    // **A destination that is never measured must not hide a card for good.**
    LaunchedEffect(batch.generation) {
        delay(DESTINATION_WAIT_MILLIS)
        flights.expire(batch.generation)
    }
    return flights
}

/** What a travelling card looks like: its face as it was, with no state that belongs to a zone. */
private fun GameCard.flightFace(): BoardCardState =
    BoardCardState(
        card = CardDisplay(name = if (isFaceDown) "" else name, manaCost = manaCost, typeLine = typeLine),
        power = shownPower,
        toughness = shownToughness,
    )

/**
 * How long a move may wait for its destination to be measured before the card is given back.
 *
 * A destination is measured on the frame after it is composed, so any real wait is a frame or two. This
 * is the ceiling on the case that never resolves.
 */
private const val DESTINATION_WAIT_MILLIS = 1_000L
