package magefree.feature.game.table

import magefree.network.game.GameState

/*
 * What you have been shown of an opponent's hand.
 *
 * **The server will not tell you twice.** `GameState.revealed` is cleared by `GameImpl` in two places
 * — inside `fireUpdatePlayersEvent` and inside the priority `select` — so a reveal exists in exactly
 * the snapshot that carries it and the next update wipes it. There is no history and no verb that asks
 * again. A player who cast Inquisition of Kozilek and looked away has lost the information, and the
 * only thing that can keep it is the client.
 *
 * **And nothing on the wire says whose cards were revealed.** `RevealedView` carries the *effect's*
 * name and the cards, and `CardView` exposes exactly `getId()` and `getParentId()` — no owner, no
 * controller, no player. The bridge receives `CardView` and cannot carry what it was never given, so
 * this is **inference**, and it is worth being exact about which inference:
 *
 * > A revealed card the board cannot see in any visible zone — no battlefield, no graveyard, no exile,
 * > not on the stack, not in the viewer's own hand — is in a hand or a library.
 *
 * With **one** opponent that is enough: it is in their hand, or it is not in a hand at all, and the
 * second case is corrected on the next snapshot by [KnownHand.hidden] never going below zero and by
 * the card leaving as soon as it is seen anywhere. With more than one opponent it is not enough, and
 * nothing on the wire makes it so — so [knownHandFor] answers only for a single opponent, and a
 * multiplayer game gets counts exactly as it did before.
 *
 * **The count is always the server's.** Everything drawn face-up is something the server showed this
 * client; everything else is a number the server sent. A wrong memory is reconciled rather than
 * compounded, because the memory only ever *subtracts* from what the count already says.
 */

/**
 * One opponent's hand as far as this client knows it.
 *
 * @property cards what has been revealed and not seen since — face-up, in the order they were shown.
 * @property hidden how many cards are in that hand that this client has never been shown. Never
 *   negative: the server's count wins over the memory, always.
 */
data class KnownHand(
    val cards: List<TableCard> = emptyList(),
    val hidden: Int = 0,
) {
    /** Every card in the hand, seen or not. The server's own `handCount`. */
    val count: Int get() = cards.size + hidden
}

/**
 * Everything this client has been shown, folded across snapshots.
 *
 * Held as ids and cards rather than as a rendered pile so it survives the projections being rebuilt,
 * and so the *fold* — which is the part with rules in it — is a pure function a test can drive.
 */
data class SeenCards(
    val cards: List<TableCard> = emptyList(),
) {
    val ids: Set<String> get() = cards.mapTo(mutableSetOf()) { it.id }
}

/**
 * One snapshot folded in: what it revealed, minus anything now visible somewhere else.
 *
 * **A card leaves the moment it is seen anywhere**, which is what keeps this honest without any rule
 * about *why* it left. Played, discarded, exiled, put on the battlefield, revealed off a library
 * instead — every one of those makes the card visible in a zone the board draws, and that is the
 * signal. Nothing here reasons about the game.
 */
fun SeenCards.fold(state: GameState): SeenCards {
    val visible = state.visibleCardIds()
    val revealed = state.revealed.flatMap { zone -> zone.cards }
    val known = LinkedHashMap<String, TableCard>()
    // Order matters and it is *first seen first*: a hand a player has been shown twice should not
    // reshuffle itself under them between snapshots.
    cards.forEach { card -> known[card.id] = card }
    revealed.forEach { card -> known.getOrPut(card.id) { card.asTableCard(state, TableCardZone.Revealed) } }
    return SeenCards(cards = known.values.filterNot { it.id in visible })
}

/**
 * This client's picture of [playerId]'s hand, or `null` when it has no business drawing one.
 *
 * Null for the viewer's own seat — their hand is not a guess, it is `GameState.hand` — and null when
 * there is more than one opponent, because the inference this rests on does not survive that. See the
 * file header.
 */
fun SeenCards.knownHandFor(
    state: GameState,
    playerId: String,
): KnownHand? {
    val seat = state.players.firstOrNull { it.playerId == playerId } ?: return null
    if (seat.isViewer) return null
    if (state.players.count { !it.isViewer } != 1) return null

    // The server's count is the ceiling. A memory longer than the hand means a card left in a way the
    // board could not see, and the count is the thing to believe.
    val shown = cards.take(seat.handCount)
    return KnownHand(cards = shown, hidden = (seat.handCount - shown.size).coerceAtLeast(0))
}

/**
 * Every card id the board can currently see somewhere.
 *
 * The viewer's own hand is included: a card revealed off *their* hand by an opponent's spell is not
 * something to remember about the opponent.
 */
private fun GameState.visibleCardIds(): Set<String> =
    buildSet {
        hand.forEach { add(it.id) }
        stack.forEach { add(it.id) }
        players.forEach { player ->
            player.battlefield.forEach { add(it.card.id) }
            player.graveyard.forEach { add(it.id) }
            player.exile.forEach { add(it.id) }
        }
    }
