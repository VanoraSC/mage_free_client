package magefree.network.live

import app.cash.turbine.test
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import magefree.decks.model.Deck
import magefree.decks.model.DeckEntry
import magefree.decks.model.DeckId
import magefree.model.ConnectionState
import magefree.model.SkillLevel
import magefree.network.game.GamePrompt
import magefree.network.game.GameState
import magefree.network.game.GameStateUnavailableFailure
import magefree.network.game.GameStateUnavailableReason
import magefree.network.game.TurnPhase
import magefree.network.live.LiveBridge.Companion.awaitValue
import magefree.network.live.LiveBridge.Companion.requireTarget
import magefree.network.live.LiveBridge.Companion.uniqueUsername
import magefree.network.reconnect.FakeConnectivityObserver
import magefree.network.table.CreateTableOptions
import magefree.network.table.SeatPlayerType
import magefree.network.table.TableState
import magefree.protocol.GameError
import magefree.protocol.GameInformed
import magefree.protocol.GameOver
import magefree.protocol.GamePrompted
import magefree.protocol.GameStarted
import magefree.protocol.GameStateUpdated
import magefree.protocol.ServerMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.milliseconds

/**
 * **Story 0054's real proof**: a client that loses its connection mid-game and reconnects can see the
 * board **immediately**, without waiting for the opponent to do anything.
 *
 * This is the scenario the feature exists for and the one that fails without it. XMage has no verb that
 * reads a `GameView`, and re-joining a running game does not resync (`GameController.join` on a running
 * game only logs "rejoined"), so before this story a reconnecting client was blind until the server
 * happened to push — which, while the opponent is thinking, can be minutes or never.
 *
 * **How the "nothing is being pushed" condition is guaranteed, not hoped for.** The test first plays to
 * its own precombat main phase, where the server has sent a `Select` prompt and is *blocked on our
 * answer*. While that prompt is outstanding the game cannot advance and the server pushes nothing at
 * all. The test never answers it. So between the reconnect and the assertion there is provably no push —
 * and it asserts that directly, by watching the client's own push side-channel.
 *
 * **How the drop is performed.** [LiveBridge.dropRadio] takes the *network* away, which is what
 * production actually does: `ReconnectingSession` ends the running attempt the moment connectivity is
 * lost (story 0050 defect B), the socket closes with no `Logout` on it so the bridge **parks** the
 * session (0023), and the reconnect loop stays alive holding its `ResumeHandle` so the next attempt
 * sends `Resume` (0024). That is the shape `KtorBridgeClient`'s KDoc records from the on-device smoke —
 * Android tearing down a lingering network after a WIFI/CELLULAR hand-off.
 *
 * `LiveBridge.dropWithoutSigningOut()` deliberately is **not** used here: cancelling the session flow
 * discards the resume handle with it, so what follows is a fresh `Login` and a brand-new bridge session
 * — which by design has no cache, because the cache belongs to the session that was sent the snapshots.
 * That path cannot show a board for a different reason too: a fresh login would have to re-join the
 * running game, and upstream does not resync a re-join. Resume is the path, and this is it.
 *
 * **What it asserts:**
 * 1. **Before the join, the bridge honestly says it has nothing** — a typed `NoStateYet`, not an empty
 *    board. This is the standard-5 control: without it, a cache that is *always* empty and a cache that
 *    works look identical from the other side of the second assertion.
 * 2. **After the reconnect, a direct `refreshGame` returns a real, populated board** — the proof that
 *    the cache is genuinely populated live rather than merely present and always empty.
 * 3. **A board opened fresh after the reconnect fills itself** — `observeGame` on a blank seed emits a
 *    populated state, with **no game push** having arrived in that window.
 *
 * **Env-gated** on `BRIDGE_URL`; skipped (not failed) without it. The bridge image must be rebuilt from
 * this branch first, or it will answer the unknown `get_game_state` type with nothing at all:
 *
 * ```
 * docker compose -f docker/docker-compose.yml build bridge
 * docker compose -f docker/docker-compose.yml up -d --no-deps --force-recreate bridge
 * BRIDGE_URL=localhost:8080 ./gradlew :core:network:testDebugUnitTest --tests '*live*' --rerun-tasks
 * ```
 */
