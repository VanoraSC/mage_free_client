package magefree.bridge.mapping

import mage.remote.SessionImpl
import magefree.protocol.CreateTableOptions
import magefree.protocol.DeckList
import magefree.protocol.SeatPlayerTypeCode
import magefree.protocol.ServerMessage
import magefree.protocol.SkillLevelCode
import magefree.protocol.TableActionCode
import magefree.protocol.TableActionResult
import magefree.protocol.TableCreated
import magefree.protocol.TableSummary
import java.util.UUID

/**
 * The table-action dispatch+map boundary — the sibling of [LobbyRelay] for the **act** side (story
 * 0036). Each method builds the `mage.*` arguments (`MatchOptions`, `DeckCardLists`, `PlayerType`) via
 * the pure sibling mappers, dispatches to the matching `SessionImpl` verb within the resolved room, and
 * maps the result back to an app-schema [ServerMessage]. All `mage.*` construction stays **inside** this
 * package, so no upstream shape crosses the wire (`docs/architecture.md`; the same reason [LobbyRelay]
 * lives here).
 *
 * These are **blocking** JBoss-remoting calls; callers (`XMageSession`) invoke them on
 * `Dispatchers.IO`. This layer does not catch [mage.remote.MageRemoteException] — a transport failure
 * propagates and the caller decides the reply (the coordinator maps a failure to a failed
 * [TableActionResult]).
 *
 * **Result semantics.** `createTable` returns a `mage.view.TableView` (or `null`); a non-null view maps
 * to [TableCreated] (reusing [TableMapper]), a null to a failed [TableActionResult]. The boolean verbs
 * map to a structured [TableActionResult] — a `false` becomes a **typed failure**, never a silent drop.
 */
public object TableRelay {
    /**
     * Creates a table in [roomId] from [options], replying [TableCreated] with the mapped new table on
     * success or a failed [TableActionResult] ([TableActionCode.CREATE]) when the server declines
     * (a null `TableView`).
     */
    public fun createTable(
        session: SessionImpl,
        roomId: UUID,
        options: CreateTableOptions,
    ): ServerMessage {
        val view = session.createTable(roomId, MatchOptionsMapper.toMatchOptions(options))
        return createdMessage(view?.let { TableMapper.map(it) })
    }

    /**
     * The pure create-result decision (unit-testable without a `TableView`): a mapped [table] → a
     * [TableCreated]; a null (the server declined) → a failed [TableActionResult]. The `TableView →
     * TableSummary` getter-forwarding itself is [TableMapper.map], covered end-to-end by the live path.
     */
    public fun createdMessage(table: TableSummary?): ServerMessage =
        if (table != null) {
            TableCreated(table = table)
        } else {
            TableActionResult(action = TableActionCode.CREATE, ok = false, reason = CREATE_DECLINED)
        }

    /** Joins the constructed table [tableId] in [roomId] as [seatName], submitting [deck]. */
    public fun joinTable(
        session: SessionImpl,
        roomId: UUID,
        tableId: UUID,
        seatName: String,
        deck: DeckList,
        playerType: SeatPlayerTypeCode,
        skill: SkillLevelCode,
        password: String?,
    ): TableActionResult =
        resultOf(
            TableActionCode.JOIN,
            session.joinTable(
                roomId,
                tableId,
                seatName,
                MatchOptionsMapper.playerTypeOf(playerType),
                skillIntOf(skill),
                DeckListMapper.toDeckCardLists(deck),
                password ?: "",
            ),
        )

    /** Submits [deck] (the binding submission) for the seat at [tableId] during construction. */
    public fun submitDeck(
        session: SessionImpl,
        tableId: UUID,
        deck: DeckList,
    ): TableActionResult = resultOf(TableActionCode.SUBMIT_DECK, session.submitDeck(tableId, DeckListMapper.toDeckCardLists(deck)))

    /** Saves the in-progress [deck] for the seat at [tableId] during construction. */
    public fun updateDeck(
        session: SessionImpl,
        tableId: UUID,
        deck: DeckList,
    ): TableActionResult = resultOf(TableActionCode.UPDATE_DECK, session.updateDeck(tableId, DeckListMapper.toDeckCardLists(deck)))

    /** Leaves the table [tableId] in [roomId]. */
    public fun leaveTable(
        session: SessionImpl,
        roomId: UUID,
        tableId: UUID,
    ): TableActionResult = resultOf(TableActionCode.LEAVE, session.leaveTable(roomId, tableId))

    /** Removes the table [tableId] in [roomId]. */
    public fun removeTable(
        session: SessionImpl,
        roomId: UUID,
        tableId: UUID,
    ): TableActionResult = resultOf(TableActionCode.REMOVE, session.removeTable(roomId, tableId))

    /** Starts the match at table [tableId] in [roomId]. */
    public fun startMatch(
        session: SessionImpl,
        roomId: UUID,
        tableId: UUID,
    ): TableActionResult = resultOf(TableActionCode.START_MATCH, session.startMatch(roomId, tableId))

    /** Watches (spectates) the table [tableId] in [roomId]. */
    public fun watchTable(
        session: SessionImpl,
        roomId: UUID,
        tableId: UUID,
    ): TableActionResult = resultOf(TableActionCode.WATCH, session.watchTable(roomId, tableId))

    /**
     * The pure boolean-verb result decision (unit-testable): a `true` → an ok [TableActionResult]; a
     * `false` → a failed one carrying [ACTION_DECLINED] — a typed failure, never a silent drop.
     */
    public fun resultOf(
        action: TableActionCode,
        ok: Boolean,
    ): TableActionResult = TableActionResult(action = action, ok = ok, reason = if (ok) null else ACTION_DECLINED)

    /** Maps an app-schema skill to XMage's `int skill` join argument (BEGINNER/CASUAL/SERIOUS → 1/2/3). */
    private fun skillIntOf(skill: SkillLevelCode): Int =
        when (skill) {
            SkillLevelCode.BEGINNER -> 1
            SkillLevelCode.CASUAL -> 2
            SkillLevelCode.SERIOUS -> 3
            SkillLevelCode.UNKNOWN -> 1
        }

    private const val CREATE_DECLINED = "the server declined to create the table"
    private const val ACTION_DECLINED = "the server declined the action"
}
