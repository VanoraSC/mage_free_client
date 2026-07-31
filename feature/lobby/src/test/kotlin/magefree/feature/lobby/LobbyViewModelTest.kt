package magefree.feature.lobby

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import magefree.model.ConnectionState
import magefree.model.GameType
import magefree.model.LobbyTable
import magefree.model.RoomUser
import magefree.model.SkillLevel
import magefree.model.TableState
import magefree.network.LobbyClient
import magefree.network.LobbyRepository
import magefree.network.fake.FakeLobbyClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Hermetic Turbine/coroutines-test coverage of [LobbyViewModel] over a **real** [LobbyRepository]
 * backed by a scriptable [FakeLobbyClient] and a controllable connection [MutableStateFlow] — no
 * bridge, no device (mirrors `:core:network`'s own `LobbyRepositoryTest` setup). It pins the
 * snapshot→[LobbyUiState] mapping and the client-side filter/sort, plus the
 * loading / refresh / empty / error / disconnected surfaces.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LobbyViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    private val duel =
        table(id = "t-duel", name = "Duel", gameType = "Two Player Duel", seatsFilled = 1, seatsTotal = 2, createdAt = 3L)
    private val draft =
        table(
            id = "t-draft",
            name = "Draft",
            gameType = "Booster Draft",
            seatsFilled = 8,
            seatsTotal = 8,
            isPassworded = false,
            isLimited = true,
            isTournament = true,
            createdAt = 2L,
        )
    private val locked =
        table(
            id = "t-locked",
            name = "Private",
            gameType = "Two Player Duel",
            seatsFilled = 1,
            seatsTotal = 2,
            isPassworded = true,
            createdAt = 1L,
        )

    private val user = RoomUser("pete", "US", "W:1", "idle", "10ms", 1500)
    private val gameType = GameType("Two Player Duel", 2, 2)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.repository(
        client: LobbyClient,
        connection: MutableStateFlow<ConnectionState>,
    ): LobbyRepository =
        LobbyRepository(
            lobbyClient = client,
            connectionState = connection,
            dispatcher = UnconfinedTestDispatcher(testScheduler),
            scope = backgroundScope,
        )

    private suspend fun ReceiveTurbine<LobbyUiState>.awaitUntil(predicate: (LobbyUiState) -> Boolean): LobbyUiState {
        while (true) {
            val item = awaitItem()
            if (predicate(item)) return item
        }
    }

    @Test
    fun loadedSnapshotMapsToContentWithCountsAndTypes() =
        runTest {
            val client =
                FakeLobbyClient(
                    tables = listOf(duel, draft, locked),
                    roomUsers = listOf(user),
                    gameTypes = listOf(gameType),
                )
            val vm = LobbyViewModel(repository(client, MutableStateFlow(ConnectionState.Connected)))

            vm.uiState.test {
                vm.refresh()
                val content = awaitUntil { it.phase == LobbyPhase.Content }
                assertEquals(3, content.totalTables)
                assertEquals(3, content.shownTables)
                assertEquals(1, content.roomUserCount)
                assertEquals(listOf("Two Player Duel"), content.gameTypeNames)
                assertFalse(content.isEmpty)
                assertEquals("Showing 3 of 3", content.countSummary)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun firstRefreshReportsLoadingBeforeContent() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val client = FakeLobbyClient(tables = listOf(duel), gate = gate)
            val vm = LobbyViewModel(repository(client, MutableStateFlow(ConnectionState.Connected)))

            vm.uiState.test {
                vm.refresh()
                val loading = awaitUntil { it.phase == LobbyPhase.Loading }
                assertFalse("first load is not a refresh", loading.isRefreshing)

                gate.complete(Unit)
                val content = awaitUntil { it.phase == LobbyPhase.Content && !it.isRefreshing }
                assertEquals(1, content.totalTables)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun secondRefreshIsNonDestructiveRefreshingOverExistingList() =
        runTest {
            val client = FakeLobbyClient(tables = listOf(duel))
            val vm = LobbyViewModel(repository(client, MutableStateFlow(ConnectionState.Connected)))

            vm.uiState.test {
                vm.refresh()
                awaitUntil { it.phase == LobbyPhase.Content && !it.isRefreshing }

                val gate = CompletableDeferred<Unit>()
                client.gate = gate
                vm.refresh()
                val refreshing = awaitUntil { it.isRefreshing }
                assertEquals(LobbyPhase.Content, refreshing.phase)
                assertEquals(listOf(duel), refreshing.tables) // list retained under the spinner

                gate.complete(Unit)
                awaitUntil { it.phase == LobbyPhase.Content && !it.isRefreshing }
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun emptyLobbyMapsToEmptyContent() =
        runTest {
            val vm = LobbyViewModel(repository(FakeLobbyClient(), MutableStateFlow(ConnectionState.Connected)))

            vm.uiState.test {
                vm.refresh()
                val content = awaitUntil { it.phase == LobbyPhase.Content }
                assertTrue(content.isEmpty)
                assertEquals(0, content.totalTables)
                assertEquals("Showing 0 of 0", content.countSummary)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun failedFetchSurfacesAsErrorWithMessage() =
        runTest {
            val client = FakeLobbyClient(error = IllegalStateException("no active session"))
            val vm = LobbyViewModel(repository(client, MutableStateFlow(ConnectionState.Connected)))

            vm.uiState.test {
                vm.refresh()
                val error = awaitUntil { it.phase == LobbyPhase.Error }
                assertEquals("no active session", error.errorMessage)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun disconnectedSnapshotMapsToDisconnectedPhase() =
        runTest {
            val connection = MutableStateFlow(ConnectionState.Connected)
            val client = FakeLobbyClient(tables = listOf(duel), roomUsers = listOf(user), gameTypes = listOf(gameType))
            val vm = LobbyViewModel(repository(client, connection))

            vm.uiState.test {
                vm.refresh()
                awaitUntil { it.phase == LobbyPhase.Content }

                connection.value = ConnectionState.Disconnected
                val idle = awaitUntil { it.phase == LobbyPhase.Disconnected }
                assertTrue(idle.tables.isEmpty())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun hidePasswordedAndHideFullNarrowTheListWithCounts() =
        runTest {
            val client = FakeLobbyClient(tables = listOf(duel, draft, locked))
            val vm = LobbyViewModel(repository(client, MutableStateFlow(ConnectionState.Connected)))

            vm.uiState.test {
                vm.refresh()
                awaitUntil { it.phase == LobbyPhase.Content && it.totalTables == 3 }

                vm.setHidePassworded(true)
                val noPass = awaitUntil { it.filter.hidePassworded && it.tables.none { t -> t.isPassworded } }
                assertEquals(2, noPass.shownTables)
                assertEquals(3, noPass.totalTables)
                assertEquals("Showing 2 of 3", noPass.countSummary)

                vm.setHideFull(true)
                val noFull = awaitUntil { it.filter.hideFull && it.tables.none { t -> t.seatsFilled >= t.seatsTotal } }
                assertEquals(1, noFull.shownTables) // draft is full, locked is passworded → only duel
                assertEquals(listOf("t-duel"), noFull.tables.map { it.id })

                vm.resetFilters()
                val reset = awaitUntil { !it.filter.hasActiveFilters }
                assertEquals(3, reset.shownTables)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun gameTypeFilterKeepsOnlyMatchingTables() =
        runTest {
            val client = FakeLobbyClient(tables = listOf(duel, draft, locked))
            val vm = LobbyViewModel(repository(client, MutableStateFlow(ConnectionState.Connected)))

            vm.uiState.test {
                vm.refresh()
                awaitUntil { it.phase == LobbyPhase.Content && it.totalTables == 3 }

                vm.setGameTypeFilter("Two Player Duel")
                val duels = awaitUntil { it.filter.gameType == "Two Player Duel" }
                assertEquals(setOf("t-duel", "t-locked"), duels.tables.map { it.id }.toSet())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun sortOrdersTablesClientSide() =
        runTest {
            val client = FakeLobbyClient(tables = listOf(duel, draft, locked))
            val vm = LobbyViewModel(repository(client, MutableStateFlow(ConnectionState.Connected)))

            vm.uiState.test {
                vm.refresh()
                // Default sort is NewestFirst (createdAt desc): duel(3), draft(2), locked(1).
                val newest = awaitUntil { it.phase == LobbyPhase.Content && it.totalTables == 3 }
                assertEquals(listOf("t-duel", "t-draft", "t-locked"), newest.tables.map { it.id })

                vm.setSort(LobbySort.OldestFirst)
                val oldest = awaitUntil { it.filter.sort == LobbySort.OldestFirst }
                assertEquals(listOf("t-locked", "t-draft", "t-duel"), oldest.tables.map { it.id })

                vm.setSort(LobbySort.MostPlayers)
                val players = awaitUntil { it.filter.sort == LobbySort.MostPlayers }
                assertEquals("t-draft", players.tables.first().id) // 8 seated

                vm.setSort(LobbySort.MostOpenSeats)
                val open = awaitUntil { it.filter.sort == LobbySort.MostOpenSeats }
                assertEquals("t-draft", open.tables.last().id) // full (0 free seats) sorts last
                cancelAndIgnoreRemainingEvents()
            }
        }

    private fun table(
        id: String,
        name: String,
        gameType: String,
        seatsFilled: Int,
        seatsTotal: Int,
        isRated: Boolean = true,
        isPassworded: Boolean = false,
        isLimited: Boolean = false,
        isTournament: Boolean = false,
        createdAt: Long,
    ): LobbyTable =
        LobbyTable(
            id = id,
            name = name,
            host = "host-$id",
            gameType = gameType,
            deckType = "Constructed",
            state = if (seatsFilled >= seatsTotal) TableState.Dueling else TableState.Waiting,
            seatsFilled = seatsFilled,
            seatsTotal = seatsTotal,
            isTournament = isTournament,
            isRated = isRated,
            isPassworded = isPassworded,
            isLimited = isLimited,
            skillLevel = SkillLevel.Casual,
            createdAtEpochMs = createdAt,
        )
}
