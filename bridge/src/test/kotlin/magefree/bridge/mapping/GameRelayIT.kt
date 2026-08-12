package magefree.bridge.mapping

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import mage.remote.SessionImpl
import magefree.bridge.xmage.BridgeMageClient
import magefree.bridge.xmage.XMageConnection
import magefree.bridge.xmage.XMageServerTarget
import magefree.protocol.ChooseChoicePrompt
import magefree.protocol.CreateTableOptions
import magefree.protocol.DeckList
import magefree.protocol.DeckListCard
import magefree.protocol.GameInformed
import magefree.protocol.GameOver
import magefree.protocol.GamePrompt
import magefree.protocol.GamePrompted
import magefree.protocol.GameStarted
import magefree.protocol.GameStateUpdated
import magefree.protocol.GameStateView
import magefree.protocol.GetAmountPrompt
import magefree.protocol.GetMultiAmountPrompt
import magefree.protocol.MatchStarting
import magefree.protocol.PhaseStepCode
import magefree.protocol.PlayXManaPrompt
import magefree.protocol.SeatPlayerTypeCode
import magefree.protocol.SelectPrompt
import magefree.protocol.ServerMessage
import magefree.protocol.SkillLevelCode
import magefree.protocol.SkillLevelCode.CASUAL
import magefree.protocol.TableActionCode
import magefree.protocol.TableActionResult
import magefree.protocol.TableCreated
import magefree.protocol.TargetPrompt
import magefree.protocol.TurnPhaseCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.util.UUID

/**
 * **The story's real proof** (story 0051): a live game crossing the bridge, driven against the
 * reference server (story 0022) through a **real** [SessionImpl].
 *
 * The hermetic tests can only assert that crafted views map correctly and that each reply reaches the
 * right upstream verb. This asserts the two things they structurally cannot:
 * 1. that the server's own `GameView` — built by a real game, over a real deck, on a real turn — maps
 *    to a state the app can actually use: a hand of seven, a numbered turn in a named phase, an
 *    identified priority holder, and a **non-empty `canPlayObjects`** on the viewer's own main phase;
 * 2. that the reply path works, because the only way this test ever reaches that main phase is by the
 *    server accepting its answers: it keeps its opening hand, then passes priority, prompt after
 *    prompt, until the turn comes round. A dropped or mis-typed reply stalls the game and the test
 *    times out.
 *
 * **The scenario** reuses `TableRelayIT`'s recipe verbatim (see its KDoc for why each value is the one
 * the real server accepts): a `Two Player Duel`, `Constructed - Freeform Unlimited`, 60× `Forest`
 * (`M21` #272), seats `[HUMAN, COMPUTER_MAD]` with the AI joined first. A mono-basic deck is also what
 * keeps the prompt stream small — no spells, so no modes, no costs and no combat — which is what lets
 * the auto-responder below be a few lines rather than a rules engine.
 *
 * **Ordering note.** `GAME_INIT` is fired by `GameController.startGame()` *before* the game worker
 * deals cards, so the very first snapshot legitimately has an empty hand. The opening hand arrives on a
 * later push — which is why this waits for a state carrying seven cards rather than asserting on the
 * first one.
 *
 * **Env-gated:** enabled only when `XMAGE_SERVER` is set; otherwise JUnit reports it *skipped*.
 *
 * ```
 * ./scripts/dev up xmage-server
 * XMAGE_SERVER=xmage-server:17171 ./scripts/dev gradle :bridge:test --tests '*GameRelayIT' --rerun-tasks
 * ```
 */
