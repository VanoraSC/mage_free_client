package magefree.feature.game.table

import magefree.network.game.CardType
import magefree.network.game.GameCard
import magefree.network.game.GameState
import magefree.network.game.MageObjectType

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
    /** From a hand onto the battlefield — a land played, most of the time. */
    PlayedFromHand,

    /** From a hand into a graveyard. */
    Discarded,

    /** From the battlefield into a graveyard. */
    ToGraveyard,

    /** From the battlefield into a hand. */
    ToHand,

    /** From the battlefield anywhere else — exile, a library, a zone the board draws only as a count. */
    ToOther,

    /** From the stack onto the battlefield — a permanent spell that resolved. */
    SpellToBattlefield,

    /** From the stack into a graveyard — a spell that resolved, or was countered. */
    SpellToGraveyard,

    /** From the stack into exile — a spell exiled as it resolved, or instead of resolving. */
    SpellToExile,
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
 * @property leavesStack the stack object this move waits for the board to stop drawing, or `null`. A
 *   spell's card reaches its graveyard the moment the server resolves it, but the stack goes on showing
 *   the spell for a while (`rememberPresentedStack`) — and a card must not fly out of a stack that is
 *   still showing it.
 */
data class ZoneMove(
    val cardId: String,
    val kind: ZoneMoveKind,
    val card: GameCard,
    val from: String,
    val to: List<String>,
    val freshDestination: Boolean = false,
    val leavesStack: String? = null,
)

/**
 * Every card that changed zone between [previous] and [current], in a stable order: out of the viewer's
 * hand, off the battlefield, off the stack, then out of an opponent's hand.
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

    // Every card any visible zone held before, so a card arriving can be told from one already there.
    val seenBefore =
        buildSet {
            previous.hand.forEach { add(it.id) }
            previous.players.forEach { seat ->
                seat.battlefield.forEach { add(it.card.id) }
                seat.graveyard.forEach { add(it.id) }
                seat.exile.forEach { add(it.id) }
            }
        }

    // **Off the stack.** A spell on the stack does not carry its card's id — `Spell.getId()` is its
    // ability's — so what it became is found by **name**, the same join the hand makes for a cast: a spell
    // gone from the stack, and something of that name new to the battlefield, a graveyard or an exile pile
    // in the same snapshot. Only spells (upstream's `MageObjectType.SPELL`): an ability leaving the stack
    // goes nowhere. Each arrival is claimed once, so it is neither flown to twice nor also read as a
    // discard.
    val claimed = mutableSetOf<String>()
    val onStackNow = current.stack.map { it.id }.toSet()
    previous.stack
        .filter { it.objectType == MageObjectType.Spell && it.id !in onStackNow }
        .forEach { spell ->
            val move = spellArrival(spell, current, seenBefore, claimed) ?: return@forEach
            claimed += move.cardId
            moves += move
        }

    // **Out of an opponent's hand.** The hand has no ids, so what left it is read from its count falling
    // while something arrived that no visible zone held before: a land on their battlefield — played, which
    // never touches the stack — or a card in their graveyard, a discard. A land put onto the battlefield
    // from a library, and a card milled from one, arrive the same way with the hand standing still, which is
    // why the count is the condition. Lands take the count first, because a land played is the ordinary
    // turn.
    current.players.filterNot { it.isViewer }.forEach { seat ->
        val before = previous.players.firstOrNull { it.playerId == seat.playerId } ?: return@forEach
        var shrank = before.handCount - seat.handCount
        if (shrank <= 0) return@forEach
        seat.battlefield
            .map { it.card }
            .filter { CardType.Land in it.cardTypes && !it.isToken && it.id !in seenBefore }
            .take(shrank)
            .forEach { land ->
                shrank -= 1
                moves +=
                    ZoneMove(
                        cardId = land.id,
                        kind = ZoneMoveKind.PlayedFromHand,
                        card = land,
                        from = opponentHandAnchorId(seat.playerId),
                        // Onto a stack of its name if they have one, as the viewer's own lands do.
                        to = stackMatesOf(current, land.id) + land.id,
                        freshDestination = true,
                    )
            }
        seat.graveyard
            .filter { it.id !in seenBefore && it.id !in claimed }
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

/**
 * Where [spell], gone from the stack, went: the battlefield first, then a graveyard, then exile — or
 * `null` when nothing of its name arrived anywhere this client can see (a copy that ceased to exist, a
 * spell returned to a hand).
 *
 * The battlefield first because a permanent spell that resolves is the ordinary case, and a card of the
 * same name reaching a graveyard at the same moment is not this spell unless nothing arrived on the table.
 */
