package magefree.network.table

import kotlinx.coroutines.flow.Flow
import magefree.decks.model.Deck

/**
 * The app-side, **UI-free** table client: the *act* side of a table, layered over the
 * multiplexed request/response and the session/resume, complementing the read-only `LobbyClient`.
 * Each verb turns a table-action request into a `suspend` call that maps the correlated
 * `TableActionResult`/`TableCreated` reply into a [Result] — a server decline surfaces as a typed
 * [TableActionFailure] carrying its reason, **never** a silent drop.
 *
 * The interface speaks **only** app-schema/deck domain types: callers pass a [Deck] (mapped internally to
 * the wire `DeckList`) and read back a [TableRef]/[TableState]. No `:protocol` or `mage.*` type appears
 * on this ABI — the discipline (the erased `BridgeClient.request` seam keeps wire types confined to
 * the implementation and mappers).
 *
 * [observeTable] exposes a single table's evolving [TableState], folded from the server-pushed events
 * and re-synced across a resume so a reconnect never strands the seat. It stops at
 * [TablePhase.Starting] + a one-shot [TableState.matchStarting]; in-game state is the game layer's.
 *
 * A [magefree.network.fake.FakeTableClient] backs hermetic tests of downstream code.
 */
interface TableClient {
    /** Create (host) a table from [options]; success yields the new [TableRef], a decline a [TableActionFailure]. */
    suspend fun createTable(options: CreateTableOptions): Result<TableRef>

    /**
     * Join the constructed table [tableId] as [seatName], submitting [deck] at join. [password] is
     * required only for a passworded table. Success yields `Unit`; a decline a [TableActionFailure].
     *
     * [playerType] is the **kind** of occupant being seated. It defaults to
     * [SeatPlayerType.Human] — the ordinary "I sit down" join — but a host completing its own table also
     * seats the AI players it configured, and upstream fills those seats with this very same call,
     * differing only by the type: `Table.getNextAvailableSeat` matches a join to a seat **by player
     * type**, so an AI seat is only ever filled by a join that names its AI type, and the server's
     * "join only once" guard applies to `HUMAN` alone (which is why one session can legitimately fill
     * an AI seat and then its own).
     */
    suspend fun joinTable(
        tableId: String,
        seatName: String,
        deck: Deck,
        password: String? = null,
        playerType: SeatPlayerType = SeatPlayerType.Human,
    ): Result<Unit>

    /** Submit [deck] (the first, binding submission) for the seat at [tableId] during construction. */
    suspend fun submitDeck(
        tableId: String,
        deck: Deck,
    ): Result<Unit>

    /** Update the in-progress [deck] for the seat at [tableId] during construction (a save before submit). */
    suspend fun updateDeck(
        tableId: String,
        deck: Deck,
    ): Result<Unit>

    /** Leave the table [tableId] (give up your seat). */
    suspend fun leaveTable(tableId: String): Result<Unit>

    /** Remove the table [tableId] (host tears it down). */
    suspend fun removeTable(tableId: String): Result<Unit>

    /** Start the match at table [tableId] (the game-start signal then arrives as a pushed event). */
    suspend fun startMatch(tableId: String): Result<Unit>

    /** Watch (spectate) the table [tableId]. */
    suspend fun watchTable(tableId: String): Result<Unit>

    /**
     * Read the current [TableDetails] of [tableId] — its seats and the **server's** lifecycle state
     *. This is the room's source of seat state: XMage emits no per-seat push before
     * match-start, so the table is *read* rather than waited on.
     *
     * A table the server no longer lists yields a typed [TableNotFoundFailure]; a transport failure a
     * failed [Result] — never a throw, and never an empty success that would look like a seatless table.
     *
     * [observeTable] calls this itself (on open, on each table-lifecycle push, and after a resume), so a
     * caller rendering the room does not need to: this is here for a deliberate one-shot re-read.
     */
    suspend fun refreshTable(tableId: String): Result<TableDetails>

    /**
     * Observe the evolving [TableState] of [tableId]: emit [seed] first, then a new state each time a
     * table event for this table folds in ([TableEventFold]). On a resume (a return to
     * [magefree.model.ConnectionState.Connected]) the current state is re-emitted so a reconnect does not
     * strand the seat. Cold: each collection starts a fresh subscription; the caller owns its scope.
     *
     * **Seats.** It also [refreshTable]s — once when collection starts, on each
     * table-lifecycle push for this table, after a resume, and periodically while the table is still
     * seating — folding each [TableDetails] into [TableState.seats]/[TableState.serverState].
     *
     * The periodic re-read is not belt-and-braces: upstream notifies already-seated players of
     * **nothing** when another human takes a seat, so without it a host would wait forever for a table
     * that is in fact ready. It is scoped to the collection (a room that is off screen issues no
     * traffic at all) and stops once [TableState.isPastSeating] — the table has become a game.
     *
     * @param seed the starting state — from a create/join [TableRef.toSeed] or a bare
     *   [TableState] for a watch; defaults to an empty [TableState] for [tableId].
     */
    fun observeTable(
        tableId: String,
        seed: TableState = TableState(tableId),
    ): Flow<TableState>
}
