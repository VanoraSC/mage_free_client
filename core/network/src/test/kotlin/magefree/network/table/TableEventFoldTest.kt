package magefree.network.table

import magefree.protocol.ConstructPrompt
import magefree.protocol.SeatUpdated
import magefree.protocol.ServerMessage
import magefree.protocol.SideboardPrompt
import magefree.protocol.TableUpdated
import org.junit.Assert.assertEquals
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
    fun aNonTableFrameFoldsToNull() {
        // A lobby/session frame reaching the push side-channel is not a table event: no state change.
        assertNull(TableEventFold.fold(seed, magefree.protocol.Pong()))
    }
}
