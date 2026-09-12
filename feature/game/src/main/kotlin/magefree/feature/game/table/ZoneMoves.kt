package magefree.feature.game.table

import magefree.network.game.GameCard
import magefree.network.game.GameState

/*
 * Cards changing zone, read off two snapshots.
 *
 * **A card keeps its id when it moves.** Upstream moves a `Card` between zones without re-identifying
 * it — the zone-change counter changes, the UUID does not — so an id seen in one zone in one snapshot and
 * in another zone in the next is the same card having moved. That is what lets a move be read off two
 * whole snapshots with no event stream at all. A token that leaves the battlefield ceases to exist, so it
 * has nowhere to go.
 *
 * **Only what is visible, and counts for the rest.** The battlefield, every graveyard, every exile pile
 * and the viewer's own hand carry ids. An opponent's hand and every library carry only a count, so a card
 * going into one of those is read from the count moving at the same time — and a card that went nowhere
 * this client can see and into no hand whose count rose is *somewhere else*, which is the count panel.
 *
 * **Nothing here is drawn.** A move names anchors, not positions; where those are is `BoardAnchors`'
 * answer, and whether a flight can be drawn at all is decided where it is drawn — see [ZoneFlights].
 */

/** What kind of move a [ZoneMove] is. */
enum class ZoneMoveKind {
    /** From the viewer's hand onto the battlefield — a land played, most of the time. */
    PlayedFromHand,

    /** From a hand into a graveyard. */
    Discarded,

    /** From the battlefield into a graveyard. */
    ToGraveyard,

    /** From the battlefield into a hand. */
    ToHand,

    /** From the battlefield anywhere else — exile, a library, a zone the board draws only as a count. */
    ToOther,
}

/**
 * One card that moved between two snapshots.
 *
 * @property cardId the card's own id, the same on both sides of the move.
 * @property card the card as it looked where it was, which is what travels.
 * @property from the anchor it flies out of.
 * @property to the anchors it may land on, best first; the first one the board has measured wins.
 * @property freshDestination whether the last of [to] is a node being drawn for the first time. Whatever
 *   the board last measured under that id belongs to where the card *used* to be, so it is forgotten and
 *   the flight waits for the new box.
 */
data class ZoneMove(
    val cardId: String,
    val kind: ZoneMoveKind,
    val card: GameCard,
    val from: String,
    val to: List<String>,
    val freshDestination: Boolean = false,
)

/**
 * Every card that changed zone between [previous] and [current], in a stable order: out of the viewer's
 * hand, off the battlefield, then out of an opponent's hand.
 *
 * **No history moves nothing.** The first snapshot a board sees, and a snapshot of a different game,
 * are not a sequence — §7.3: *"a resync is not a sequence"*.
 */
