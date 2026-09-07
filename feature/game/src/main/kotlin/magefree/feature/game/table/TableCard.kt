package magefree.feature.game.table

import magefree.cards.art.CardArtRequest
import magefree.cards.art.CardArtSize
import magefree.designsystem.card.BoardCardSignal
import magefree.designsystem.card.CardDisplay
import magefree.designsystem.card.CardPreviewAction
import magefree.designsystem.card.CardPreviewState
import magefree.network.game.CardType
import magefree.network.game.GameCard
import magefree.network.game.GameState

/*
 * A card in a zone, as the board draws it — the hand, a graveyard, and whatever comes next.
 *
 * **One type for all of them, because the board treats them the same.** A card in a graveyard and a
 * card in hand differ in exactly one thing a player can act on: whether the server is offering it
 * right now, which is already [TableCard.signal]. Everything else — the face, the printing, the stats,
 * the abilities, the way it is read when it is tapped — is identical, and duplicating the type per
 * zone would duplicate the preview that reads it.
 *
 * **Nothing here is derived.** `GameState.hand` is the viewer's own cards, in the server's order,
 * `GamePlayer.graveyard` likewise, and `GameState.playable` is upstream's own list of what this player
 * may act on right now. A client that decided for itself which cards were castable would be answering
 * a rules question — the same one the cast flow refuses to answer, for the same reason: it would be
 * wrong exactly where it mattered, after cost reductions, alternative costs and everything else the
 * server has already accounted for.
 */

/**
 * One card in a zone.
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
data class TableCard(
    val id: String,
    val card: CardDisplay,
    val art: CardArtRequest? = null,
    val signal: BoardCardSignal? = null,
    val isLand: Boolean = false,
    val power: String? = null,
    val toughness: String? = null,
    val abilities: List<String> = emptyList(),
) {
    /** True when the server is offering this card right now. */
    val isPlayable: Boolean get() = signal == BoardCardSignal.Playable

    /**
     * What a player would call doing this: *Play* a land, *Cast* anything else.
     *
     * A wording choice and not a legality one. Whether the card can be played at all is [isPlayable],
     * which is the server's answer; this only picks the word, and picking it from the card's type is
     * what every Magic player and every rules document does.
     */
    val actionLabel: String get() = if (isLand) PLAY_LABEL else CAST_LABEL

    /**
     * The same printing at full resolution, for a card being *read* rather than glanced at.
     *
     * §7.5: *"Only Full loads full-resolution art, which matters for memory and for the first-turn
     * experience."* A hand tile is a hundred-odd dp wide and a downsampled image is indistinguishable
     * there; an inspected card fills three quarters of the screen, and at that size the small image is
     * visibly soft exactly where a player is trying to read printed text.
     *
     * A separate request rather than raising the size everywhere, because the two are cached
     * separately and a board that loaded full-resolution art for every permanent would spend the
     * memory and the first-turn bandwidth on cards nobody is looking at.
     */
    val fullArt: CardArtRequest? get() = art?.copy(size = CardArtSize.LARGE)
}

/** Lands are *played*, not cast — they never use the stack. */
const val PLAY_LABEL: String = "Play"

/** Everything else is *cast*. */
const val CAST_LABEL: String = "Cast"

/**
 * The viewer's hand, in the server's own order.
 *
 * Empty for a spectator, who has no hand — and empty is a real state the board draws as nothing rather
 * than as an empty region, which is §7.4's rule about regions that hold height.
 */
fun handCards(state: GameState): List<TableCard> {
    val playable = state.playable.map { it.objectId }.toSet()
    return state.hand.map { card -> card.toTableCard(playable) }
}

/**
 * One player's graveyard, in the server's own order.
 *
 * **Nothing sorts this.** A graveyard has a meaningful order — it is the order things died in, and
 * every effect that cares about the top of it means the server's last entry — so grouping or sorting
 * it here would be the client inventing a fact.
 *
 * The playable set still applies: flashback, escape and their relatives make a card in a graveyard
 * castable, and when the server says so the board says so, whatever it is currently able to do about
 * it.
 */
fun graveyardCards(
    state: GameState,
    playerId: String,
): List<TableCard> {
    val playable = state.playable.map { it.objectId }.toSet()
    val player = state.players.firstOrNull { it.playerId == playerId } ?: return emptyList()
    return player.graveyard.map { card -> card.toTableCard(playable) }
}

/**
 * One player's exiled cards, in the server's own order.
 *
 * `GamePlayer.exile` is what that player **owns** in any exile zone — owner, not controller — so a
 * card of yours an opponent exiled is on your list, which is where a player looks for it.
 */
fun exileCards(
    state: GameState,
    playerId: String,
): List<TableCard> {
    val playable = state.playable.map { it.objectId }.toSet()
    val player = state.players.firstOrNull { it.playerId == playerId } ?: return emptyList()
    return player.exile.map { card -> card.toTableCard(playable) }
}

private fun GameCard.toTableCard(playable: Set<String>): TableCard =
    TableCard(
        id = id,
        card =
            CardDisplay(
                name = name,
                manaCost = manaCost,
                typeLine = typeLine,
                oracleText = rules.joinToString("\n").takeIf { it.isNotBlank() },
            ),
        art = zoneArtRequest(setCode, collectorNumber),
        signal = if (id in playable) BoardCardSignal.Playable else null,
        isLand = CardType.Land in cardTypes,
        power = power,
        toughness = toughness,
        abilities = rules,
    )

/**
 * A card as the inspect overlay shows it.
 *
 * @param oracleText the **printed** text, which the wire does not carry — `CardView.rules` is the
 *   game-aware form and arrives as [TableCard.abilities]. The two differ exactly where it matters,
 *   so the printed text comes from the device's own card database, and until the board is looking cards
 *   up there it is supplied by whoever is showing the preview.
 * @param onAct what to do when the player presses Play or Cast. Absent for a card the server has not
 *   offered: a button that submitted an action the server had not agreed to is the one thing §7.6
 *   forbids everywhere else in this app.
 */
fun tableCardPreview(
    card: TableCard,
    oracleText: String? = null,
    onAct: ((String) -> Unit)? = null,
): CardPreviewState =
    CardPreviewState(
        card = card.card,
        power = card.power,
        toughness = card.toughness,
        abilities = card.abilities,
        oracleText = oracleText,
        action =
            if (card.isPlayable && onAct != null) {
                CardPreviewAction(label = card.actionLabel, onAct = { onAct(card.id) })
            } else {
                null
            },
    )

/** The printing the server named, or `null` when it named none — a token, or a card it did not pin. */
private fun zoneArtRequest(
    setCode: String?,
    collectorNumber: String?,
): CardArtRequest? {
    val set = setCode?.takeIf { it.isNotBlank() } ?: return null
    val number = collectorNumber?.takeIf { it.isNotBlank() } ?: return null
    return CardArtRequest(setCode = set, collectorNumber = number, size = CardArtSize.SMALL)
}
