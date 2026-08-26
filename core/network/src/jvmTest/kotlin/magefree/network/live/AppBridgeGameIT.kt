package magefree.network.live

import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import magefree.decks.model.Deck
import magefree.decks.model.DeckEntry
import magefree.decks.model.DeckId
import magefree.model.SkillLevel
import magefree.network.game.GamePrompt
import magefree.network.game.GameState
import magefree.network.game.PhaseStep
import magefree.network.game.TurnPhase
import magefree.network.live.LiveBridge.Companion.awaitValue
import magefree.network.live.LiveBridge.Companion.requireTarget
import magefree.network.live.LiveBridge.Companion.uniqueUsername
import magefree.network.table.CreateTableOptions
import magefree.network.table.SeatPlayerType
import magefree.network.table.TableState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * **the real proof**: a real game, against a real AI, on the reference XMage
 * server, reaching the app through the app's **own** production stack —
 * [magefree.network.ktor.KtorBridgeClient] → `PendingRequests` → `DefaultGameClient` →
 * `GameEventFold`/`GameViewMapper` → an app-schema [GameState].
 *
 * `:bridge`'s `GameRelayIT` proves the *bridge* speaks XMage's game protocol correctly. This proves the
 * layer the user actually sits behind, which is a different claim and the one that matters here: every
 * hermetic test here asserts that *crafted* views map correctly and that each reply becomes the
 * right wire message. Only a live run can say whether the server's own `GameView` — built by a real game,
 * over a real deck, on a real turn — becomes a [GameState] a board could actually be drawn from.
 *
 * **What it asserts, and why each is the interesting one:**
 * 1. the viewer's **hand of seven** arrives, as the printings we submitted;
 * 2. the game is on a **numbered turn in a named phase/step** — not the `Unknown` fallbacks;
 * 3. the **priority holder is identified**, by id, and it is us;
 * 4. `playable` is **non-empty on our own main phase** and names only objects we can see — the server's
 *    own answer to "what can I play", never a client inference;
 * 5. **passing priority advances the state** — the first end-to-end proof that the reply path works
 *    through the app's client, because the only way this test reaches its own main phase at all is by
 *    the server accepting every answer it sent.
 *
 * **The scenario** reuses `AppBridgeHostTableIT`'s recipe verbatim (see its KDoc for why each value is
 * the one the real server accepts): a `Two Player Duel`, `Constructed - Freeform Unlimited`, 60×
 * `Forest` (`M21` #272), seats `[Human, ComputerMad]` with the AI joined first. A mono-basic deck is
 * also what keeps the prompt stream small — no spells, so no modes, no costs, no combat — which is what
 * lets the auto-responder below be a dozen lines rather than a rules engine.
 *
 * **Ordering note.** `GAME_INIT` fires *before* the game worker deals cards, so the first snapshot
 * legitimately carries an empty hand; this waits for a state carrying seven rather than asserting on the
 * first one. And because the push side-channel is replay-less, [GameClient.observeGame] is collected
 * **before** `joinGame` is called — a subscription started afterwards can miss `GAME_INIT` entirely.
 *
 * **Teardown.** The reference server is long-lived, so a leaked game or table would poison later runs
 * (and `:bridge`'s `LobbyRelayIT`, which asserts the room has no open tables). The match is quit and the
 * table removed in a `finally`, including after a failed assertion, so the suite is repeatable.
 *
 * **Env-gated** on `BRIDGE_URL`; skipped (not failed) without it.
 *
 * ```
 * ./scripts/dev up bridge
 * BRIDGE_URL=localhost:8080 ./gradlew :core:network:testDebugUnitTest --tests '*live*' --rerun-tasks
 * ```
 */
class AppBridgeGameIT {
    @Test
    fun `joins a real game, receives a hand of seven with priority and playables, and passes to advance it`() {
        val target = requireTarget()
        runBlocking {
            val username = uniqueUsername()
            val bridge = LiveBridge(this, target)
            bridge.connect(username)

            var tableId: String? = null
            var gameId: String? = null
            var tableObserver: Job? = null
            var gameObserver: Job? = null
            val states = Channel<GameState>(Channel.UNLIMITED)
            try {
                // ---- Reach match start, exactly as AppBridgeHostTableIT does. ------------------------
                val created =
                    bridge.tables
                        .createTable(options("it_game_$username"))
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

                // ---- Observe the game *before* joining it. -------------------------------------------
                // The push side-channel is replay-less (like production), and `GAME_INIT` follows the
                // join within milliseconds, so subscribing afterwards can miss the game's first snapshot.
                val seed = GameState(gameId)
                gameObserver = launch { bridge.games.observeGame(gameId, seed).collect { states.trySend(it) } }
                assertEquals("observeGame emits its seed as the flow opens", seed, states.receiveWithin(SEED_TIMEOUT_MS))

                bridge.games.joinGame(gameId).expect("the real server should accept joinGame for a match we are seated in")

                // ---- Play along: keep the opening hand, then pass until our own main phase. ----------
                val observed = playUntilOurMainPhase(bridge, gameId, states)

                // 1. The opening hand really crossed the whole stack into app-schema state.
                val opening =
                    requireNotNull(observed.openingHand) {
                        "no state ever carried a $OPENING_HAND-card hand; answered=${observed.answered}; last was ${observed.last}"
                    }
                assertEquals(
                    "the viewer's hand should be the seven cards the server dealt, got ${opening.hand.map { it.name }}",
                    OPENING_HAND,
                    opening.hand.size,
                )
                assertTrue(
                    "every card should be the printing we submitted, got ${opening.hand}",
                    opening.hand.all { it.name == "Forest" && it.setCode == "M21" && it.collectorNumber == "272" },
                )

                // 2. Turn, phase and step are real, named values — not the Unknown fallbacks.
                val priority =
                    requireNotNull(observed.ourMainPhase) {
                        "we never reached our own main phase with priority; answered=${observed.answered}; last was ${observed.last}"
                    }
                assertTrue("a running game is on a numbered turn, got ${priority.turn}", priority.turn >= 1)
                assertEquals(TurnPhase.PrecombatMain, priority.phase)
                assertEquals(PhaseStep.PrecombatMain, priority.step)

                // 3. The priority holder is identified by id, and it is us on our own turn.
                assertTrue("a select prompt only reaches the player holding priority", priority.viewerHasPriority)
                assertTrue("we waited for our own turn", priority.isViewerTurn)
                assertNotNull("we joined as a player, so the snapshot is built for our seat", priority.viewerPlayerId)
                assertFalse("a seated player is not a spectator", priority.isSpectator)
                val holder = priority.players.single { it.hasPriority }
                assertEquals("exactly one seat holds priority, and it is ours", priority.viewerPlayerId, holder.playerId)
                assertEquals(username, holder.name)
                assertEquals(username, priority.priorityPlayerName)
                assertEquals(priority.viewerPlayerId, priority.viewer?.playerId)
                assertEquals("both seats should be in the snapshot", 2, priority.players.size)
                assertTrue("the AI seat should be there", priority.players.any { it.name == AI_SEAT_NAME && !it.isHuman })
                assertTrue("both players start on 20 life, got ${priority.players.map { it.life }}", priority.players.all { it.life == 20 })

                // 4. Playability is the server's own answer, and it names objects we actually hold.
                assertTrue(
                    "on our own main phase with lands in hand the server's canPlayObjects must not be empty; got $priority",
                    priority.playable.isNotEmpty(),
                )
                val known =
                    (priority.hand.map { it.id } + priority.players.flatMap { p -> p.battlefield.map { it.card.id } }).toSet()
                assertTrue(
                    "every playable object should be one we can see; playable=${priority.playable} known=$known",
                    priority.playable.all { it.objectId in known },
                )
                assertTrue(
                    "each playable object should name the abilities that make it playable, got ${priority.playable}",
                    priority.playable.all { it.abilityIds.isNotEmpty() },
                )
                assertTrue("the lookup helper agrees with the list", priority.playable.all { priority.isPlayable(it.objectId) })

                // 5. There is an outstanding prompt, and it is the typed Select a UI would gate on.
                assertTrue("our own priority arrives as a Select prompt, got ${priority.prompt}", priority.prompt is GamePrompt.Select)

                // 6. **Pass priority and watch the state advance.** This is the reply path proven through
                //    the app's own client: nothing else moves the game on.
                val before = priority
                bridge.games.passPriority(gameId).expect("the server should accept a pass from the priority holder")
                val advanced =
                    awaitState(
                        what = "the game to advance past ${before.phase}/${before.step} on turn ${before.turn}",
                        states = states,
                        timeoutMs = ADVANCE_TIMEOUT_MS,
                        bridge = bridge,
                        gameId = gameId,
                    ) { it.turn != before.turn || it.phase != before.phase || it.step != before.step }
                assertTrue(
                    "passing priority must move the game on; before=${before.turn}/${before.phase}/${before.step} " +
                        "after=${advanced.turn}/${advanced.phase}/${advanced.step}",
                    advanced.turn != before.turn || advanced.phase != before.phase || advanced.step != before.step,
                )

                // Recorded so a run's evidence is the server's actual numbers, not a green tick — and so
                // the board-design session has a concrete inventory of what a real GameState contains.
                println(describe(opening, priority, advanced, observed))
            } finally {
                gameObserver?.let { runCatching { it.cancelAndJoin() } }
                tableObserver?.let { runCatching { it.cancelAndJoin() } }
                // Teardown *before* the socket closes: both ride the same session.
                gameId?.let { runCatching { bridge.games.quitMatch(it) } }
                tableId?.let { runCatching { bridge.tables.removeTable(it) } }
                bridge.close()
            }
        }
    }

    /** What the pass-and-observe loop saw, for the assertions above. */
    private class Observed {
        var openingHand: GameState? = null
        var ourMainPhase: GameState? = null
        var last: GameState? = null
        var answered: Int = 0
        val prompts: MutableList<String> = mutableListOf()
    }

    /**
     * Consumes emitted [GameState]s, answering each outstanding prompt conservatively (keep the hand,
     * then pass priority), until the viewer holds priority in its **own** precombat main phase — the one
     * moment where the server's `canPlayObjects` must be non-empty for a hand full of lands.
     *
     * Every answer goes through the **real** [magefree.network.game.GameClient], so the reply half of the
     * is under test too: a reply that never reaches the server simply stops the stream and this
     * times out.
     */
    private suspend fun playUntilOurMainPhase(
        bridge: LiveBridge,
        gameId: String,
        states: Channel<GameState>,
    ): Observed {
        val observed = Observed()
        val deadline = System.currentTimeMillis() + PLAY_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val remaining = minOf(PUSH_TIMEOUT_MS, deadline - System.currentTimeMillis())
            val state = withTimeoutOrNull(remaining) { states.receive() } ?: break

            observed.last = state
            if (observed.openingHand == null && state.hand.size == OPENING_HAND) observed.openingHand = state
            if (state.isOver) break

            val prompt = state.prompt ?: continue
            if (state.viewerHasPriority &&
                state.isViewerTurn &&
                state.phase == TurnPhase.PrecombatMain &&
                prompt is GamePrompt.Select &&
                state.playable.isNotEmpty()
            ) {
                observed.ourMainPhase = state
                return observed
            }

            observed.prompts += prompt::class.simpleName.orEmpty()
            answer(bridge, gameId, prompt)
            observed.answered++
            // A cap, not padding: a refused reply is re-asked immediately, so a wrong answer shows up as
            // an unbounded prompt loop. Stopping here names the prompt instead of timing out silently.
            if (observed.answered > MAX_ANSWERS) {
                throw AssertionError(
                    "answered $MAX_ANSWERS prompts without reaching our main phase — the server is likely re-asking a " +
                        "refused reply. Last prompt: $prompt. Prompt trace: ${observed.prompts.summary()}",
                )
            }
        }
        return observed
    }

    /**
     * Answers [prompt] with the least interesting legal reply for a mono-land deck, through the real
     * client — one call per prompt kind, which is also a live exercise of the typed reply surface.
     *
     * **A required [GamePrompt.Target] is the exception, and it is not hypothetical.** Before turn one
     * the server picks a player to choose who goes first and asks them with a *required* target prompt
     * whose candidates are the two seats. Cancelling there is refused and immediately re-asked — an
     * infinite prompt loop — and which player is asked is random, so this failed roughly one run in two
     * before the arm existed (`:bridge`'s `GameRelayIT` records the same finding). Picking the first
     * candidate from the prompt's own `targetIds` is what makes the run deterministic, and it is the one
     * place this test builds a reply from a prompt's typed payload — exactly the property a closed
     * prompt set exists to give the app.
     */
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
            // Nothing knows what a valid answer to an unrecognised prompt is, so the client offers no
            // method for one and this must not invent one. Against this bridge it never occurs.
            is GamePrompt.Unrecognised -> Unit
        }
    }

    /**
     * Receives states until [condition] holds, answering any *other* prompt that arrives meanwhile (the
     * game cannot advance while it is waiting on us), or fails after [timeoutMs].
     */
    private suspend fun awaitState(
        what: String,
        states: Channel<GameState>,
        timeoutMs: Long,
        bridge: LiveBridge,
        gameId: String,
        condition: (GameState) -> Boolean,
    ): GameState {
        var last: GameState? = null
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val remaining = deadline - System.currentTimeMillis()
            val state = withTimeoutOrNull(remaining) { states.receive() } ?: break
            last = state
            if (condition(state)) return state
            state.prompt?.let { answer(bridge, gameId, it) }
        }
        throw AssertionError("timed out after ${timeoutMs}ms waiting for $what; last state was $last")
    }

    private suspend fun Channel<GameState>.receiveWithin(timeoutMs: Long): GameState =
        withTimeoutOrNull(timeoutMs) { receive() } ?: throw AssertionError("no GameState emitted within ${timeoutMs}ms")

    /**
     * The run's own inventory of what a real [GameState] contains — printed so the evidence for a board
     * design is the server's actual data rather than a recollection of it. Names what is **present**,
     * what is **empty**, and (for the zones that only fill in some phases) which.
     */
    private fun describe(
        opening: GameState,
        priority: GameState,
        advanced: GameState,
        observed: Observed,
    ): String {
        val card = opening.hand.first()
        val printings = opening.hand.map { "${it.name}(${it.setCode} #${it.collectorNumber})" }.distinct()
        val seats =
            priority.players.map { p ->
                "${p.name}(life=${p.life} lib=${p.libraryCount} hand=${p.handCount} gy=${p.graveyardCount} " +
                    "exile=${p.exileCount} human=${p.isHuman} wins=${p.wins}/${p.winsNeeded} " +
                    "pool=${p.manaPool.total} battlefield=${p.battlefield.size})"
            }
        return buildString {
            appendLine("AppBridgeGameIT — what a live GameState actually contained:")
            appendLine("  answered=${observed.answered} promptKinds=${observed.prompts.summary()}")
            appendLine("  opening hand: ${opening.hand.size} x $printings")
            appendLine("    card fields: manaCost=${card.manaCost} typeLine=${card.typeLine} rules=${card.rules}")
            appendLine("  at our priority: turn=${priority.turn} phase=${priority.phase} step=${priority.step}")
            appendLine("    viewerPlayerId=${priority.viewerPlayerId} viewerHasPriority=${priority.viewerHasPriority}")
            appendLine("    isViewerTurn=${priority.isViewerTurn} isSpectator=${priority.isSpectator}")
            appendLine("    activePlayer=${priority.activePlayerName} priorityPlayerName=${priority.priorityPlayerName}")
            appendLine("    players=$seats")
            appendLine("    playable=${priority.playable.size} abilitiesPerEntry=${priority.playable.map { it.abilityIds.size }}")
            appendLine("    stack=${priority.stack.size} exileZones=${priority.exile.size}")
            appendLine("    revealedZones=${priority.revealed.size} combatGroups=${priority.combat.size}")
            appendLine("    specialActions=${priority.specialActionsAvailable} priorityTime=${priority.priorityTimeSeconds}s")
            appendLine("    bufferTime=${priority.bufferTimeSeconds}s")
            appendLine("    prompt=${priority.prompt}")
            appendLine("    lastMessage=${priority.lastMessage} lastError=${priority.lastError}")
            appendLine("  after passing: turn=${advanced.turn} phase=${advanced.phase} step=${advanced.step}")
            appendLine("    viewerHasPriority=${advanced.viewerHasPriority} playable=${advanced.playable.size}")
            appendLine("    prompt=${advanced.prompt?.let { it::class.simpleName }}")
            appendLine("    battlefields=${advanced.players.map { "${it.name}=${it.battlefield.size}" }}")
            appendLine("    stack=${advanced.stack.size} combat=${advanced.combat.size}")
        }
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
            author = "app-bridge-game-it",
            main = listOf(DeckEntry(cardName = "Forest", setCode = "M21", collectorNumber = "272", quantity = 60)),
        )

    /** Unwraps a [Result] from a client verb, failing with the server's own reason rather than a throw. */
    private fun <T> Result<T>.expect(what: String): T =
        getOrElse { failure ->
            throw AssertionError("$what; the client returned ${failure::class.simpleName}: ${failure.message}", failure)
        }

    private companion object {
        const val DUEL = "Two Player Duel"
        const val FREEFORM = "Constructed - Freeform Unlimited"

        /** The name given to the AI seat's player; upstream does not constrain it. */
        const val AI_SEAT_NAME = "Computer"

        /** The opening hand size the reference server deals; the first thing a board must be able to show. */
        const val OPENING_HAND = 7

        /** How long the server may take to push START_GAME after a start is accepted. */
        const val MATCH_START_TIMEOUT_MS = 120_000L

        /** How long `observeGame`'s synchronous seed emission may take to reach the collector. */
        const val SEED_TIMEOUT_MS = 30_000L

        /** How long the pass-and-observe loop may run before its assertions must hold. */
        const val PLAY_TIMEOUT_MS = 180_000L

        /** How long a single push may take while the game is running. */
        const val PUSH_TIMEOUT_MS = 60_000L

        /** How long the game may take to move on after a pass is accepted. */
        const val ADVANCE_TIMEOUT_MS = 120_000L

        /** Far more prompts than a mono-land opening needs; a breaker for a refused-and-re-asked reply. */
        const val MAX_ANSWERS = 60
    }
}
