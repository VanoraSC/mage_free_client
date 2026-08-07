package magefree.network.table

import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import magefree.model.ConnectionState
import magefree.network.fake.FakeBridgeClient
import magefree.protocol.ConstructPrompt
import magefree.protocol.SeatUpdated
import magefree.protocol.TableUpdated
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import magefree.protocol.MatchStarting as MatchStartingMessage

/**
 * Hermetic Turbine coverage of [DefaultTableClient.observeTable]: it seeds current state, folds the 0036
 * events pushed through the [FakeBridgeClient]'s server-push side-channel into successive [TableState]s
 * (join → seat update → construct → match-starting), and re-emits the held state on a 0023 resume (a
 * return to [ConnectionState.Connected]) so a reconnect does not strand the seat. No socket.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ObserveTableTest {
    private val seed = TableState(tableId = "t-1", optionsSummary = "Two Player Duel")

    @Test
    fun observeTableSeedsThenFoldsAScriptedLifecycleIntoStateTransitions() =
        runTest {
            val fake = FakeBridgeClient()
            val client =
                DefaultTableClient(
                    bridgeClient = fake,
                    pushSource = fake,
                    connectionState = MutableStateFlow(ConnectionState.Connected),
                )

            client.observeTable("t-1", seed).test {
                assertEquals(seed, awaitItem())

                fake.emitPush(TableUpdated(tableId = "t-1", isOwner = true))
                assertTrue(awaitItem().isOwner)

                fake.emitPush(SeatUpdated(tableId = "t-1", playerId = "p-2"))
                assertEquals(listOf("p-2"), awaitItem().seats.map { it.playerId })

                fake.emitPush(ConstructPrompt(tableId = "t-1"))
                assertEquals(TablePhase.Constructing, awaitItem().phase)

                fake.emitPush(MatchStartingMessage(gameId = "g-9", tableId = "t-1", playerId = "p-1"))
                val starting = awaitItem()
                assertEquals(TablePhase.Starting, starting.phase)
                assertEquals("g-9", starting.matchStarting?.gameId)

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun anEventForAnotherTableDoesNotEmit() =
        runTest {
            val fake = FakeBridgeClient()
            val client =
                DefaultTableClient(
                    bridgeClient = fake,
                    pushSource = fake,
                    connectionState = MutableStateFlow(ConnectionState.Connected),
                )

            client.observeTable("t-1", seed).test {
                assertEquals(seed, awaitItem())
                fake.emitPush(ConstructPrompt(tableId = "other"))
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun aResumeReEmitsTheCurrentStateSoTheSeatIsNotStranded() =
        runTest {
            val fake = FakeBridgeClient()
            val connection = MutableStateFlow(ConnectionState.Connected)
            val scheduler = testScheduler
            val client =
                DefaultTableClient(
                    bridgeClient = fake,
                    pushSource = fake,
                    connectionState = connection,
                )

            client.observeTable("t-1", seed).test {
                assertEquals(seed, awaitItem())

                // Advance the table into Constructing.
                fake.emitPush(ConstructPrompt(tableId = "t-1"))
                val constructing = awaitItem()
                assertEquals(TablePhase.Constructing, constructing.phase)

                // Simulate a drop and a 0023 resume; step through Reconnecting so the StateFlow does not
                // conflate the intermediate away, then return to Connected.
                connection.value = ConnectionState.Reconnecting
                scheduler.runCurrent()
                connection.value = ConnectionState.Connected
                scheduler.runCurrent()

                // The held state is re-emitted — the seat re-syncs rather than stranding at reconnect.
                assertEquals(constructing, awaitItem())

                cancelAndIgnoreRemainingEvents()
            }
        }
}