@EnabledIfEnvironmentVariable(named = XMageServerTarget.ENV_VAR, matches = ".+")
class GameRelayIT {
    private companion object {
        const val GAME_TYPE = "Two Player Duel"
        const val DECK_TYPE = "Constructed - Freeform Unlimited"
        const val AI_SEAT_NAME = "Computer"

        /** How long to wait for the server to push `START_GAME` after `startMatch` was accepted. */
        const val MATCH_START_TIMEOUT_MS = 120_000L

        /** How long to wait for `GAME_INIT` after `joinGame` (it is what triggers the game start). */
        const val GAME_INIT_TIMEOUT_MS = 120_000L

        /** How long the pass-and-observe loop may run before the assertions it is waiting on must hold. */
        const val PLAY_TIMEOUT_MS = 180_000L

        /** How long a single push may take while the game is running. */
        const val PUSH_TIMEOUT_MS = 60_000L

        /** The opening hand size the reference server deals; the first thing the app must be able to show. */
        const val OPENING_HAND = 7

        /** Far more prompts than a mono-land opening needs; a breaker for a refused-and-re-asked reply. */
        const val MAX_ANSWERS = 60

        val DECK =
            DeckList(
                name = "it_forests",
                author = "game-relay-it",
                cards = listOf(DeckListCard(cardName = "Forest", setCode = "M21", collectorNumber = "272", amount = 60)),
                sideboard = emptyList(),
            )
    }

    @Test
    fun `a real game crosses the bridge - opening hand, turn, priority and what is playable`() =
        runBlocking {
            val username = uniqueUsername()
            val client = BridgeMageClient()
            val session = connect(client, username)
            val roomId = requireNotNull(session.mainRoomId) { "the main room id should be resolvable once connected" }

            var tableId: UUID? = null
            var gameId: UUID? = null
            var pump: Job? = null
            val events = Channel<ServerMessage>(Channel.UNLIMITED)
            try {
                pump = startCallbackPump(client, events)

                // ---- Reach match start, exactly as TableRelayIT does. -------------------------------
                val created =
                    withContext(Dispatchers.IO) {
                        TableRelay.createTable(session, roomId, options(name = "it_game_$username"))
                    }
                assertTrue(created is TableCreated, "the real server should accept the mapped MatchOptions; got $created")
                tableId = UUID.fromString((created as TableCreated).table.tableId)

                assertJoined(join(session, roomId, tableId, AI_SEAT_NAME, SeatPlayerTypeCode.COMPUTER_MAD))
                assertJoined(join(session, roomId, tableId, username, SeatPlayerTypeCode.HUMAN))

                val start = withContext(Dispatchers.IO) { TableRelay.startMatch(session, roomId, tableId) }
                assertEquals(TableActionResult(action = TableActionCode.START_MATCH, ok = true), start)

                val matchStarting = events.awaitOfType<MatchStarting>(MATCH_START_TIMEOUT_MS, "a START_GAME push")
                gameId = UUID.fromString(matchStarting.gameId)

                // ---- Join the game: this is the call that makes the server start pushing state. ------
                val joined = withContext(Dispatchers.IO) { GameRelay.joinGame(session, gameId) }
                assertTrue(joined.ok, "the real server should accept joinGame for a match we are seated in; got $joined")

                val started = events.awaitOfType<GameStarted>(GAME_INIT_TIMEOUT_MS, "a GAME_INIT push")
                assertEquals(gameId.toString(), started.gameId, "GAME_INIT's object id is the game id")
                assertEquals(
                    2,
                    started.state.players.size,
                    "the first snapshot should already carry both seats, got ${started.state.players}",
                )
                assertNotNull(started.state.viewerPlayerId, "we joined as a player, so the snapshot is built for our seat")
                assertTrue(
                    started.state.players.any { it.name == username } && started.state.players.any { it.name == AI_SEAT_NAME },
                    "both seats should be named, got ${started.state.players.map { it.name }}",
                )

                // ---- Play along: keep the opening hand, then pass until our own main phase. ----------
                val observed = playUntilOurMainPhase(session, gameId, events)

                // 1. The opening hand really crossed the bridge.
                val openingHand =
                    requireNotNull(observed.openingHand) {
                        "no snapshot ever carried a $OPENING_HAND-card hand; callbacks were ${callbackSummary()}; last state was ${observed.last}"
                    }
                assertEquals(
                    OPENING_HAND,
                    openingHand.hand.size,
                    "the viewer's hand should be the seven cards the server dealt, got ${openingHand.hand}",
                )
                assertTrue(
                    openingHand.hand.all { it.name == "Forest" && it.setCode == "M21" && it.collectorNumber == "272" },
                    "every card should be the printing we submitted, got ${openingHand.hand}",
                )

                // 2. Turn, phase and step are real, named values — not the UNKNOWN fallbacks.
                val priority =
                    requireNotNull(observed.ourMainPhase) {
                        "we never reached our own main phase with priority; last state was ${observed.last}"
                    }
                assertTrue(priority.turn >= 1, "a running game is on a numbered turn, got ${priority.turn}")
                assertEquals(TurnPhaseCode.PRECOMBAT_MAIN, priority.phase)
                assertEquals(PhaseStepCode.PRECOMBAT_MAIN, priority.step)

                // 3. The priority holder is identified — and it is us, on our own turn.
                assertTrue(priority.viewerHasPriority, "the select prompt is only sent to the player holding priority")
                assertEquals(
                    priority.viewerPlayerId,
                    priority.activePlayerId,
                    "we waited for our own turn, so the active player should be us",
                )
                val holder = priority.players.single { it.hasPriority }
                assertEquals(priority.viewerPlayerId, holder.playerId, "exactly one seat holds priority, and it is ours")
                assertEquals(username, holder.name)
                assertEquals(priority.viewerPlayerId, priority.players.single { it.viewer }.playerId)

                // 4. Playability is the server's own answer, and it names objects we actually have.
                assertTrue(
                    priority.playable.isNotEmpty(),
                    "on our own main phase with lands in hand the server's canPlayObjects must not be empty; got $priority",
                )
                val known = (priority.hand.map { it.id } + priority.players.flatMap { p -> p.battlefield.map { it.card.id } }).toSet()
                assertTrue(
                    priority.playable.all { it.objectId in known },
                    "every playable object should be one we can see; playable=${priority.playable} known=$known",
                )
                assertTrue(
                    priority.playable.all { it.abilityIds.isNotEmpty() },
                    "each playable object should name the abilities that make it playable, got ${priority.playable}",
                )

                // 5. The reply path worked: we only got here because the server accepted every answer.
                assertTrue(
                    observed.answered >= 2,
                    "reaching our main phase requires answering the mulligan and at least one priority prompt, " +
                        "answered=${observed.answered}",
                )
                assertTrue(
                    observed.players.contains(username) && observed.players.contains(AI_SEAT_NAME),
                    "both seats should have been visible throughout, saw ${observed.players}",
                )

                // Recorded in the test output so a run's evidence is the server's actual numbers, not a
                // green tick: the report shows what the live game really produced.
                println(
                    "GameRelayIT: hand=${openingHand.hand.size} turn=${priority.turn} " +
                        "phase=${priority.phase}/${priority.step} priorityHolder=${holder.name} " +
                        "playable=${priority.playable.size} answered=${observed.answered} callbacks=${callbackSummary()}",
                )
            } finally {
                pump?.cancel()
                events.close()
                teardown(session, roomId, tableId, gameId)
            }
        }

