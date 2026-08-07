package magefree.network.table

import kotlinx.coroutines.flow.Flow
import magefree.decks.model.Deck

/**
 * The app-side, **UI-free** table client (story 0037): the *act* side of a table, layered over 0028's
 * multiplexed request/response and 0023's session/resume, complementing 0028's read-only `LobbyClient`.
 * Each verb turns a 0036 table-action request into a `suspend` call that maps the correlated
 * `TableActionResult`/`TableCreated` reply into a [Result] — a server decline surfaces as a typed
 * [TableActionFailure] carrying its reason, **never** a silent drop.
 *
 * The interface speaks **only** app-schema/0033 domain types: callers pass a [Deck] (mapped internally to
 * 0036's wire `DeckList`) and read back a [TableRef]/[TableState]. No `:protocol` or `mage.*` type appears
 * on this ABI — the 0028 discipline (the erased `BridgeClient.request` seam keeps wire types confined to
 * the implementation and mappers).
 *
 * [observeTable] exposes a single table's evolving [TableState], folded from 0036's server-pushed events
 * and re-synced across a 0023 resume so a reconnect never strands the seat. It stops at
 * [TablePhase.Starting] + a one-shot [TableState.matchStarting]; in-game state is Epic 11's.
 *
 * A [magefree.network.fake.FakeTableClient] backs hermetic tests of downstream (0038) code.
 */
interface TableClient {
    /** Create (host) a table from [options]; success yields the new [TableRef], a decline a [TableActionFailure]. */
    suspend fun createTable(options: CreateTableOptions): Result<TableRef>

    /**
     * Join the constructed table [tableId] as [seatName], submitting [deck] at join. [password] is
     * required only for a passworded table. Success yields `Unit`; a decline a [TableActionFailure].
     */
    suspend fun joinTable(
        tableId: String,
        seatName: String,
        deck: Deck,
        password: String? = null,
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
     * Observe the evolving [TableState] of [tableId]: emit [seed] first, then a new state each time a
     * 0036 event for this table folds in ([TableEventFold]). On a 0023 resume (a return to
     * [magefree.model.ConnectionState.Connected]) the current state is re-emitted so a reconnect does not
     * strand the seat. Cold: each collection starts a fresh subscription; the caller owns its scope.
     *
     * @param seed the starting state — from a create/join [TableRef.toSeed] or a bare
     *   [TableState] for a watch; defaults to an empty [TableState] for [tableId].
     */
    fun observeTable(
        tableId: String,
        seed: TableState = TableState(tableId),
    ): Flow<TableState>
}