class AppBridgeGameReconnectIT {
    @Test
    fun `a client dropped mid-game and reconnected sees the board before the opponent acts`() {
        val target = requireTarget()
        runBlocking {
            val username = uniqueUsername()
            val bridge = LiveBridge(this, target, FakeConnectivityObserver())
            bridge.connect(username)

            var tableId: String? = null
            var gameId: String? = null
            var tableObserver: Job? = null
            var gameObserver: Job? = null
            var pushWatcher: Job? = null
            val states = Channel<GameState>(Channel.UNLIMITED)
            val pushes = CopyOnWriteArrayList<ServerMessage>()
            try {
                // ---- Reach a running game, exactly as AppBridgeGameIT does. --------------------------
                val created =
                    bridge.tables
                        .createTable(options("it_recon_$username"))
                        .expect("the real server should accept the mapped create options")
                tableId = created.tableId

                val tableStates = CopyOnWriteArrayList<TableState>()
                tableObserver =
                    launch { bridge.tables.observeTable(created.tableId, created.toSeed()).collect { tableStates += it } }

                bridge.tables
                    .joinTable(created.tableId, AI_SEAT_NAME, deck, playerType = SeatPlayerType.ComputerMad)
                    .expect("the server should seat a ComputerMad player")
                bridge.tables
                    .joinTable(created.tableId, username, deck, playerType = SeatPlayerType.Human)
                    .expect("the server should seat the host")
                awaitValue(
                    what = "the table to become ready to start",
                    read = { tableStates.lastOrNull() },
                    condition = { it?.isReadyToStart == true },
                )
                bridge.tables.startMatch(created.tableId).expect("the server should start a fully seated match")

                val starting =
                    awaitValue(
                        what = "the observed table to receive MatchStarting",
                        timeoutMs = MATCH_START_TIMEOUT_MS,
                        read = { tableStates.lastOrNull() },
                        condition = { it?.matchStarting != null },
                    )!!.matchStarting!!
                gameId = starting.gameId

                // ---- 1. The standard-5 control, *before* anything can have been cached. ---------------
                // A cache that is always empty presents exactly like this feature working — right up
                // until a real reconnect. This pins the honest "nothing yet" now, so the populated read
                // later cannot be a constant.
                val beforeJoin = bridge.games.refreshGame(gameId)
                val absent = beforeJoin.exceptionOrNull()
                assertTrue(
                    "before this session has been sent a single snapshot the bridge must say so, " +
                        "typed — got ${beforeJoin.getOrNull() ?: absent}",
                    absent is GameStateUnavailableFailure,
                )
                assertEquals(
                    GameStateUnavailableReason.NoStateYet,
                    (absent as GameStateUnavailableFailure).reason,
                )

                // ---- Join and play to our own main phase, where the server waits on *us*. -------------
                gameObserver = launch { bridge.games.observeGame(gameId, GameState(gameId)).collect { states.trySend(it) } }
                states.receiveWithin(SEED_TIMEOUT_MS)
                bridge.games.joinGame(gameId).expect("the real server should accept joinGame for a match we are seated in")

                val ourTurn = playUntilOurMainPhase(bridge, gameId, states)
                assertTrue("we should hold priority in our own main phase, got $ourTurn", ourTurn.viewerHasPriority)
                assertTrue("with an outstanding Select the server is blocked on us", ourTurn.prompt is GamePrompt.Select)
                assertTrue("we should be holding cards to compare the cached board against", ourTurn.hand.isNotEmpty())

                // From here the game cannot move: it is waiting for an answer this test never gives.
                // Anything that arrives on the push channel from now on would invalidate the claim, so
                // watch it.
                pushWatcher = launch { bridge.client.serverPushes.collect { pushes += it } }

                // ---- 2. Lose the network, park the session, come back and resume. ---------------------
                bridge.dropRadio()
                awaitValue(
                    what = "the client to notice the radio is gone",
                    timeoutMs = DROP_TIMEOUT_MS,
                    read = { bridge.client.connectionState.value },
                    condition = { it != ConnectionState.Connected },
                )
                // Long enough for the bridge to see the close and park the session, comfortably inside
                // the 60s resume TTL. A resume that arrives before the park is refused (story 0026 F4)
                // and falls back to a fresh Login — a different session, with no cache, which is exactly
                // the failure this pause avoids.
                delay(RADIO_OFF_MS)

                pushes.clear()
                bridge.restoreRadio()
                awaitValue(
                    what = "the session to come back (a Resume onto the parked session)",
                    timeoutMs = RESUME_TIMEOUT_MS,
                    read = { bridge.client.connectionState.value },
                    condition = { it == ConnectionState.Connected },
                )

                // The direct read. This is the standard-5 evidence: the cache is genuinely populated in
                // a live game, not merely present.
                val snapshot =
                    bridge.games
                        .refreshGame(gameId)
                        .expect(
                            "after a resume the bridge must hold this session's board; if this is a " +
                                "NoStateYet the resume did not re-attach the parked session (a fresh " +
                                "Login has no cache), or the bridge image predates this branch",
                        )
                assertTrue("the read must produce a real board", snapshot.state.hasSnapshot)
                assertEquals("both seats should be in the cached snapshot", 2, snapshot.state.players.size)
                assertEquals(
                    "the cached board is *our* view, with the hand we were holding when the socket died",
                    ourTurn.hand.size,
                    snapshot.state.hand.size,
                )
                assertTrue("and that hand is not empty", snapshot.state.hand.isNotEmpty())
                assertEquals(
                    "and it is the seat the snapshot was built for — never the opponent's view",
                    ourTurn.viewerPlayerId,
                    snapshot.state.viewerPlayerId,
                )
                assertTrue(
                    "a cached board must be at least as far along as the one we last saw, got " +
                        "${snapshot.state.turn} vs ${ourTurn.turn}",
                    snapshot.state.turn >= ourTurn.turn,
                )
                assertNotNull("the capture time makes staleness knowable", snapshot.capturedAtEpochMs)
                assertNoGamePushes(pushes, gameId, "the direct read")

                // ---- 3. A board opened fresh after the reconnect fills itself. -------------------------
                // The user's actual experience: come back, open the game, see the board. The seed is
                // blank, nothing is pushed, and the only thing that can fill it is the read.
                val blank = GameState(gameId)
                bridge.games.observeGame(gameId, blank).test(timeout = OPEN_READ_TIMEOUT_MS.milliseconds) {
                    assertEquals("observeGame emits its seed as the flow opens", blank, awaitItem())
                    val restored = awaitItem()
                    assertTrue("the board must fill from the bridge's held snapshot alone", restored.hasSnapshot)
                    assertEquals(2, restored.players.size)
                    assertEquals(ourTurn.hand.size, restored.hand.size)
                    assertTrue(
                        "the printings we submitted should survive the whole round trip, got ${restored.hand.map { it.name }}",
                        restored.hand.all { it.name == "Forest" && it.setCode == "M21" && it.collectorNumber == "272" },
                    )
                    cancelAndIgnoreRemainingEvents()
                }
                assertNoGamePushes(pushes, gameId, "the re-opened board")

                println(describe(ourTurn, snapshot.state, snapshot.capturedAtEpochMs))
            } finally {
                pushWatcher?.let { runCatching { it.cancelAndJoin() } }
                gameObserver?.let { runCatching { it.cancelAndJoin() } }
                tableObserver?.let { runCatching { it.cancelAndJoin() } }
                gameId?.let { runCatching { bridge.games.quitMatch(it) } }
                tableId?.let { runCatching { bridge.tables.removeTable(it) } }
                bridge.close()
            }
        }
    }

