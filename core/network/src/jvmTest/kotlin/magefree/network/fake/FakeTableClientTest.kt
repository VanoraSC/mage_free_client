package magefree.network.fake

import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import magefree.network.table.TableServerState
import magefree.network.table.TableState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Fake-fidelity coverage of [FakeTableClient.observeTable] (story 0044). The fake used to drop the
 * `seed` its production counterpart emits first, so every test built on it saw a loading/empty room
 * production never shows — and a regression in production's seed emission would have gone unnoticed.
 * These pin the emission order the fake must keep matching
 * [magefree.network.table.DefaultTableClient.observeTable] (covered by `ObserveTableTest`, which
 * asserts the same `seed`-first opening against the real client).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FakeTableClientTest {
    private val seed = TableState(tableId = "t-1", optionsSummary = "Two Player Duel")

    @Test
    fun observeTableEmitsTheSeedFirst() =
        runTest {
            val client = FakeTableClient()

            client.observeTable("t-1", seed).test {
                assertEquals(seed, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun theSeedPrecedesTheScriptedStatesAndThenLiveEmissions() =
        runTest {
            val scripted = seed.copy(serverState = TableServerState.ReadyToStart)
            val live = seed.copy(serverState = TableServerState.Dueling)
            val client = FakeTableClient().apply { tableStates = listOf(scripted) }

            client.observeTable("t-1", seed).test {
                assertEquals(seed, awaitItem())
                assertEquals(scripted, awaitItem())

                client.emitTableState(live)
                assertEquals(live, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }
}
