package magefree.feature.game.table

import magefree.network.game.GameState

/*
 * A player's graveyard, as the status rail draws it.
 *
 * **The zone the board reads most often after the battlefield.** Flashback, escape, delve, threshold,
 * and every "return target creature card from your graveyard" are decisions made by reading it, and
 * until now the board said only how many cards were in it.
 *
 * Nothing here is derived and nothing here is sorted. `GamePlayer.graveyard` is the server's own list
 * in the server's own order — the order things died in — and the top of a graveyard means its last
 * entry to every effect that names one.
 */

/**
 * One seat's graveyard.
 *
 * @property playerId the seat's server id.
 * @property isViewer whether this is the seat the board was built for, which is what decides where in
 *   the rail it is drawn: the viewer's at the bottom, everyone else's at the top, the same way the two
 *   halves of the board are arranged.
 * @property cards the zone, in the server's order.
 */
data class TableGraveyard(
    val playerId: String,
    val isViewer: Boolean,
    val cards: List<TableCard> = emptyList(),
) {
    /** How many cards are in it, which the rail shows even when it is drawing one of them. */
    val count: Int get() = cards.size

    /**
     * The card on top — the server's last entry, and the one that just died.
     *
     * This is what the rail draws, because it is what a graveyard looks like on a table and because it
     * is the single most useful card in the pile. `null` for an empty graveyard, which the rail draws
     * as a placeholder rather than as nothing: the rail's whole job is to be in a fixed place, so a
     * region that vanished when empty would move everything under it the first time a creature died.
     */
    val topCard: TableCard? get() = cards.lastOrNull()
}

/**
 * Every seat's graveyard, opponents first and the viewer last.
 *
 * The same seat order the battlefield uses, so the rail and the board agree about who is where without
 * either of them being told.
 */
fun tableGraveyards(state: GameState): List<TableGraveyard> =
    state.players
        .sortedBy { it.isViewer }
        .map { player ->
            TableGraveyard(
                playerId = player.playerId,
                isViewer = player.isViewer,
                cards = graveyardCards(state, player.playerId),
            )
        }