private fun spellArrival(
    spell: GameCard,
    current: GameState,
    seenBefore: Set<String>,
    claimed: Set<String>,
): ZoneMove? {
    fun move(
        kind: ZoneMoveKind,
        arrived: GameCard,
        to: List<String>,
        fresh: Boolean = false,
    ) = ZoneMove(
        cardId = arrived.id,
        kind = kind,
        // The spell as it was on the stack is what leaves it.
        card = spell,
        from = spell.id,
        to = to,
        freshDestination = fresh,
        leavesStack = spell.id,
    )

    // A copy of a permanent spell resolves as a token, so tokens are looked at too.
    current.players
        .firstNotNullOfOrNull { seat -> seat.battlefield.map { it.card }.arrivalNamed(spell.name, seenBefore, claimed) }
        ?.let { return move(ZoneMoveKind.SpellToBattlefield, it, arrivalAnchorsOf(current, it.id), fresh = true) }
    current.players.forEach { seat ->
        seat.graveyard
            .arrivalNamed(spell.name, seenBefore, claimed)
            ?.let { return move(ZoneMoveKind.SpellToGraveyard, it, listOf(graveyardAnchorId(seat.playerId))) }
    }
    current.players.forEach { seat ->
        seat.exile
            .arrivalNamed(spell.name, seenBefore, claimed)
            ?.let { return move(ZoneMoveKind.SpellToExile, it, listOf(zoneCountsAnchorId(seat.playerId))) }
    }
    return null
}

/** The newest card named [name] in this pile that no visible zone held before and no move has claimed. */
private fun List<GameCard>.arrivalNamed(
    name: String,
    seenBefore: Set<String>,
    claimed: Set<String>,
): GameCard? = lastOrNull { it.name == name && it.id !in seenBefore && it.id !in claimed }

/**
 * Where a permanent new to the battlefield is drawn, best first — see [ZoneMove.to].
 *
 * **An attachment is drawn on its host,** with no box of its own, so it lands on the host. **A copy
 * joining a pile** — a land onto a stack of its name, a token onto a pile of identical tokens — lands on
 * the pile, whose front card does not move when a copy joins and so never reports the newcomer's box.
 * Anything else answers under its own id once it is laid out.
 */
private fun arrivalAnchorsOf(
    state: GameState,
    cardId: String,
): List<String> {
    val permanent = state.players.flatMap { it.battlefield }.firstOrNull { it.card.id == cardId }
    val host = permanent?.attachedTo?.takeIf { permanent.isAttachedToPermanent }
    if (host != null) return listOf(host, cardId)
    return stackMatesOf(state, cardId) + cardId
}

/** The other members of the land stack or token pile [cardId] is now part of, on whichever side it is. */
private fun stackMatesOf(
    state: GameState,
    cardId: String,
): List<String> {
    val model = battlefieldModel(state)
    val sides = model.opponents + listOfNotNull(model.viewer)
    val landStacks = sides.flatMap { it.landStacks() }.map { stack -> (stack.untapped + stack.tapped).map { it.id } }
    val tokenPiles =
        sides.flatMap { side ->
            side.permanents
                .map { it.role }
                .distinct()
                .filter { it != PermanentRole.Land }
                .flatMap { side.entriesIn(it) }
                .filterIsInstance<RowEntry.Pile>()
                .map { pile -> pile.members.map { it.id } }
        }
    return (landStacks + tokenPiles)
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
