package magefree.network.table

import magefree.protocol.ConstructPrompt
import magefree.protocol.SeatUpdated
import magefree.protocol.ServerMessage
import magefree.protocol.SideboardPrompt
import magefree.protocol.TableUpdated
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import magefree.protocol.MatchStarting as MatchStartingMessage

/**
 * Hermetic coverage of the pure [TableEventFold]: it folds each 0036 table event into the app-schema
 * [TableState], filters events for a different table (returning `null`, no emission), and ignores
 * frames that are not table events. No client, socket, or coroutine — just the reducer.
 */
class TableEventFoldTest {
    private val seed = TableState(tableId = "t-1", optionsSummary = "Two Player Duel")

    private fun fold(
        state: TableState,
        message: ServerMessage,
    ): TableState = TableEventFold.fold(state, message) ?: state

    @Test
    fun aScriptedJoinToMatchStartingSequenceFoldsIntoTheExpectedStates() {
        var state = seed

        // Join/create: you are seated and own the table.
        state = fold(state, TableUpdated(tableId = "t-1", isOwner = true))
        assertTrue(state.isOwner)
        assertEquals(TablePhase.Waiting, state.phase)

        // A seat fills.
        state = fold(state, SeatUpdated(tableId = "t-1", playerId = "p-2"))
        assertEquals(listOf("p-2"), state.seats.map { it.playerId })
        assertTrue(state.seats.single().isOccupied)

        // Construction begins.
        state = fold(state, ConstructPrompt(tableId = "t-1", remainingSeconds = 60))
        assertEquals(TablePhase.Constructing, state.phase)

        // The match starts — the one-shot signal + Starting phase (the Epic 11 boundary).
        state = fold(state, MatchStartingMessage(gameId = "g-9", tableId = "t-1", playerId = "p-1"))
        assertEquals(TablePhase.Starting, state.phase)
        assertEquals(MatchStarting(gameId = "g-9", tableId = "t-1", playerId = "p-1"), state.matchStarting)
    }

    @Test
    fun aSideboardPromptAdvancesToConstructing() {
        val next = TableEventFold.fold(seed, SideboardPrompt(tableId = "t-1", isConstruct = true))
        assertEquals(TablePhase.Constructing, next?.phase)
    }

    @Test
    fun anEventForAnotherTableIsIgnored() {
        assertNull(TableEventFold.fold(seed, ConstructPrompt(tableId = "other")))
        assertNull(TableEventFold.fold(seed, TableUpdated(tableId = "other", isOwner = true)))
        assertNull(TableEventFold.fold(seed, MatchStartingMessage(gameId = "g", tableId = "other")))
    }

    @Test
    fun aMatchStartingWithNoTableIdIsAcceptedForTheObservedTable() {
        // The per-recipient push may omit tableId; accept it so the seat is not stranded pre-game.
        val next = TableEventFold.fold(seed, MatchStartingMessage(gameId = "g-9", tableId = null, playerId = "p-1"))
        assertEquals("g-9", next?.matchStarting?.gameId)
        assertEquals("t-1", next?.matchStarting?.tableId)
    }

    @Test
    fun aSeatUpdateUpsertsRatherThanDuplicating() {
        var state = TableEventFold.fold(seed, SeatUpdated(tableId = "t-1", playerId = "p-2"))!!
        state = TableEventFold.fold(state, SeatUpdated(tableId = "t-1", playerId = "p-2", isOwner = true))!!
        assertEquals(1, state.seats.size)
        assertTrue(state.seats.single().isOwner)
    }

    @Test
    fun aSeatUpdateWithNoPlayerIdCannotGrowAPhantomSeatList() {
        // The defect (story 0040): a null-playerId SeatUpdated used to *append* a brand-new seat on
        // every push, so a repeated push grew `seats` without bound. It carries no key, so it must be
        // ignored outright — the fold returns null (no state change, nothing emitted).
        assertNull(TableEventFold.fold(seed, SeatUpdated(tableId = "t-1", playerId = null)))

        // Not just from the empty seed: it must not grow a table whose seats are already known either.
        val seated =
            seed.copy(
                seats =
                    listOf(
                        Seat(index = 0, name = "alice", isOccupied = true),
                        Seat(index = 1, isOccupied = false),
                    ),
            )
        var state = seated
        repeat(50) {
            val next = TableEventFold.fold(state, SeatUpdated(tableId = "t-1", playerId = null, isOwner = true))
            assertNull("a null-playerId seat update must not change the state", next)
            state = next ?: state
        }
        assertEquals(seated, state)
        assertEquals(2, state.seats.size)
    }

    @Test
    fun aKeyedSeatUpdateFillsAKnownEmptySlotRatherThanAppending() {
        // A table read (story 0040) established two slots; a push naming a player fills the free one
        // instead of appending a third seat.
        val known =
            seed.copy(
                seats =
                    listOf(
                        Seat(index = 0, name = "alice", isOccupied = true, playerId = "p-1"),
                        Seat(index = 1, playerType = SeatPlayerType.ComputerMad, isOccupied = false),
                    ),
            )

        val next = TableEventFold.fold(known, SeatUpdated(tableId = "t-1", playerId = "p-2"))!!

        assertEquals(2, next.seats.size)
        assertEquals("p-2", next.seats[1].playerId)
        assertTrue(next.seats[1].isOccupied)
        // The seat keeps the slot's server-declared type; the occupied seat is untouched.
        assertEquals(SeatPlayerType.ComputerMad, next.seats[1].playerType)
        assertEquals(known.seats[0], next.seats[0])
    }

    @Test
    fun onlyTheTableLifecyclePushesForThisTableTriggerADetailRefresh() {
        // These are what make a seat change visible without a per-seat push: the room re-reads the
        // table on each of them (and on nothing else — no timer, no unrelated frame).
        assertTrue(TableEventFold.triggersDetailRefresh("t-1", TableUpdated(tableId = "t-1")))
        assertTrue(TableEventFold.triggersDetailRefresh("t-1", ConstructPrompt(tableId = "t-1")))
        assertTrue(TableEventFold.triggersDetailRefresh("t-1", SideboardPrompt(tableId = "t-1")))
        assertTrue(TableEventFold.triggersDetailRefresh("t-1", MatchStartingMessage(gameId = "g", tableId = "t-1")))
        // A per-recipient MatchStarting may omit the table id; it still refers to our table.
        assertTrue(TableEventFold.triggersDetailRefresh("t-1", MatchStartingMessage(gameId = "g", tableId = null)))

        assertFalse(TableEventFold.triggersDetailRefresh("t-1", TableUpdated(tableId = "other")))
        assertFalse(TableEventFold.triggersDetailRefresh("t-1", ConstructPrompt(tableId = "other")))
        assertFalse(TableEventFold.triggersDetailRefresh("t-1", magefree.protocol.Pong()))
    }

    @Test
    fun aNonTableFrameFoldsToNull() {
        // A lobby/session frame reaching the push side-channel is not a table event: no state change.
        assertNull(TableEventFold.fold(seed, magefree.protocol.Pong()))
    }
}