    /**
     * The claim the whole test rests on: **nothing was pushed**. Without this, a board that appeared
     * because the server happened to send a snapshot would read exactly like a board that appeared
     * because the read worked — and only the second is this story.
     */
    private fun assertNoGamePushes(
        pushes: List<ServerMessage>,
        gameId: String,
        what: String,
    ) {
        val forThisGame =
            pushes.filter { push ->
                when (push) {
                    is GameStarted -> push.gameId == gameId
                    is GameStateUpdated -> push.gameId == gameId
                    is GameInformed -> push.gameId == gameId
                    is GamePrompted -> push.gameId == gameId
                    is GameOver -> push.gameId == gameId
                    is GameError -> push.gameId == gameId
                    else -> false
                }
            }
        assertTrue(
            "no game push may have arrived before $what — the board must have come from the bridge's " +
                "cache, not from the server moving on. Got ${forThisGame.map { it::class.simpleName }}",
            forThisGame.isEmpty(),
        )
    }

    /**
     * Answers each prompt conservatively (keep the hand, then pass) until the viewer holds priority in
     * its own precombat main phase — the point at which the server is blocked on our answer and the game
     * therefore pushes nothing at all. Lifted from `AppBridgeGameIT`, whose KDoc explains why the
     * required-`Target` arm is not hypothetical (the "who goes first" prompt is random and re-asks
     * forever if cancelled).
     */
    private suspend fun playUntilOurMainPhase(
        bridge: LiveBridge,
        gameId: String,
        states: Channel<GameState>,
    ): GameState {
        var answered = 0
        var last: GameState? = null
        val trace = mutableListOf<String>()
        val deadline = System.currentTimeMillis() + PLAY_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val remaining = minOf(PUSH_TIMEOUT_MS, deadline - System.currentTimeMillis())
            val state = withTimeoutOrNull(remaining) { states.receive() } ?: break
            last = state
            if (state.isOver) break

            val prompt = state.prompt ?: continue
            if (state.viewerHasPriority &&
                state.isViewerTurn &&
                state.phase == TurnPhase.PrecombatMain &&
                prompt is GamePrompt.Select &&
                // The same condition `AppBridgeGameIT` waits on. Deliberately *not* "a hand of seven":
                // which player goes first is random, so on the draw we have already drawn a card by our
                // own main phase — a fixed hand size makes the run a coin flip.
                state.playable.isNotEmpty()
            ) {
                return state
            }

            trace += prompt::class.simpleName.orEmpty()
            answer(bridge, gameId, prompt)
            answered++
            if (answered > MAX_ANSWERS) {
                throw AssertionError(
                    "answered $MAX_ANSWERS prompts without reaching our main phase — the server is likely " +
                        "re-asking a refused reply. Last prompt: $prompt. Trace: ${trace.summary()}",
                )
            }
        }
        throw AssertionError(
            "never reached our own main phase with a full hand; answered=$answered trace=${trace.summary()} last=$last",
        )
    }

    private suspend fun answer(
        bridge: LiveBridge,
        gameId: String,
        prompt: GamePrompt,
    ) {
        when (prompt) {
            is GamePrompt.Target -> {
                val candidate = prompt.targetIds.firstOrNull() ?: prompt.cards.firstOrNull()?.id
                if (prompt.isRequired && candidate != null) {
                    bridge.games.chooseTarget(gameId, candidate)
                } else {
                    bridge.games.cancelPrompt(gameId)
                }
            }

            is GamePrompt.Ask -> bridge.games.answerAsk(gameId, answer = false)
            is GamePrompt.Select -> bridge.games.passPriority(gameId)
            is GamePrompt.ChooseChoice ->
                prompt.choices.firstOrNull()?.let { bridge.games.chooseChoice(gameId, it.key) }
                    ?: bridge.games.cancelPrompt(gameId)

            is GamePrompt.ChooseAbility -> bridge.games.cancelPrompt(gameId)
            is GamePrompt.ChoosePile -> bridge.games.choosePile(gameId, first = true)
            is GamePrompt.GetAmount -> bridge.games.chooseAmount(gameId, prompt.min)
            is GamePrompt.PlayMana -> bridge.games.cancelPrompt(gameId)
            is GamePrompt.PlayXMana -> bridge.games.announceX(gameId, 0)
            is GamePrompt.GetMultiAmount -> bridge.games.distributeAmounts(gameId, prompt.entries.map { it.min })
            is GamePrompt.Unrecognised -> Unit
        }
    }

    private suspend fun Channel<GameState>.receiveWithin(timeoutMs: Long): GameState =
        withTimeoutOrNull(timeoutMs) { receive() } ?: throw AssertionError("no GameState emitted within ${timeoutMs}ms")

    /** The run's own evidence, printed so a reviewer reads the server's numbers rather than a green tick. */
    private fun describe(
        beforeDrop: GameState,
        afterResume: GameState,
        capturedAtEpochMs: Long?,
    ): String =
        buildString {
            appendLine("AppBridgeGameReconnectIT — the board the bridge held across a real reconnect:")
            appendLine("  before the drop: turn=${beforeDrop.turn} phase=${beforeDrop.phase} step=${beforeDrop.step}")
            appendLine("    hand=${beforeDrop.hand.size} playable=${beforeDrop.playable.size} prompt=${beforeDrop.prompt}")
            appendLine("  read after the resume: turn=${afterResume.turn} phase=${afterResume.phase} step=${afterResume.step}")
            appendLine("    viewerPlayerId=${afterResume.viewerPlayerId} hand=${afterResume.hand.size}")
            val seats =
                afterResume.players.map { "${it.name}(life=${it.life} hand=${it.handCount} bf=${it.battlefield.size})" }
            appendLine("    players=$seats")
            appendLine("    playable=${afterResume.playable.size} stack=${afterResume.stack.size} capturedAtEpochMs=$capturedAtEpochMs")
        }

    private fun List<String>.summary(): String = groupingBy { it }.eachCount().toString()

    // --- fixtures ------------------------------------------------------------------------------------

    private fun options(name: String) =
        CreateTableOptions(
            name = name,
            gameType = DUEL,
            deckType = FREEFORM,
            players = listOf(SeatPlayerType.Human, SeatPlayerType.ComputerMad),
            rated = false,
            winsNeeded = 1,
            freeMulligans = 0,
            skillLevel = SkillLevel.Casual,
            spectatorsAllowed = false,
        )

    private val deck =
        Deck(
            id = DeckId("it-forests"),
            name = "it_forests",
            author = "app-bridge-game-reconnect-it",
            main = listOf(DeckEntry(cardName = "Forest", setCode = "M21", collectorNumber = "272", quantity = 60)),
        )

    private fun <T> Result<T>.expect(what: String): T =
        getOrElse { failure ->
            throw AssertionError("$what; the client returned ${failure::class.simpleName}: ${failure.message}", failure)
        }

    private companion object {
        const val DUEL = "Two Player Duel"
        const val FREEFORM = "Constructed - Freeform Unlimited"
        const val AI_SEAT_NAME = "Computer"

        const val MATCH_START_TIMEOUT_MS = 120_000L
        const val SEED_TIMEOUT_MS = 30_000L
        const val PLAY_TIMEOUT_MS = 180_000L
        const val PUSH_TIMEOUT_MS = 60_000L
        const val MAX_ANSWERS = 60

        /** How long the client may take to notice the radio has gone. */
        const val DROP_TIMEOUT_MS = 60_000L

        /** How long to stay offline: past the bridge's park, well inside its 60s resume TTL. */
        const val RADIO_OFF_MS = 5_000L

        /** How long the reconnect + resume may take once the radio is back. */
        const val RESUME_TIMEOUT_MS = 60_000L

        /** How long a freshly-opened board may take to fill itself from the bridge's held snapshot. */
        const val OPEN_READ_TIMEOUT_MS = 30_000L
    }
}
