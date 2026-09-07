package magefree.feature.game.table

import magefree.network.game.GameState

/*
 * A player's piles of cards that are not on the battlefield, as the status rail draws them.
 *
 * **The zones a player reads most often after the battlefield.** A graveyard decides flashback,
 * delve, escape, threshold and every "return target creature card". Exile decides whether the thing
 * that vanished is coming back. Until now the board said only how many cards were in each.
 *
 * Nothing here is derived and nothing here is sorted. `GamePlayer.graveyard` and `GamePlayer.exile`
 * are the server's own lists in the server's own order — for a graveyard, the order things died in —
 * and the top of a pile means its last entry to every effect that names one.
 */

/**
 * Which pile this is.
 *
 * @property label what the rail calls it when it is empty and what the browser is titled.
 */
enum class TableZoneKind(
    val label: String,
) {
    Graveyard("Graveyard"),

    /**
     * Exile as a player thinks of it: cards that are gone.
     *
     * Everything the server put in the general exile pile that is not doing anything special.
     */
    Exile("Exile"),

    /**
     * **Cards exiled in a way that means something.** Plot, suspend, rebound, adventure, foretell,
     * airbend — an exile a card is coming back from, or can be cast from.
     *
     * §7.13: there is no single upstream flag for this, and the reference client does not have one
     * either. Two signals together cover it, and both are already on the wire: a zone *name*, because
     * effects that make their own exile zone name it (`"Plots of <player> - Exile"`, `"Rebound"`), and
     * `canPlayObjects`, because anything castable from exile right now is by definition not just gone.
     * Neither alone is enough — a plotted card is named always and playable only on the turn it can be
     * cast, and an airbent card is nameless and only marked while it is castable — so the board uses
     * both and says so rather than pretending to a certainty it has not got.
     */
    SpecialExile("Other"),
}

/**
 * One seat's pile.
 *
 * @property playerId the seat's server id.
 * @property isViewer whether this is the seat the board was built for, which is what decides where in
 *   the rail it is drawn: the viewer's at the bottom, everyone else's at the top, the same way the two
 *   halves of the board are arranged.
 * @property cards the pile, in the server's order.
 */
data class TableZonePile(
    val playerId: String,
    val isViewer: Boolean,
    val kind: TableZoneKind,
    val cards: List<TableCard> = emptyList(),
) {
    /** How many cards are in it, which the rail shows even when it is drawing one of them. */
    val count: Int get() = cards.size

    /**
     * The card on top — the server's last entry.
     *
     * This is what the rail draws, because it is what a pile looks like on a table and because it is
     * the single most useful card in it: the one that just went there. `null` for an empty pile, which
     * the rail draws as a placeholder rather than as nothing — the rail's whole job is to be in a fixed
     * place, so a region that vanished when empty would move everything under it the first time a
     * creature died.
     */
    val topCard: TableCard? get() = cards.lastOrNull()
}

/**
 * Every seat's piles, opponents first and the viewer last, each seat's in rail order.
 *
 * The same seat order the battlefield uses, so the rail and the board agree about who is where without
 * either of them being told.
 */
fun tableZones(state: GameState): List<TableZonePile> {
    val special = specialExileIds(state)
    return state.players
        .sortedBy { it.isViewer }
        .flatMap { player ->
            val exiled = exileCards(state, player.playerId)
            listOf(
                TableZonePile(
                    playerId = player.playerId,
                    isViewer = player.isViewer,
                    kind = TableZoneKind.Graveyard,
                    cards = graveyardCards(state, player.playerId),
                ),
                TableZonePile(
                    playerId = player.playerId,
                    isViewer = player.isViewer,
                    kind = TableZoneKind.SpecialExile,
                    cards = exiled.filter { it.id in special },
                ),
                TableZonePile(
                    playerId = player.playerId,
                    isViewer = player.isViewer,
                    kind = TableZoneKind.Exile,
                    cards = exiled.filterNot { it.id in special },
                ),
            )
        }
}

/**
 * The cards in exile that are there for a reason the player has to keep track of.
 *
 * A card in a *named* exile zone, or one the server is currently offering from exile. See
 * [TableZoneKind.SpecialExile] for why it takes both.
 */
private fun specialExileIds(state: GameState): Set<String> {
    val named =
        state.exile
            .filterNot { it.name.isOrdinaryExile() }
            .flatMap { zone -> zone.cards.map { it.id } }
    val offered = state.playable.map { it.objectId }.toSet()
    val exiled = state.players.flatMap { player -> player.exile.map { it.id } }.toSet()
    return named.toSet() + (offered intersect exiled)
}

/**
 * Whether an exile zone's name says nothing.
 *
 * Upstream's default pile is called `Permanent`; an effect that makes its own zone names it after the
 * effect. A blank name is treated the same way as the default, because a pile that did not say what it
 * was did not say anything.
 */
private fun String.isOrdinaryExile(): Boolean = isBlank() || equals(DEFAULT_EXILE_ZONE, ignoreCase = true)

/** Upstream's name for the exile pile that is not any effect's own. */
private const val DEFAULT_EXILE_ZONE = "Permanent"
