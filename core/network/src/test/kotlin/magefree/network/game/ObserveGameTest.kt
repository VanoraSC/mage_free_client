package magefree.network.game

import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import magefree.model.ConnectionState
import magefree.network.fake.FakeBridgeClient
import magefree.protocol.AskPrompt
import magefree.protocol.GameCardView
import magefree.protocol.GameOver
import magefree.protocol.GamePlayableObject
import magefree.protocol.GamePlayerView
import magefree.protocol.GamePrompted
import magefree.protocol.GameStarted
import magefree.protocol.GameStateUpdated
import magefree.protocol.GameStateView
import magefree.protocol.PhaseStepCode
import magefree.protocol.SelectPrompt
import magefree.protocol.TurnPhaseCode
import magefree.protocol.WatchingGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hermetic Turbine coverage of [DefaultGameClient.observeGame]: it seeds current state, folds the 0051
 * game events pushed through the [FakeBridgeClient]'s server-push side-channel into successive
 * [GameState]s (start → hand → a prompt appears → the prompt clears → game over), and re-emits the held
 * state on a 0023/0024 resume so a reconnect does not strand the board. No socket.
 *
 * Note what is deliberately **absent** compared with `ObserveTableTest`: there is no read-on-open, no
 * read-after-resume and no while-observed poll, because upstream exposes no verb that reads a `GameView`.
 * Game state is push-only. `observeGameNeverIssuesARequestOfItsOwn` pins that, so a later change cannot
 * quietly add a poll against a request the bridge does not have.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ObserveGameTest {
    private val seed = GameState(gameId = GAME)

    private fun clientOver(
        fake: FakeBridgeClient,
        connection: MutableStateFlow<ConnectionState> = MutableStateFlow(ConnectionState.Connected),
    ) = DefaultGameClient(bridgeClient = fake, pushSource = fake, connectionState = connection)

    @Test
    fun observeGameSeedsThenFoldsAWholeGameIntoStateTransitions() =
        runTest {
            val fake = FakeBridgeClient()
            val client = clientOver(fake)

            client.observeGame(GAME, seed).test {
                assertEquals("the seed is emitted synchronously as the flow opens", seed, awaitItem())

                fake.emitPush(GameStarted(gameId = GAME, state = view(turn = 1)))
                val started = awaitItem()
                assertTrue(started.hasSnapshot)
                assertEquals(1, started.turn)

                fake.emitPush(GameStateUpdated(gameId = GAME, state = view(turn = 1, hand = 7)))
                assertEquals(7, awaitItem().hand.size)

                fake.emitPush(
                    GamePrompted(
                        gameId = GAME,
                        state = view(turn = 1, hand = 7, viewerHasPriority = true, playable = listOf("c-0")),
                        prompt = SelectPrompt(message = "Play a land"),
                    ),
                )
                val prompted = awaitItem()
                assertEquals(GamePrompt.Select("Play a land"), prompted.prompt)
                assertTrue(prompted.viewerHasPriority)
                assertTrue(prompted.isPlayable("c-0"))

                fake.emitPush(
                    GameStateUpdated(
                        gameId = GAME,
                        state = view(turn = 2, phase = TurnPhaseCode.END, step = PhaseStepCode.END_TURN, hand = 6),
                    ),
                )
                val advanced = awaitItem()
                assertNull("the game moved on, so the prompt is finished with", advanced.prompt)
                assertEquals(2, advanced.turn)
                assertEquals(TurnPhase.End, advanced.phase)
                assertFalse("the new snapshot carries no priority for us", advanced.viewerHasPriority)
                assertTrue("and nothing playable", advanced.playable.isEmpty())

                fake.emitPush(GameOver(gameId = GAME, message = "pete has won the game", state = view(turn = 2)))
                val over = awaitItem()
                assertTrue(over.isOver)
                assertEquals("pete has won the game", over.result?.message)

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun aPushForAnotherGameDoesNotEmit() =
        runTest {
            val fake = FakeBridgeClient()

            clientOver(fake).observeGame(GAME, seed).test {
                assertEquals(seed, awaitItem())
                fake.emitPush(GameStateUpdated(gameId = "other", state = view(turn = 9)))
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun aPushThatChangesNothingDoesNotEmitAgain() =
        runTest {
            // Snapshot replace means an identical snapshot yields an identical state; re-emitting it
            // would make every consumer recompose for nothing.
            val fake = FakeBridgeClient()

            clientOver(fake).observeGame(GAME, seed).test {
                assertEquals(seed, awaitItem())

                fake.emitPush(GameStateUpdated(gameId = GAME, state = view(turn = 1)))
                val first = awaitItem()

                fake.emitPush(GameStateUpdated(gameId = GAME, state = view(turn = 1)))
                expectNoEvents()
                assertEquals(1, first.turn)

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun aResumeReEmitsTheCurrentStateSoTheBoardIsNotStranded() =
        runTest {
            val fake = FakeBridgeClient()
            val connection = MutableStateFlow(ConnectionState.Connected)

            clientOver(fake, connection).observeGame(GAME, seed).test {
                assertEquals(seed, awaitItem())

                fake.emitPush(GamePrompted(gameId = GAME, state = view(turn = 3), prompt = AskPrompt("Mulligan?")))
                val asked = awaitItem()
                assertEquals(GamePrompt.Ask("Mulligan?"), asked.prompt)

                // A drop and a 0023/0024 resume; step through Reconnecting so the StateFlow does not
                // conflate the intermediate away, then return to Connected.
                connection.value = ConnectionState.Reconnecting
                testScheduler.runCurrent()
                connection.value = ConnectionState.Connected
                testScheduler.runCurrent()

                assertEquals("the held state is re-emitted, prompt and all", asked, awaitItem())

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun aResumeIsFollowedByWhateverTheBridgeBufferedSoTheBoardCatchesUp() =
        runTest {
            // There is no game read to issue after a resume; the fresh snapshot arrives as the bridge's
            // parked-session buffer drains into the re-bound socket (story 0023). This asserts the
            // client's half of that: a push after the resume folds normally onto the re-synced state.
            val fake = FakeBridgeClient()
            val connection = MutableStateFlow(ConnectionState.Connected)

            clientOver(fake, connection).observeGame(GAME, seed).test {
                assertEquals(seed, awaitItem())

                fake.emitPush(GameStateUpdated(gameId = GAME, state = view(turn = 1)))
                val before = awaitItem()

                connection.value = ConnectionState.Reconnecting
                testScheduler.runCurrent()
                connection.value = ConnectionState.Connected
                testScheduler.runCurrent()
                assertEquals(before, awaitItem())

                fake.emitPush(GameStateUpdated(gameId = GAME, state = view(turn = 5)))
                assertEquals("a snapshot missed during the gap simply replaces state when it lands", 5, awaitItem().turn)

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun theFirstConnectedDoesNotCountAsAResume() =
        runTest {
            // The seed already covers the current state; only a *return* to Connected re-syncs. Emitting
            // on the initial value would duplicate the seed for every collector.
            val fake = FakeBridgeClient()
            val connection = MutableStateFlow(ConnectionState.Connected)

            clientOver(fake, connection).observeGame(GAME, seed).test {
                assertEquals(seed, awaitItem())
                testScheduler.runCurrent()
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun observeGameNeverIssuesARequestOfItsOwn() =
        runTest {
            // Game state is push-only: there is no upstream verb that reads a GameView, so an observer
            // that issued a request would be sending something the bridge cannot answer. The fake's
            // default responder throws, so any request at all fails this.
            val fake = FakeBridgeClient()

            clientOver(fake).observeGame(GAME, seed).test {
                assertEquals(seed, awaitItem())
                testScheduler.advanceTimeBy(10 * 60_000L)
                testScheduler.runCurrent()
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun aSpectateConfirmationFoldsWithoutAnySnapshot() =
        runTest {
            val fake = FakeBridgeClient()

            clientOver(fake).observeGame(GAME, seed).test {
                assertEquals(seed, awaitItem())

                fake.emitPush(WatchingGame(gameId = GAME, tableId = "t-1"))
                val watching = awaitItem()
                assertTrue(watching.isWatching)
                assertFalse("WATCHGAME carries no state", watching.hasSnapshot)

                fake.emitPush(GameStateUpdated(gameId = GAME, state = view(turn = 1, viewer = null)))
                val snapshot = awaitItem()
                assertTrue(snapshot.isSpectator)
                assertTrue(snapshot.hand.isEmpty())

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun aCallerMaySeedWithAStateItAlreadyHolds() =
        runTest {
            // Re-opening a board should not blank it: the seed is emitted as-is before any push folds.
            val fake = FakeBridgeClient()
            val held = GameState(gameId = GAME, turn = 7, hasSnapshot = true)

            clientOver(fake).observeGame(GAME, held).test {
                assertEquals(held, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    // --- fixtures ------------------------------------------------------------------------------------

    private fun view(
        turn: Int = 1,
        phase: TurnPhaseCode = TurnPhaseCode.PRECOMBAT_MAIN,
        step: PhaseStepCode = PhaseStepCode.PRECOMBAT_MAIN,
        hand: Int = 0,
        viewer: String? = "p-1",
        viewerHasPriority: Boolean = false,
        playable: List<String> = emptyList(),
    ) = GameStateView(
        turn = turn,
        phase = phase,
        step = step,
        activePlayerId = "p-1",
        activePlayerName = "pete",
        viewerPlayerId = viewer,
        viewerHasPriority = viewerHasPriority,
        players =
            listOf(
                GamePlayerView(playerId = "p-1", name = "pete", life = 20, viewer = viewer != null, active = true),
                GamePlayerView(playerId = "p-2", name = "Computer", life = 20, human = false),
            ),
        hand = (0 until hand).map { GameCardView(id = "c-$it", name = "Forest", setCode = "M21", collectorNumber = "272") },
        playable = playable.map { GamePlayableObject(objectId = it, abilityIds = listOf("a-$it")) },
    )

    private companion object {
        const val GAME = "g-1"
    }
}
