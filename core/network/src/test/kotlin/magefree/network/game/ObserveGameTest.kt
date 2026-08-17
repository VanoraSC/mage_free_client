package magefree.network.game

import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import magefree.model.ConnectionState
import magefree.network.fake.FakeBridgeClient
import magefree.protocol.AskPrompt
import magefree.protocol.ClientMessage
import magefree.protocol.GameCardView
import magefree.protocol.GameOver
import magefree.protocol.GamePlayableObject
import magefree.protocol.GamePlayerView
import magefree.protocol.GamePrompted
import magefree.protocol.GameStarted
import magefree.protocol.GameStateSnapshot
import magefree.protocol.GameStateUnavailable
import magefree.protocol.GameStateUnavailableCode
import magefree.protocol.GameStateUpdated
import magefree.protocol.GameStateView
import magefree.protocol.GetGameState
import magefree.protocol.PhaseStepCode
import magefree.protocol.SelectPrompt
import magefree.protocol.ServerMessage
import magefree.protocol.TurnPhaseCode
import magefree.protocol.WatchingGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import magefree.protocol.GamePrompt as GamePromptMessage

/**
 * Hermetic Turbine coverage of [DefaultGameClient.observeGame]: it seeds current state, folds the 0051
 * game events pushed through the [FakeBridgeClient]'s server-push side-channel into successive
 * [GameState]s (start → hand → a prompt appears → the prompt clears → game over), re-emits the held
 * state on a 0023/0024 resume so a reconnect does not strand the board, and — since story 0054 — **reads**
 * the board from the bridge on open and after each resume. No socket.
 *
 * **The invariant that changed, and why it was there.** Story 0052 pinned
 * `observeGameNeverIssuesARequestOfItsOwn`: game state was push-only, because upstream exposes no verb
 * that reads a `GameView`, so anything the observer sent would have been a request the bridge could not
 * answer — and a *poll* of it would have been an unbounded stream of unanswerable requests. Story 0054
 * gives the bridge an answer (from its own per-session cache), so the read is now deliberate. The test
 * is therefore **updated rather than deleted**, to
 * [observeGameReadsOnOpenAndAfterEachResumeAndStillNeverPolls]: it now asserts *exactly* the two reads
 * that are intended, and — the half of the original constraint that still stands — that no timer ever
 * adds a third.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ObserveGameTest {
    private val seed = GameState(gameId = GAME)

    private fun clientOver(
        fake: FakeBridgeClient,
        connection: MutableStateFlow<ConnectionState> = MutableStateFlow(ConnectionState.Connected),
    ) = DefaultGameClient(bridgeClient = fake, pushSource = fake, connectionState = connection)

    /**
     * A scripted responder for the 0054 read that records every request `observeGame` issues.
     *
     * It **fails loudly on anything that is not a `GetGameState`** — the surviving half of the original
     * never-reads constraint. An observer is allowed exactly one kind of request now, and a test that
     * merely counted requests would not notice a different one appearing.
     */
    private class Reads(
        private val replies: Iterator<ServerMessage>,
    ) : (ClientMessage) -> ServerMessage {
        constructor(vararg replies: ServerMessage) : this(replies.toList().iterator())

        val requests: MutableList<GetGameState> = mutableListOf()

        override fun invoke(message: ClientMessage): ServerMessage {
            val read = message as? GetGameState ?: error("observeGame must issue no request but the game read; got $message")
            requests += read
            return if (replies.hasNext()) replies.next() else noState(read.gameId, read.requestId)
        }
    }

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
    fun aRepeatedPromptStillEmitsSoARefusedAnswerCannotDeadlockTheGame() =
        runTest {
            // The exception to the rule above, and the reason it exists: when the server refuses an
            // answer it re-asks with the *same* view, so the folded state is byte-identical while a real,
            // new question is outstanding. Swallowing that emission leaves a consumer waiting for an
            // event that never comes while the server waits for an answer.
            val fake = FakeBridgeClient()
            val prompt = GamePrompted(gameId = GAME, state = view(turn = 1), prompt = AskPrompt("Mulligan?"))

            clientOver(fake).observeGame(GAME, seed).test {
                assertEquals(seed, awaitItem())

                fake.emitPush(prompt)
                assertEquals(GamePrompt.Ask("Mulligan?"), awaitItem().prompt)

                fake.emitPush(prompt)
                assertEquals("a re-ask must reach the consumer", GamePrompt.Ask("Mulligan?"), awaitItem().prompt)

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
    fun observeGameReadsOnOpenAndAfterEachResumeAndStillNeverPolls() =
        runTest {
            // **The updated 0052 invariant.** It used to read "never issues a request of its own",
            // because there was no request the bridge could answer and a poll of one would have been an
            // unbounded stream of unanswerable requests. Story 0054 gives the bridge an answer, so the
            // read is now intended — but only at the two moments where the answer can have changed
            // without the app hearing about it: the board opening, and the socket coming back. Nothing
            // else changes it, so a timer would ask forever for no new answer. This asserts both halves:
            // the reads that must happen, and the poll that must not.
            val reads = Reads()
            val fake = FakeBridgeClient(responder = reads)
            val connection = MutableStateFlow(ConnectionState.Connected)

            clientOver(fake, connection).observeGame(GAME, seed).test {
                assertEquals(seed, awaitItem())
                testScheduler.runCurrent()
                assertEquals("the board is read once as it opens", 1, reads.requests.size)
                assertEquals(GAME, reads.requests.single().gameId)

                testScheduler.advanceTimeBy(10 * 60_000L)
                testScheduler.runCurrent()
                assertEquals("ten minutes of observing must add no read at all — there is no poll", 1, reads.requests.size)

                connection.value = ConnectionState.Reconnecting
                testScheduler.runCurrent()
                connection.value = ConnectionState.Connected
                testScheduler.runCurrent()
                assertEquals("a resume reads again: the board may have moved while we were away", 2, reads.requests.size)

                testScheduler.advanceTimeBy(10 * 60_000L)
                testScheduler.runCurrent()
                assertEquals("and still no poll after the resume", 2, reads.requests.size)

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun observeGameShowsTheBridgesHeldBoardOnOpenWithoutWaitingForAPush() =
        runTest {
            // Re-opening a running game used to show nothing until the server happened to push — which,
            // while an opponent thinks, can be minutes or never. The read fixes that.
            val reads = Reads(snapshot(view(turn = 4, hand = 7)))
            val fake = FakeBridgeClient(responder = reads)

            clientOver(fake).observeGame(GAME, seed).test {
                assertEquals(seed, awaitItem())

                val read = awaitItem()
                assertTrue("the read fills the board with no push involved", read.hasSnapshot)
                assertEquals(4, read.turn)
                assertEquals(7, read.hand.size)
                // Story 0074: a read *can* restore a cached prompt now — this fixture's snapshot simply
                // did not carry one, so null is the right answer here, not an absolute rule.
                assertNull("this snapshot cached no outstanding prompt for the session", read.prompt)

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun aResumeReadsTheBoardSoAReconnectDoesNotWaitForTheOpponentToAct() =
        runTest {
            // **The scenario the story exists for.** The socket drops mid-game; while it is down the
            // game moves on; the app reconnects and the opponent is thinking, so nothing is pushed. The
            // post-resume read is the only thing that can put a board on screen — this asserts a fresh
            // state arrives with no push at all after the reconnect.
            val reads = Reads(snapshot(view(turn = 1, hand = 7)), snapshot(view(turn = 9, hand = 5)))
            val fake = FakeBridgeClient(responder = reads)
            val connection = MutableStateFlow(ConnectionState.Connected)

            clientOver(fake, connection).observeGame(GAME, seed).test {
                assertEquals(seed, awaitItem())
                assertEquals(1, awaitItem().turn)

                connection.value = ConnectionState.Reconnecting
                testScheduler.runCurrent()
                connection.value = ConnectionState.Connected
                testScheduler.runCurrent()

                assertEquals("the resume re-emits the held state first (0037's non-destructive re-sync)", 1, awaitItem().turn)
                val afterReconnect = awaitItem()
                assertEquals("and then the read lands, with no push in between", 9, afterReconnect.turn)
                assertEquals(5, afterReconnect.hand.size)

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun aNoStateReplyLeavesTheHeldBoardExactlyAsItWas() =
        runTest {
            // The typed "no state" must never be rendered as a board. Here the bridge has nothing (the
            // join has not produced its GAME_INIT yet), and the observer must emit nothing rather than
            // an all-defaults state a consumer could not tell from a real one.
            val reads = Reads(noState(GAME, "r-1"), noState(GAME, "r-2"))
            val fake = FakeBridgeClient(responder = reads)
            val held = GameState(gameId = GAME, turn = 7, hasSnapshot = true)

            clientOver(fake).observeGame(GAME, held).test {
                assertEquals(held, awaitItem())
                testScheduler.advanceTimeBy(1_000L)
                testScheduler.runCurrent()
                expectNoEvents()
                assertEquals(1, reads.requests.size)

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun aReadNeverOverwritesTheOutstandingPromptOrARunningGamesResult() =
        runTest {
            // Story 0074: a read *can* restore a cached prompt now (see the dedicated test for that),
            // but it must never *clear* one already held just because this particular reply's own
            // snapshot happened to cache none — that would leave the server waiting forever for an
            // answer the UI no longer offers. This fixture's snapshot() call below carries no prompt on
            // purpose, to prove exactly that half.
            val reads = Reads(noState(GAME, "r-1"), snapshot(view(turn = 5)))
            val fake = FakeBridgeClient(responder = reads)
            val connection = MutableStateFlow(ConnectionState.Connected)

            clientOver(fake, connection).observeGame(GAME, seed).test {
                assertEquals(seed, awaitItem())

                fake.emitPush(GamePrompted(gameId = GAME, state = view(turn = 4), prompt = AskPrompt("Mulligan?")))
                assertEquals(GamePrompt.Ask("Mulligan?"), awaitItem().prompt)

                connection.value = ConnectionState.Reconnecting
                testScheduler.runCurrent()
                connection.value = ConnectionState.Connected
                testScheduler.runCurrent()
                awaitItem() // the non-destructive re-sync

                val afterRead = awaitItem()
                assertEquals(5, afterRead.turn)
                assertEquals(
                    "the question the server is still waiting on must survive a read",
                    GamePrompt.Ask("Mulligan?"),
                    afterRead.prompt,
                )

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun aReadRestoresACachedPromptWhenNoneIsCurrentlyHeld() =
        runTest {
            // The exact scenario story 0074 exists for: the client left mid-question (a mulligan, say),
            // so it holds no live prompt (viewerHasPriority is false pre-priority, and 0071's fallback
            // correctly does not apply here either). The bridge, however, cached the last GamePrompted
            // it relayed for this session, and now serves it back on the opening read.
            val reads = Reads(snapshot(view(turn = 1, hand = 7), prompt = AskPrompt("Mulligan?")))
            val fake = FakeBridgeClient(responder = reads)

            clientOver(fake).observeGame(GAME, seed).test {
                assertEquals(seed, awaitItem())

                val read = awaitItem()
                assertEquals(
                    "the read restores the outstanding question, not just the board",
                    GamePrompt.Ask("Mulligan?"),
                    read.prompt,
                )

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun aReadAfterTheGameEndedDoesNotResurrectTheBoard() =
        runTest {
            // A finished game is finished. Applying a board to it would show a live game that no longer
            // exists — the bridge drops its cache at GAME_OVER, but a late reply must be inert too.
            val reads = Reads(noState(GAME, "r-1"), snapshot(view(turn = 12)))
            val fake = FakeBridgeClient(responder = reads)
            val connection = MutableStateFlow(ConnectionState.Connected)

            clientOver(fake, connection).observeGame(GAME, seed).test {
                assertEquals(seed, awaitItem())

                fake.emitPush(GameOver(gameId = GAME, message = "pete has won the game", state = view(turn = 8)))
                val over = awaitItem()
                assertTrue(over.isOver)

                connection.value = ConnectionState.Reconnecting
                testScheduler.runCurrent()
                connection.value = ConnectionState.Connected
                testScheduler.runCurrent()
                assertEquals("the resume still re-emits the terminal state", over, awaitItem())

                testScheduler.advanceTimeBy(1_000L)
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

        /** The bridge's hit: a held snapshot, stamped with when it was captured. */
        fun snapshot(
            state: GameStateView,
            capturedAtEpochMs: Long = 1_700_000_000_000L,
            prompt: GamePromptMessage? = null,
        ): ServerMessage = GameStateSnapshot(gameId = GAME, state = state, capturedAtEpochMs = capturedAtEpochMs, prompt = prompt)

        /** The bridge's typed miss — the reply that must never be rendered as a board. */
        fun noState(
            gameId: String,
            requestId: String?,
        ): ServerMessage =
            GameStateUnavailable(
                gameId = gameId,
                reason = GameStateUnavailableCode.NO_STATE_YET,
                detail = "no snapshot for this game on this session yet",
                requestId = requestId,
            )
    }
}