fun zoneMoves(
    previous: GameState?,
    current: GameState,
): List<ZoneMove> {
    if (previous == null || !previous.hasSnapshot || previous.gameId != current.gameId) return emptyList()

    val onBattlefield =
        current.players
            .flatMap { it.battlefield }
            .map { it.card.id }
            .toSet()
    val inHand = current.hand.map { it.id }.toSet()
    val graveyardOf = current.players.flatMap { seat -> seat.graveyard.map { it.id to seat.playerId } }.toMap()
    val exileOf = current.players.flatMap { seat -> seat.exile.map { it.id to seat.playerId } }.toMap()
    val moves = mutableListOf<ZoneMove>()

    // **Out of the viewer's hand.** A card cast from it is not here: it goes to the stack, which does not
    // carry the card's id, and the stack draws that flight itself.
    previous.hand.filter { it.id !in inHand }.forEach { card ->
        when (card.id) {
            in onBattlefield ->
                moves +=
                    ZoneMove(
                        cardId = card.id,
                        kind = ZoneMoveKind.PlayedFromHand,
                        card = card,
                        from = handCardAnchorId(card.id),
                        // A land joining a stack already on the table lands on that stack, whose front
                        // card is where it goes and is already measured; the card's own node answers for a
                        // stack of its own.
                        to = stackMatesOf(current, card.id) + card.id,
                        freshDestination = true,
                    )
            in graveyardOf ->
                moves +=
                    ZoneMove(
                        cardId = card.id,
                        kind = ZoneMoveKind.Discarded,
                        card = card,
                        from = handCardAnchorId(card.id),
                        to = listOf(graveyardAnchorId(graveyardOf.getValue(card.id))),
                    )
        }
    }

    // **Off the battlefield.** How much each opponent's hand grew, so a card that vanished from view can
    // be sent to a hand that took it — one card per card of growth, and no more.
    val handGrowth =
        current.players
            .filterNot { it.isViewer }
            .associate { seat ->
                val before = previous.players.firstOrNull { it.playerId == seat.playerId }?.handCount ?: seat.handCount
                seat.playerId to seat.handCount - before
            }.toMutableMap()
    previous.players.forEach { seat ->
        seat.battlefield
            .map { it.card }
            .filter { it.id !in onBattlefield && !it.isToken }
            .forEach { card ->
                moves +=
                    when (card.id) {
                        in graveyardOf ->
                            ZoneMove(
                                card.id,
                                ZoneMoveKind.ToGraveyard,
                                card,
                                card.id,
                                listOf(graveyardAnchorId(graveyardOf.getValue(card.id))),
                            )
                        in inHand ->
                            ZoneMove(
                                card.id,
                                ZoneMoveKind.ToHand,
                                card,
                                card.id,
                                listOf(handCardAnchorId(card.id)),
                                freshDestination = true,
                            )
                        in exileOf ->
                            ZoneMove(card.id, ZoneMoveKind.ToOther, card, card.id, listOf(zoneCountsAnchorId(exileOf.getValue(card.id))))
                        else -> {
                            // The seat it was on first, because a card returned to a hand goes to its owner's
                            // and that is almost always its controller.
                            val hand = (listOf(seat.playerId) + handGrowth.keys).firstOrNull { (handGrowth[it] ?: 0) > 0 }
                            if (hand != null) {
                                handGrowth[hand] = handGrowth.getValue(hand) - 1
                                // Fresh: the card it becomes is a new last card in that hand, not the one before it.
                                ZoneMove(
                                    card.id,
                                    ZoneMoveKind.ToHand,
                                    card,
                                    card.id,
                                    listOf(opponentHandAnchorId(hand)),
                                    freshDestination = true,
                                )
                            } else {
                                ZoneMove(card.id, ZoneMoveKind.ToOther, card, card.id, listOf(zoneCountsAnchorId(seat.playerId)))
                            }
                        }
                    }
            }
    }

    // **Out of an opponent's hand into their graveyard.** The hand has no ids, so this is a card that
    // arrived in their graveyard having been nowhere this client could see, while their hand shrank by
    // at least as many. A card milled from a library arrives the same way with the hand standing still,
    // which is why the count is the condition.
    val seenBefore =
        buildSet {
            previous.hand.forEach { add(it.id) }
            previous.players.forEach { seat ->
                seat.battlefield.forEach { add(it.card.id) }
                seat.graveyard.forEach { add(it.id) }
                seat.exile.forEach { add(it.id) }
            }
        }
    current.players.filterNot { it.isViewer }.forEach { seat ->
        val before = previous.players.firstOrNull { it.playerId == seat.playerId } ?: return@forEach
        val shrank = before.handCount - seat.handCount
        if (shrank <= 0) return@forEach
        seat.graveyard
            .filter { it.id !in seenBefore }
            .takeLast(shrank)
            .forEach { card ->
                moves +=
                    ZoneMove(
                        cardId = card.id,
                        kind = ZoneMoveKind.Discarded,
                        card = card,
                        from = opponentHandAnchorId(seat.playerId),
                        to = listOf(graveyardAnchorId(seat.playerId)),
                    )
            }
    }

    return moves
}

/** The other lands in the stack [cardId] is now part of, on whichever side it is. */
private fun stackMatesOf(
    state: GameState,
    cardId: String,
): List<String> {
    val model = battlefieldModel(state)
    return (model.opponents + listOfNotNull(model.viewer))
        .flatMap { it.landStacks() }
        .map { stack -> (stack.untapped + stack.tapped).map { it.id } }
        .firstOrNull { cardId in it }
        ?.filter { it != cardId }
        .orEmpty()
}

/**
 * Where a card in the viewer's hand is, by its **id** — beside [handAnchorId], which is by name.
 *
 * Both, because they answer different questions: a spell on the stack keeps no id of the card it came
 * from, so it is found by name; a card that went anywhere else kept its id, so it is found exactly.
 */
internal fun handCardAnchorId(cardId: String): String = "hand-card:$cardId"

/** Where a seat's graveyard is drawn on the rail. */
internal fun graveyardAnchorId(playerId: String): String = "graveyard:$playerId"

/** Where a seat's zone counts are — the panel a press opens everything behind. */
internal fun zoneCountsAnchorId(playerId: String): String = "zone-counts:$playerId"

/**
 * Where a card comes out of, and goes into, an opponent's hand: the last card drawn in it along the top
 * edge. Card-sized on purpose — the whole strip is as wide as the screen, and a card flying out of that
 * was a card the size of a hand shrinking into a graveyard.
 */
internal fun opponentHandAnchorId(playerId: String): String = "opponent-hand:$playerId"
