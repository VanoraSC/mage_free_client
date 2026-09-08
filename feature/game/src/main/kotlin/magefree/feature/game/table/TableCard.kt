package magefree.feature.game.table

import magefree.cards.art.CardArtRequest
import magefree.cards.art.CardArtSize
import magefree.designsystem.card.BoardCardSignal
import magefree.designsystem.card.CardDisplay
import magefree.designsystem.card.CardPreviewAction
import magefree.designsystem.card.CardPreviewProvenance
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
 * @property zone where the card actually is. Not decoration: a card the server is offering from a
 *   graveyard is castable *and* not in hand, and a player who cannot see which would be reading a hand
 *   that is not theirs.
 * @property playableAbilities upstream's own short text for each ability making this playable, in its
 *   own order. Empty for a card the server is not offering, and empty for one it is offering over a
 *   wire too old to carry the names.
 */
data class TableCard(
    val id: String,
    val card: CardDisplay,
    val art: CardArtRequest? = null,
    val signal: BoardCardSignal? = null,
    /** Whether the player has chosen this card as part of the answer the server is waiting for. */
    val isSelected: Boolean = false,
    val isLand: Boolean = false,
    val power: String? = null,
    val toughness: String? = null,
    val abilities: List<String> = emptyList(),
    val zone: TableCardZone = TableCardZone.Hand,
    val playableAbilities: List<String> = emptyList(),
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

    /**
     * The same printing as an art crop, for a card drawn at the **Board** tier.
     *
     * The rail draws the top of a pile the way the battlefield draws a permanent — the illustration
     * and nothing else — and that is a different image from the whole card [art] carries for the
     * hand, not a crop of it.
     */
    val boardArt: CardArtRequest? get() = art?.copy(size = CardArtSize.ART_CROP)
}

/**
 * Where a card the board is drawing actually is.
 *
 * **Only zones a card can be *played from*.** A card on the battlefield is a permanent and has its own
 * model; these are the piles a spell is cast out of, which for everything but the hand means some
 * effect said so.
 *
 * @property label what the board calls it when it has to say so out loud.
 */
enum class TableCardZone(
    val label: String,
) {
    Hand("Hand"),
    Graveyard("Graveyard"),
    Exile("Exile"),

    /** Shown to this seat by some effect. Transient: upstream clears its reveals on the next update. */
    Revealed("Revealed"),
    ;

    /** Whether a card here is somewhere a player would not expect to be casting from. */
    val isElsewhere: Boolean get() = this != Hand
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
fun handCards(
    state: GameState,
    picks: PromptPicks = PromptPicks(),
): List<TableCard> = state.hand.map { card -> card.asTableCard(state, TableCardZone.Hand, picks) }

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
    picks: PromptPicks = PromptPicks(),
): List<TableCard> {
    val player = state.players.firstOrNull { it.playerId == playerId } ?: return emptyList()
    return player.graveyard.map { card -> card.asTableCard(state, TableCardZone.Graveyard, picks) }
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
    picks: PromptPicks = PromptPicks(),
): List<TableCard> {
    val player = state.players.firstOrNull { it.playerId == playerId } ?: return emptyList()
    return player.exile.map { card -> card.asTableCard(state, TableCardZone.Exile, picks) }
}

internal fun GameCard.asTableCard(
    state: GameState,
    zone: TableCardZone,
    picks: PromptPicks = PromptPicks(),
): TableCard {
    val offered = state.playable.firstOrNull { it.objectId == id }
    return TableCard(
        id = id,
        card =
            CardDisplay(
                name = name,
                manaCost = manaCost,
                typeLine = typeLine,
                oracleText = rules.joinToString("\n").takeIf { it.isNotBlank() },
            ),
        art = zoneArtRequest(setCode, collectorNumber),
        // One colour, one meaning: *you can act on this*. A card the server has offered and a card
        // that answers the outstanding question are the same fact to a player deciding what to press.
        signal = if (offered != null || id in picks.pickable) BoardCardSignal.Playable else null,
        isSelected = id in picks.picked,
        isLand = CardType.Land in cardTypes,
        power = shownPower,
        toughness = shownToughness,
        abilities = rules,
        zone = zone,
        playableAbilities = offered?.abilityNames.orEmpty(),
    )
}

/**
 * Everything the viewer may cast that is **not in their hand**.
 *
 * Flashback, escape, plot, adventure, foretell, disturb, a Snapcaster's grant, an opponent's Gonti
 * exile — Magic has a great many ways to cast a card from somewhere else, and a player who cannot see
 * them is playing a smaller game than the one in front of them. The reference client's answer is a
 * playable badge on the card *in its own zone window*, which means noticing it requires opening the
 * window first; this brings them to where a player is already looking.
 *
 * **The server decides, entirely.** These are the cards `canPlayObjects` names that happen to be in a
 * graveyard or an exile pile rather than in hand. Nothing here reasons about flashback; it reads a
 * list and looks up where each card is.
 *
 * In the server's own order within each zone, graveyard before exile, so the group does not reorder
 * itself between snapshots.
 */
fun playableElsewhere(state: GameState): List<TableCard> {
    val viewer = state.players.firstOrNull { it.isViewer } ?: return emptyList()
    val inHand = state.hand.map { it.id }.toSet()
    return (graveyardCards(state, viewer.playerId) + exileCards(state, viewer.playerId))
        .filter { it.isPlayable && it.id !in inHand }
}

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
        // **Where it is, and what is offering it.** A card castable from a graveyard is the one case
        // where "can I cast this" and "is this in my hand" have different answers, and a player is
        // owed both. The reasons are upstream's own text for the abilities `canPlayObjects` named —
        // never worked out here, and simply absent when the wire did not carry them.
        provenance =
            if (!card.zone.isElsewhere) {
                null
            } else {
                CardPreviewProvenance(zone = card.zone.label, reasons = card.playableAbilities)
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
