package magefree.network.fake

import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import magefree.network.game.GameActionFailure
import magefree.network.game.GameSnapshot
import magefree.network.game.GameState
import magefree.network.game.GameStateUnavailableFailure
import magefree.network.game.GameStateUnavailableReason
import magefree.network.game.ManaType
import magefree.network.game.PassPriorityScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keeps [FakeGameClient] honest against the production [magefree.network.game.DefaultGameClient] it
 * stands in for. A fake that behaves differently from production is a defect in the fake (see the
 * project's verification standards), and the property that matters most here is the one story 0044 found
 * missing in `FakeTableClient`: **the seed is emitted first**, synchronously, exactly as production does.
 * A fake that skipped it would manufacture a blank/loading state a real board never shows.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FakeGameClientTest {
    private val seed = GameState(gameId = "g-1")

    @Test
    fun observeGameEmitsTheSeedFirstThenScriptedStatesThenLiveOnes() =
        runTest {
            val fake = FakeGameClient()
            val dealt = seed.copy(turn = 1, hasSnapshot = true)
            fake.gameStates = listOf(dealt)

            fake.observeGame("g-1", seed).test {
                assertEquals("production emits the seed as the flow opens", seed, awaitItem())
                assertEquals(dealt, awaitItem())

                val advanced = dealt.copy(turn = 2)
                fake.emitGameState(advanced)
                assertEquals(advanced, awaitItem())

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun everyVerbIsRecordedWithTheArgumentAnAssertionWouldCareAbout() =
        runTest {
            val fake = FakeGameClient()

            fake.joinGame("g-1")
            fake.watchGame("g-1")
            fake.playObject("g-1", "card-7")
            fake.passPriority("g-1")
            fake.useSpecialAction("g-1")
            fake.chooseTarget("g-1", "t-3")
            fake.answerAsk("g-1", answer = true)
            fake.chooseAbility("g-1", "a-2")
            fake.choosePile("g-1", first = false)
            fake.chooseChoice("g-1", key = "green", special = true)
            fake.playManaSource("g-1", "land-1")
            fake.unlockMana("g-1", playerId = "p-1", manaType = ManaType.Green)
            fake.announceX("g-1", value = 3)
            fake.chooseAmount("g-1", amount = 2)
            fake.distributeAmounts("g-1", listOf(2, 0, 1))
            fake.cancelPrompt("g-1")
            fake.passPriorityUntil("g-1", PassPriorityScope.UntilNextTurn)
            fake.concede("g-1")
            fake.quitMatch("g-1")
            fake.stopWatching("g-1")

            assertEquals(
                listOf(
                    "join:g-1",
                    "watch:g-1",
                    "play:g-1:card-7",
                    "pass:g-1",
                    "special:g-1",
                    "target:g-1:t-3",
                    "ask:g-1:true",
                    "ability:g-1:a-2",
                    "pile:g-1:false",
                    "choice:g-1:green:true",
                    "mana:g-1:land-1",
                    "unlockMana:g-1:p-1:Green",
                    "x:g-1:3",
                    "amount:g-1:2",
                    "multiAmount:g-1:2,0,1",
                    "cancel:g-1",
                    "passUntil:g-1:UntilNextTurn",
                    "concede:g-1",
                    "quit:g-1",
                    "stopWatching:g-1",
                ),
                fake.calls,
            )
        }

    @Test
    fun refreshGameDefaultsToTheBridgesHonestNoStateRatherThanABlankBoard() =
        runTest {
            // A fake that behaves differently from production is a defect in the fake. The real bridge
            // answers a typed "no state" until it has relayed a snapshot, so an unscripted read here must
            // fail too — a default empty GameSnapshot would hand every downstream test an all-defaults
            // board production never produces, and hide exactly the defect standard 5 is about.
            val fake = FakeGameClient()

            val failure = fake.refreshGame("g-1").exceptionOrNull()

            assertTrue("expected a typed unavailability, got $failure", failure is GameStateUnavailableFailure)
            assertEquals(GameStateUnavailableReason.NoStateYet, (failure as GameStateUnavailableFailure).reason)
            assertEquals(listOf("refresh:g-1"), fake.calls)
        }

    @Test
    fun aScriptedSnapshotIsWhatRefreshGameReturns() =
        runTest {
            val fake = FakeGameClient()
            val held = GameState(gameId = "g-1", turn = 6, hasSnapshot = true)
            fake.refreshResult = Result.success(GameSnapshot(state = held, capturedAtEpochMs = 99L))

            val snapshot = fake.refreshGame("g-1").getOrThrow()

            assertEquals(held, snapshot.state)
            assertEquals(99L, snapshot.capturedAtEpochMs)
        }

    @Test
    fun aScriptedFailureLetsACallersErrorPathBeExercised() =
        runTest {
            val fake = FakeGameClient()
            fake.actionResultFor = { call -> if (call.startsWith("play:")) Result.failure(GameActionFailure("nope")) else null }

            assertTrue("the untargeted verb still succeeds", fake.passPriority("g-1").isSuccess)
            val failure = fake.playObject("g-1", "card-7").exceptionOrNull()
            assertTrue(failure is GameActionFailure)
            assertEquals("nope", (failure as GameActionFailure).reason)
        }
}
