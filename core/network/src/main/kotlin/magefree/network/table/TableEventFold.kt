package magefree.network.table

import magefree.protocol.ConstructPrompt
import magefree.protocol.SeatUpdated
import magefree.protocol.ServerMessage
import magefree.protocol.SideboardPrompt
import magefree.protocol.TableUpdated
import magefree.protocol.MatchStarting as MatchStartingMessage

/**
 * The **pure** reducer that folds 0036's server-pushed table-lifecycle [ServerMessage]s into a
 * [TableState] (story 0037). It is the single place a wire table event becomes app-schema state; because
 * it is a plain function with no client/socket/coroutine dependency it is exhaustively unit-testable over
 * scripted event sequences (join → construct → match-starting), independent of the `TableClient`.
 *
 * [fold] is a filter *and* a reducer: it returns the next [TableState] only for a **relevant** event
 * (one of 0036's table pushes, matching the state's [TableState.tableId]) and `null` for everything else
 * (a different table, a correlated reply, a lobby/chat/ping frame the push side-channel also carries), so
 * `observeTable` emits a new state exactly when a table event actually changes it.
 *
 * **Seats do not come from here (story 0040).** XMage emits no per-seat callback before match-start, so
 * [SeatUpdated] has no producer; [TableState.seats] is filled by a targeted read
 * ([TableClient.refreshTable]). [triggersDetailRefresh] names the pushes that mean "the table changed —
 * read it again", which is how a seat change made by another player becomes visible without inventing an
 * event the server never sends.
 *
 * The `:protocol` import stays confined here (and the mapper/clients) — the produced [TableState] is
 * `:protocol`-free.
 */
internal object TableEventFold {
    /**
     * Fold one server-pushed [message] into [state], or return `null` when it is not a table event for
     * this table (so the caller emits nothing). Handled events:
     * - [TableUpdated] — you joined/created the table: record ownership.
     * - [SeatUpdated] — a seat's occupancy changed: upsert the seat by its player id (see [upsertSeat];
     *   an update with no player id is **ignored**, not appended).
     * - [ConstructPrompt] / [SideboardPrompt] — a deck-construction prompt: advance to
     *   [TablePhase.Constructing].
     * - [MatchStartingMessage] — the game is starting: set the one-shot [TableState.matchStarting] and
     *   advance to [TablePhase.Starting] (the Epic 11 boundary).
     */
    fun fold(
        state: TableState,
        message: ServerMessage,
    ): TableState? =
        when (message) {
            is TableUpdated ->
                if (message.tableId != state.tableId) {
                    null
                } else {
                    state.copy(isOwner = state.isOwner || message.isOwner)
                }

            is SeatUpdated ->
                if (message.tableId != state.tableId) {
                    null
                } else {
                    state.seats.upsertSeat(message)?.let { state.copy(seats = it) }
                }

            is ConstructPrompt ->
                if (message.tableId != state.tableId) null else state.copy(phase = TablePhase.Constructing)

            is SideboardPrompt ->
                if (message.tableId != state.tableId) null else state.copy(phase = TablePhase.Constructing)

            is MatchStartingMessage ->
                // MatchStarting may carry a null tableId (the push is per-recipient); accept it when it
                // matches or is unspecified, so the seat is not stranded before the game view opens.
                if (message.tableId != null && message.tableId != state.tableId) {
                    null
                } else {
                    state.copy(
                        phase = TablePhase.Starting,
                        matchStarting =
                            MatchStarting(
                                gameId = message.gameId,
                                tableId = message.tableId ?: state.tableId,
                                playerId = message.playerId,
                            ),
                    )
                }

            else -> null
        }

    /**
     * Whether [message] is a table-lifecycle push for [tableId] that means the table's **seats /
     * server state may have changed**, so the observer should re-read it (story 0040). These are the
     * pushes the client already receives — a join/create ([TableUpdated]), a construction prompt, and
     * match-start — so a seat change driven by another player becomes visible without a phantom
     * per-seat push, and without any background polling.
     */
    fun triggersDetailRefresh(
        tableId: String,
        message: ServerMessage,
    ): Boolean =
        when (message) {
            is TableUpdated -> message.tableId == tableId
            is ConstructPrompt -> message.tableId == tableId
            is SideboardPrompt -> message.tableId == tableId
            is MatchStartingMessage -> message.tableId == null || message.tableId == tableId
            else -> false
        }

    /**
     * Upsert a seat from a [SeatUpdated], or return `null` when the update cannot be applied (so the
     * caller emits nothing).
     *
     * The player id is the **only** key such a push carries, so an update without one is ignored.
     * Before story 0040 it appended a brand-new seat instead, which meant a repeated null-id push grew
     * [TableState.seats] without bound — an unbounded list of phantom seats. A keyed update fills the
     * matching seat; an unknown player takes the first free slot (the table's seat count is fixed by
     * the server), and only appends when every known slot is taken.
     */
    private fun List<Seat>.upsertSeat(update: SeatUpdated): List<Seat>? {
        val id = update.playerId ?: return null
        if (any { it.playerId == id }) {
            return map { seat ->
                if (seat.playerId == id) {
                    seat.copy(isOccupied = true, isOwner = seat.isOwner || update.isOwner)
                } else {
                    seat
                }
            }
        }
        val free = indexOfFirst { !it.isOccupied }
        return if (free >= 0) {
            mapIndexed { index, seat ->
                if (index == free) {
                    seat.copy(playerId = id, name = seat.name ?: id, isOccupied = true, isOwner = update.isOwner)
                } else {
                    seat
                }
            }
        } else {
            this + Seat(index = size, playerId = id, name = id, isOccupied = true, isOwner = update.isOwner)
        }
    }
}