    /** What the pass-and-observe loop saw, for the assertions above. */
    private class Observed {
        var openingHand: GameStateView? = null
        var ourMainPhase: GameStateView? = null
        var last: GameStateView? = null
        var answered: Int = 0
        val players: MutableSet<String> = mutableSetOf()
    }

    /**
     * Consumes pushes, answering each prompt conservatively (keep the hand, then pass priority), until
     * the viewer holds priority in its **own** precombat main phase — the one moment where the server's
     * `canPlayObjects` must be non-empty for a hand full of lands.
     *
     * The answers are deliberately the least interesting legal ones: this test is about the transport,
     * not about playing well. Each is sent through [GameRelay], so the reply half of the story is under
     * test too — a reply that never reaches the server simply stops the stream and the loop times out.
     */
    private suspend fun playUntilOurMainPhase(
        session: SessionImpl,
        gameId: UUID,
        events: Channel<ServerMessage>,
    ): Observed {
        val observed = Observed()
        val deadline = System.currentTimeMillis() + PLAY_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val remaining = minOf(PUSH_TIMEOUT_MS, deadline - System.currentTimeMillis())
            val event = withTimeoutOrNull(remaining) { events.receive() } ?: break

            stateOf(event)?.let { state ->
                observed.last = state
                observed.players += state.players.map { it.name }
                if (observed.openingHand == null && state.hand.size == OPENING_HAND) observed.openingHand = state
            }

            if (event is GameOver) break
            if (event !is GamePrompted) continue

            val state = event.state
            if (state.viewerHasPriority &&
                state.viewerPlayerId != null &&
                state.viewerPlayerId == state.activePlayerId &&
                state.phase == TurnPhaseCode.PRECOMBAT_MAIN &&
                event.prompt is SelectPrompt &&
                state.playable.isNotEmpty()
            ) {
                observed.ourMainPhase = state
                break
            }

            answer(session, gameId, event.prompt)
            observed.answered++
            // A cap, not padding: if a reply is refused the server re-asks immediately, so a wrong
            // answer shows up as an unbounded prompt loop. Stopping here turns that into a failure
            // that names the prompt, instead of a timeout that says nothing.
            if (observed.answered > MAX_ANSWERS) {
                throw AssertionError(
                    "answered $MAX_ANSWERS prompts without reaching our main phase — the server is likely " +
                        "re-asking a refused reply. Last prompt: ${event.prompt}. Callbacks: ${callbackSummary()}",
                )
            }
        }
        return observed
    }

    /**
     * Answers [prompt] with the least interesting legal reply for a mono-land deck: `false` covers
     * "keep this hand", "pass priority" and "done choosing"; the numeric/text prompts get their minimum.
     *
     * **A required [TargetPrompt] is the exception, and it is not hypothetical.** Before turn one the
     * server picks a player to choose who goes first, and asks them with a *required* `GAME_TARGET`
     * whose candidates are the two seats. Answering `false` there is a cancel, which upstream refuses
     * and immediately re-asks — an infinite prompt loop. (Which player is asked is random, so this
     * failed roughly one run in two before the [TargetPrompt] arm existed.) Picking the first candidate
     * from the prompt's own `targetIds` is what makes the run deterministic — and it is the one place
     * this test uses the prompt's typed payload to construct its reply, which is exactly the property a
     * closed prompt set exists to give the app.
     */
    private suspend fun answer(
        session: SessionImpl,
        gameId: UUID,
        prompt: GamePrompt,
    ) {
        withContext(Dispatchers.IO) {
            when (prompt) {
                is TargetPrompt -> {
                    val candidate = prompt.targetIds.firstOrNull() ?: prompt.cards.firstOrNull()?.id
                    if (prompt.required && candidate != null) {
                        GameRelay.sendPlayerUuid(session, gameId, UUID.fromString(candidate))
                    } else {
                        GameRelay.sendPlayerBoolean(session, gameId, false)
                    }
                }
                is ChooseChoicePrompt ->
                    prompt.choices.firstOrNull()?.let { GameRelay.sendPlayerString(session, gameId, it.key) }
                        ?: GameRelay.sendPlayerBoolean(session, gameId, false)
                is GetAmountPrompt -> GameRelay.sendPlayerInteger(session, gameId, prompt.min)
                is PlayXManaPrompt -> GameRelay.sendPlayerInteger(session, gameId, 0)
                is GetMultiAmountPrompt ->
                    GameRelay.sendPlayerString(session, gameId, prompt.entries.joinToString(",") { it.min.toString() })
                else -> GameRelay.sendPlayerBoolean(session, gameId, false)
            }
        }
    }

    /** The snapshot an in-game push carries, or `null` for the pushes that carry none. */
    private fun stateOf(event: ServerMessage): GameStateView? =
        when (event) {
            is GameStarted -> event.state
            is GameStateUpdated -> event.state
            is GameInformed -> event.state
            is GameOver -> event.state
            is GamePrompted -> event.state
            else -> null
        }

    /** Receives until a [T] arrives, failing after [timeoutMs] describing [what]. */
    private suspend inline fun <reified T : ServerMessage> Channel<ServerMessage>.awaitOfType(
        timeoutMs: Long,
        what: String,
    ): T {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val remaining = deadline - System.currentTimeMillis()
            val event = withTimeoutOrNull(remaining) { receive() } ?: break
            if (event is T) return event
        }
        throw AssertionError("timed out after ${timeoutMs}ms waiting for $what")
    }

    /** A username satisfying XMage's `[a-z0-9_]`, length 3..14 rule: `it_` + 8 hex = 11 chars. */
    private fun uniqueUsername(): String = "it_${UUID.randomUUID().toString().replace("-", "").substring(0, 8)}"

    private fun assertJoined(result: TableActionResult) {
        assertEquals(TableActionResult(action = TableActionCode.JOIN, ok = true), result, "the server should seat the player")
    }

    /** Connects and authenticates a real [SessionImpl] driven by [client] against the `XMAGE_SERVER` target. */
    private suspend fun connect(
        client: BridgeMageClient,
        username: String,
    ): SessionImpl {
        val target = requireNotNull(XMageServerTarget.fromEnv()) { "XMAGE_SERVER must be set for this test" }
        val session = SessionImpl(client)
        val connection =
            XMageConnection.build(host = target.host, port = target.port, username = username, password = "")
        val connected = withContext(Dispatchers.IO) { session.connectStart(connection) }
        check(connected) { "connectStart should succeed against the reference server" }
        return session
    }

    /** The [CreateTableOptions] the real server accepts — see `TableRelayIT`'s KDoc for why each value. */
    private fun options(name: String): CreateTableOptions =
        CreateTableOptions(
            name = name,
            gameType = GAME_TYPE,
            deckType = DECK_TYPE,
            players = listOf(SeatPlayerTypeCode.HUMAN, SeatPlayerTypeCode.COMPUTER_MAD),
            rated = false,
            winsNeeded = 1,
            freeMulligans = 0,
            skillLevel = CASUAL,
            spectatorsAllowed = false,
        )

    /** Joins [tableId] as [seatName] of [playerType], submitting [DECK], on [Dispatchers.IO]. */
    private suspend fun join(
        session: SessionImpl,
        roomId: UUID,
        tableId: UUID,
        seatName: String,
        playerType: SeatPlayerTypeCode,
    ): TableActionResult =
        withContext(Dispatchers.IO) {
            TableRelay.joinTable(
                session,
                roomId,
                tableId,
                seatName = seatName,
                deck = DECK,
                playerType = playerType,
                skill = SkillLevelCode.CASUAL,
                password = null,
            )
        }

    /**
     * Collects [client]'s raw callbacks, maps each through [CallbackMapper] — the same mapper the relay
     * uses — and offers the result to [events]. `trySend` on an unlimited channel keeps the collector
     * non-blocking, so a slow assertion can never make the remoting hand-off drop a push. Returns once
     * the collector is *subscribed*, so a callback raised by a later action cannot be missed.
     */
    private suspend fun CoroutineScope.startCallbackPump(
        client: BridgeMageClient,
        events: Channel<ServerMessage>,
    ): Job {
        val subscribed = CompletableDeferred<Unit>()
        val job =
            launch(Dispatchers.Default) {
                client.callbacks
                    .onSubscription { subscribed.complete(Unit) }
                    .collect { raw ->
                        val mapped = CallbackMapper.map(raw)
                        // The raw trace is what makes a failure diagnosable: "nothing arrived" and
                        // "everything arrived and the mapper dropped it" look identical downstream.
                        rawTrace += "${raw.method}${if (mapped == null) "(unmapped)" else ""}"
                        mapped?.let { events.trySend(it) }
                    }
            }
        subscribed.await()
        return job
    }

    /** Every callback method the server pushed, in order, with the ones the mapper dropped marked. */
    private val rawTrace = java.util.concurrent.CopyOnWriteArrayList<String>()

    /**
     * The callback trace as method→count. A failing game can push hundreds of identical prompts, so the
     * *shape* of the stream is what a reader needs, not every element of it.
     */
    private fun callbackSummary(): String = rawTrace.groupingBy { it }.eachCount().toString()

    /**
     * Concedes the game (so the server is not left holding a match against a vanished player), removes
     * the table and disconnects, so a repeat run — and `LobbyRelayIT`, which asserts the room has no
     * open tables — starts from a clean server. Best-effort: a teardown failure must not mask the
     * assertion that caused it.
     */
    private suspend fun teardown(
        session: SessionImpl,
        roomId: UUID,
        tableId: UUID?,
        gameId: UUID?,
    ) {
        withContext(Dispatchers.IO) {
            if (gameId != null) runCatching { GameRelay.quitMatch(session, gameId) }
            if (tableId != null) runCatching { TableRelay.removeTable(session, roomId, tableId) }
            runCatching { session.connectStop(false, false) }
        }
    }
}
