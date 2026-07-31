package magefree.feature.lobby

import magefree.model.LobbyLoadState
import magefree.model.LobbySnapshot
import magefree.model.LobbyTable
import magefree.model.SkillLevel
import magefree.model.TableState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fast, pure coverage of the snapshot→[LobbyUiState] mapping and [applyFilter] — the client-side
 * presentation logic — without coroutines or a repository. Complements the Turbine
 * [LobbyViewModelTest] with the edge cases that are awkward to script through the real repository
 * (notably the non-destructive error-with-data path).
 */
class LobbyUiMappingTest {
    private fun table(
        id: String,
        gameType: String = "Two Player Duel",
        seatsFilled: Int = 1,
        seatsTotal: Int = 2,
        isPassworded: Boolean = false,
        createdAt: Long = 0L,
    ): LobbyTable =
        LobbyTable(
            id = id,
            name = id,
            host = "host",
            gameType = gameType,
            deckType = "Constructed",
            state = TableState.Waiting,
            seatsFilled = seatsFilled,
            seatsTotal = seatsTotal,
            isTournament = false,
            isRated = true,
            isPassworded = isPassworded,
            isLimited = false,
            skillLevel = SkillLevel.Casual,
            createdAtEpochMs = createdAt,
        )

    @Test
    fun errorWithRetainedDataStaysOnContentWithMessage() {
        val snapshot =
            LobbySnapshot(
                tables = listOf(table("a")),
                status = LobbyLoadState.Error,
                error = "refresh failed",
            )
        val state = snapshot.toLobbyUiState(LobbyFilter())
        // Non-destructive: keep showing the list, surface the message inline (not the full error surface).
        assertEquals(LobbyPhase.Content, state.phase)
        assertEquals("refresh failed", state.errorMessage)
        assertEquals(1, state.totalTables)
    }

    @Test
    fun errorWithNoDataUsesTheErrorSurface() {
        val snapshot = LobbySnapshot(status = LobbyLoadState.Error, error = "boom")
        val state = snapshot.toLobbyUiState(LobbyFilter())
        assertEquals(LobbyPhase.Error, state.phase)
        assertEquals("boom", state.errorMessage)
    }

    @Test
    fun idleSnapshotIsDisconnectedWithNoError() {
        val state = LobbySnapshot().toLobbyUiState(LobbyFilter())
        assertEquals(LobbyPhase.Disconnected, state.phase)
        assertNull(state.errorMessage)
        assertFalse(state.isEmpty)
    }

    @Test
    fun gameTypeNamesFallBackToDistinctTableTypesWhenServerSentNone() {
        val snapshot =
            LobbySnapshot(
                tables = listOf(table("a", gameType = "Duel"), table("b", gameType = "Draft"), table("c", gameType = "Duel")),
                status = LobbyLoadState.Loaded,
            )
        val state = snapshot.toLobbyUiState(LobbyFilter())
        assertEquals(listOf("Duel", "Draft"), state.gameTypeNames)
    }

    @Test
    fun filteredEmptyIsDistinctFromEmpty() {
        val snapshot =
            LobbySnapshot(tables = listOf(table("a", isPassworded = true)), status = LobbyLoadState.Loaded)
        val state = snapshot.toLobbyUiState(LobbyFilter(hidePassworded = true))
        assertTrue(state.isFilteredEmpty)
        assertFalse(state.isEmpty)
        assertEquals("Showing 0 of 1", state.countSummary)
    }

    @Test
    fun applyFilterSortsNewestFirstByDefault() {
        val tables = listOf(table("old", createdAt = 1L), table("new", createdAt = 5L), table("mid", createdAt = 3L))
        val sorted = tables.applyFilter(LobbyFilter())
        assertEquals(listOf("new", "mid", "old"), sorted.map { it.id })
    }
}
