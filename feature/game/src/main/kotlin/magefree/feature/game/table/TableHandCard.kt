package magefree.feature.game.table

import magefree.cards.art.CardArtRequest
import magefree.cards.art.CardArtSize
import magefree.designsystem.card.BoardCardSignal
import magefree.designsystem.card.CardDisplay
import magefree.network.game.GameState

/*
 * The viewer's hand, as the board draws it.
 *
 * **Nothing here is derived.** `GameState.hand` is the viewer's own cards, in the server's order, and
 * `GameState.playable` is upstream's own list of what this player may act on right now. A client that
 * decided for itself which cards were castable would be answering a rules question — the same one the
 * cast flow refuses to answer, for the same reason: it would be wrong exactly where it mattered, after
 * cost reductions, alternative costs and everything else the server has already accounted for.
 */

/**
 * One card in hand.
 *
 * @property id the server's own object id, which is what an action on the card names and what the
 *   animation host will track identity by when the board is wired to it.
 * @property card what the Tile tier draws.
 * @property art the printing the server named, or `null` for a card it did not name.
 * @property signal what the game is currently saying about this card — [BoardCardSignal.Playable] for
 *   one the server offered, and nothing otherwise. A signal rather than a boolean because it is the
 *   same vocabulary the battlefield uses, and a hand card being castable is the same fact as a
 *   permanent being activatable.
 */
data class TableHandCard(
    val id: String,
    val card: CardDisplay,
    val art: CardArtRequest? = null,
    val signal: BoardCardSignal? = null,
)

/**
 * The viewer's hand, in the server's own order.
 *
 * Empty for a spectator, who has no hand — and empty is a real state the board draws as nothing rather
 * than as an empty region, which is §7.4's rule about regions that hold height.
 */
fun handCards(state: GameState): List<TableHandCard> {
    val playable = state.playable.map { it.objectId }.toSet()
    return state.hand.map { card ->
        TableHandCard(
            id = card.id,
            card =
                CardDisplay(
                    name = card.name,
                    manaCost = card.manaCost,
                    typeLine = card.typeLine,
                    oracleText = card.rules.joinToString("\n").takeIf { it.isNotBlank() },
                ),
            art = handArtRequest(card.setCode, card.collectorNumber),
            signal = if (card.id in playable) BoardCardSignal.Playable else null,
        )
    }
}

/** The printing the server named, or `null` when it named none — a token, or a card it did not pin. */
private fun handArtRequest(
    setCode: String?,
    collectorNumber: String?,
): CardArtRequest? {
    val set = setCode?.takeIf { it.isNotBlank() } ?: return null
    val number = collectorNumber?.takeIf { it.isNotBlank() } ?: return null
    return CardArtRequest(setCode = set, collectorNumber = number, size = CardArtSize.SMALL)
}
