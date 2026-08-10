package magefree.network

import app.cash.turbine.test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.plus
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import magefree.model.ConnectionState
import magefree.model.GameType
import magefree.model.LobbyLoadState
import magefree.model.LobbyTable
import magefree.model.RoomUser
import magefree.model.SkillLevel
import magefree.model.TableState
import magefree.network.fake.FakeLobbyClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Turbine coverage of [LobbyRepository]'s observable, refreshable snapshot over a [FakeLobbyClient]
 * (story 0028): load→loaded, refresh (Refreshing over prior data), an empty-but-valid load, the
 * error-as-state path (never thrown), and disconnected→idle gating. No bridge, no `:protocol`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LobbyRepositoryTest {
    private val table =
        LobbyTable(
            id = "t-1",
            name = "Pete's Table",
            host = "pete",
            gameType = "Two Player Duel",
            deckType = "Constructed",
            state = TableState.Waiting,
            seatsFilled = 1,
            seatsTotal = 2,
            isTournament = false,
            isRated = true,
            isPassworded = false,
            isLimited = false,
            skillLevel = SkillLevel.Casual,
            createdAtEpochMs = 1L,
        )
    private val user = RoomUser("pete", "US", "W:1", "idle", "10ms", 1500)
    private val gameType = GameType("Two Player Duel", 2, 2)

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

    @Test
    fun refreshWhileConnectedGoesLoadingThenLoadedWithData() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val client =
                FakeLobbyClient(
                    tables = listOf(table),
                    roomUsers = listOf(user),
                    gameTypes = listOf(gameType),
                    gate = gate,
                )
            val repository = repository(client, MutableStateFlow(ConnectionState.Connected))

            repository.snapshot.test {
                assertEquals(LobbyLoadState.Idle, awaitItem().status)

                repository.refresh()
                assertEquals(LobbyLoadState.Loading, awaitItem().status)

                gate.complete(Unit)
                val loaded = awaitItem()
                assertEquals(LobbyLoadState.Loaded, loaded.status)
                assertEquals(listOf(table), loaded.tables)
                assertEquals(listOf(user), loaded.roomUsers)
                assertEquals(listOf(gameType), loaded.gameTypes)
                assertEquals(null, loaded.error)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun aSecondRefreshOverLoadedDataReportsRefreshing() =
        runTest {
            val client = FakeLobbyClient(tables = listOf(table))
            val repository = repository(client, MutableStateFlow(ConnectionState.Connected))

            repository.snapshot.test {
                assertEquals(LobbyLoadState.Idle, awaitItem().status)

                // First refresh (no gate) resolves straight through Loading to Loaded.
                repository.refresh()
                assertEquals(LobbyLoadState.Loaded, expectMostRecentItem().status)

                // Second refresh over already-loaded data surfaces Refreshing (keeps the old list).
                val gate = CompletableDeferred<Unit>()
                client.gate = gate
                repository.refresh()
                val refreshing = awaitItem()
                assertEquals(LobbyLoadState.Refreshing, refreshing.status)
                assertEquals(listOf(table), refreshing.tables)

                gate.complete(Unit)
                assertEquals(LobbyLoadState.Loaded, awaitItem().status)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun anEmptyLobbyLoadsAsAValidEmptyLoadedSnapshot() =
        runTest {
            val repository = repository(FakeLobbyClient(), MutableStateFlow(ConnectionState.Connected))

            repository.snapshot.test {
                assertEquals(LobbyLoadState.Idle, awaitItem().status)
                repository.refresh()
                // Loading then Loaded; the fetches resolve at once so assert the terminal state.
                val terminal = expectMostRecentItem()
                assertEquals(LobbyLoadState.Loaded, terminal.status)
                assertTrue(terminal.tables.isEmpty())
                assertTrue(terminal.roomUsers.isEmpty())
                assertTrue(terminal.gameTypes.isEmpty())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun aFailedFetchSurfacesAsErrorStateNeverThrown() =
        runTest {
            val client = FakeLobbyClient(error = IllegalStateException("no active session"))
            val repository = repository(client, MutableStateFlow(ConnectionState.Connected))

            repository.snapshot.test {
                assertEquals(LobbyLoadState.Idle, awaitItem().status)
                repository.refresh()
                val errored = expectMostRecentItem()
                assertEquals(LobbyLoadState.Error, errored.status)
                assertEquals("no active session", errored.error)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun disconnectingResetsToTheIdleEmptySnapshot() =
        runTest {
            val connection = MutableStateFlow(ConnectionState.Connected)
            val client = FakeLobbyClient(tables = listOf(table), roomUsers = listOf(user), gameTypes = listOf(gameType))
            val repository = repository(client, connection)

            repository.snapshot.test {
                assertEquals(LobbyLoadState.Idle, awaitItem().status)
                repository.refresh()
                assertEquals(LobbyLoadState.Loaded, expectMostRecentItem().status)

                // Losing the connection returns the lobby to idle/empty — it is meaningful only connected.
                connection.value = ConnectionState.Disconnected
                val idle = awaitItem()
                assertEquals(LobbyLoadState.Idle, idle.status)
                assertTrue(idle.tables.isEmpty())
                assertTrue(idle.gameTypes.isEmpty())
                cancelAndIgnoreRemainingEvents()
            }
        }

    /**
     * The refresh/disconnect interleaving (story 0044): the socket drops **while a refresh is running**,
     * not before it starts. The drop is raised from inside the last fetch, so the connection collector
     * runs (unconfined resumes it inline in the `value =` assignment) at the one moment the refresh is
     * mid-flight — the window in which the repository used to hold no usable handle on the in-flight job
     * and its terminal write could land *after* the disconnect reset, leaving a populated table list on a
     * disconnected lobby.
     */
    @Test
    fun aRefreshCompletingAfterADisconnectCannotResurrectTheLobby() =
        runTest {
            val connection = MutableStateFlow(ConnectionState.Connected)
            val unconfined = UnconfinedTestDispatcher(testScheduler)
            val client =
                DroppingLobbyClient(
                    tables = listOf(table),
                    roomUsers = listOf(user),
                    gameTypes = listOf(gameType),
                    onGameTypes = { connection.value = ConnectionState.Disconnected },
                )
            val repository =
                LobbyRepository(
                    lobbyClient = client,
                    connectionState = connection,
                    dispatcher = unconfined,
                    // The connection collector must run inline on the drop, so it interleaves with the
                    // refresh rather than being queued behind it.
                    scope = backgroundScope + unconfined,
                )

            repository.refresh()
            advanceUntilIdle()

            val settled = repository.snapshot.value
            assertEquals(ConnectionState.Disconnected, connection.value)
            assertEquals(LobbyLoadState.Idle, settled.status)
            assertTrue("tables=${settled.tables}", settled.tables.isEmpty())
            assertTrue(settled.roomUsers.isEmpty())
            assertTrue(settled.gameTypes.isEmpty())
        }

    @Test
    fun refreshWhileDisconnectedStaysIdle() =
        runTest {
            val repository =
                repository(
                    FakeLobbyClient(tables = listOf(table)),
                    MutableStateFlow(ConnectionState.Disconnected),
                )

            repository.snapshot.test {
                assertEquals(LobbyLoadState.Idle, awaitItem().status)
                repository.refresh()
                expectNoEvents()
                assertEquals(LobbyLoadState.Idle, repository.snapshot.value.status)
                cancelAndIgnoreRemainingEvents()
            }
        }
}

/**
 * A [LobbyClient] whose last fetch raises a connection drop ([onGameTypes]) *from inside the refresh*,
 * then [yields][yield] so the repository's connection collector actually runs before the fetch returns.
 * That is the interleaving under test — a refresh already past its fetches when the socket drops —
 * and it is what [FakeLobbyClient]'s gate cannot express, since a gate only suspends *before* a fetch
 * begins. The yield stands in for the real thing: production runs the collector on another thread while
 * the refresh sits in a socket read.
 */
private class DroppingLobbyClient(
    private val tables: List<LobbyTable>,
    private val roomUsers: List<RoomUser>,
    private val gameTypes: List<GameType>,
    private val onGameTypes: () -> Unit,
) : LobbyClient {
    override suspend fun tables(): List<LobbyTable> = tables

    override suspend fun roomUsers(): List<RoomUser> = roomUsers

    override suspend fun gameTypes(): List<GameType> {
        onGameTypes()
        yield()
        return gameTypes
    }
}
