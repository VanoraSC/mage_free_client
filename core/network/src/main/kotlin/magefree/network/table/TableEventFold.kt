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
 * scripted event sequences (join → seat updates → construct → match-starting), independent of the
 * `TableClient`.
 *
 * [fold] is a filter *and* a reducer: it returns the next [TableState] only for a **relevant** event
 * (one of 0036's table pushes, matching the state's [TableState.tableId]) and `null` for everything else
 * (a different table, a correlated reply, a lobby/chat/ping frame the push side-channel also carries), so
 * `observeTable` emits a new state exactly when a table event actually changes it.
 *
 * The `:protocol` import stays confined here (and the mapper/clients) — the produced [TableState] is
 * `:protocol`-free.
 */
internal object TableEventFold {
    /**
     * Fold one server-pushed [message] into [state], or return `null` when it is not a table event for
     * this table (so the caller emits nothing). Handled events:
     * - [TableUpdated] — you joined/created the table: record ownership.
     * - [SeatUpdated] — a seat's occupancy changed: upsert the seat by its player id.
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
                    state.copy(seats = state.seats.upsertSeat(message))
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

    /** Upsert a seat by its player id: update the matching seat's owner flag, else append a new one. */
    private fun List<Seat>.upsertSeat(update: SeatUpdated): List<Seat> {
        val id = update.playerId
        val existing = if (id == null) null else firstOrNull { it.playerId == id }
        return if (existing != null) {
            map { if (it.playerId == id) it.copy(isOwner = it.isOwner || update.isOwner) else it }
        } else {
            this + Seat(playerId = id, name = id, isOwner = update.isOwner)
        }
    }
}
