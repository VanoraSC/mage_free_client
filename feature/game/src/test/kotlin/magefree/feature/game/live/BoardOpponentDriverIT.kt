package magefree.feature.game.live

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import magefree.decks.model.Deck
import magefree.decks.model.DeckEntry
import magefree.decks.model.DeckId
import magefree.model.Credentials
import magefree.model.ServerTarget
import magefree.model.SessionEvent
import magefree.model.SkillLevel
import magefree.network.game.GameClient
import magefree.network.game.GameClients
import magefree.network.game.GamePrompt
import magefree.network.game.GameState
import magefree.network.ktor.KtorBridgeClient
import magefree.network.table.CreateTableOptions
import magefree.network.table.SeatPlayerType
import magefree.network.table.TableClient
import magefree.network.table.TableClients
import magefree.network.table.TableState
import org.junit.Assume
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The **on-device driver**: the second client of the two-client harness.
 *
 * The board this builds is read-only, so it cannot drive a game. This is the thing that does:
 * a real app-side session (the production [KtorBridgeClient] + [TableClients] + [GameClients] stack,
 * assembled exactly as `NetworkModule` assembles it) that hosts a two-**human** table, waits for the
 * app on the emulator to take the other seat, starts the match, and then plays and narrates its own
 * side while the app renders the same game.
 *
 * It prints, on every state it receives, both seats as the server reports them — so a run leaves a
 * transcript that can be lined up against the app's screen, rather than a green tick.
 *
 * **Why two sessions and not one.** Upstream's "join only once" guard applies to `HUMAN` seats, so one
 * session cannot fill both; the app is the second session, and that is the whole point — its board is
 * fed by the server, not by this test.
 *
 * **This is not an assertion suite.** It is a harness, run by hand alongside the emulator, and it is
 * env-gated on `BRIDGE_URL` so `:feature:game:check` stays hermetic and offline (an unset variable
 * *skips* it). Run it with:
 * ```
 * BRIDGE_URL=localhost:8080 ./gradlew :feature:game:testDebugUnitTest --tests '*BoardOpponentDriverIT*' --rerun-tasks -i
 * ```
 *
 * **A known, deliberate limit of a read-only board, recorded here because the harness runs into it.**
 * XMage asks *every* seat whether it wants a mulligan, on the game thread, and blocks on the answer
 * (`Mulligan.executeMulliganPhase` → `HumanPlayer.chooseMulligan` → `waitForResponse`, which is an
 * unbounded `response.wait()`). A client that cannot answer therefore stops the game for **both**
 * players at the mulligan. So this harness can drive the game as far as: match start, the deal, the
 * opening hands, the who-goes-first choice, and the mulligan question — which is exactly the material
 * the board's seats, vitals, hand, turn, prompt and **priority-in-both-directions** are drawn from —
 * and no further. Turn advance and a populated battlefield need a client that can answer, which is
 *
 *
 * ### What a real run produces (`emulator-5554` + `docker-bridge-1`)
 * ```
 * [driver] table 'it_board' created
 * [driver] WAITING FOR THE APP to join from the lobby…
 * [driver] driver seated as it_ae10e9b9 (second)
 * [driver] both seats filled: [Player/Human, it_ae10e9b9/Human]
 * [driver] match starting; gameId=e086f1be-…
 * [driver] STATE turn=1 … prompt=Target('Select a starting player') seats=[…hand=0 lib=60…]
 * [driver] answering required Target 'Select a starting player' with 3227a57c-…
 * [driver] STATE turn=1 … hand=7 … seats=[…hand=7 lib=53…] msg=Waiting for <font …>Player</font>
 * ```
 * and the app's board, at that moment, drew: opponent `20 it_ae10e9b9 Lib 53 · Hand 7 · GY 0 ·
 * Exile 0 · Wins 0/1`, both battlefields on `No permanents`, `Turn 1 · … · turn holder unknown`,
 * **"The server is waiting on you"**, `Stack · empty`, `Server asks: Mulligan down to 6 cards?`
 * (the server's HTML stripped off), `Exile empty`, `In hand 7`, and — expanded — seven Forests.
 * Earlier in the same sequence, while the server was asking the *driver*, it drew
 * **"Waiting for opponent"** with `Waiting for it_…` as the narration: the indicator, in both
 * directions, driven entirely from this side.
 *
 * **Two things a run cannot show here, and why.**
 * 1. **A moving game.** The mulligan block above. Battlefields, the stack filling and clearing, and
 *    turn/phase advance are unreachable until 0056 can answer.
 * 2. **Card art.** Not the board's doing: this emulator has **no external egress**
 *    (`nc 10.0.2.2 8080` answers the bridge; `nc <external host> 80` answers nothing), so every art
 *    request fails and the design-system placeholder renders — which is the correct degradation, and
 *    `:feature:cards`' own card browser shows exactly the same placeholders on the same device. A
 *    *second*, independent problem is waiting behind it: Scryfall answers **HTTP 400
 *    `generic_user_agent`** to OkHttp's default `User-Agent` and 200 to a descriptive one, and
 *    `CardImageLoader` sets none — so art would still not load even with egress. Both are recorded in
 *    this report; neither is here's guardrails to fix.
 *
 * **Seat order is load-bearing here** — see the comment on the deferred `joinTable` below.
 */
class BoardOpponentDriverIT {
    @Test
    fun `hosts a two-human table, waits for the app to sit down, starts the match and plays its side`() {
        val target = requireBridge()
        runBlocking {
            val username =
                "it_" +
                    UUID
                        .randomUUID()
                        .toString()
                        .filter { it != '-' }
                        .take(8)
            val session = DriverSession(this, target)
            session.connect(username)

            var tableId: String? = null
            var tableObserver: Job? = null
            var gameObserver: Job? = null
            try {
                val created =
                    session.tables
                        .createTable(
                            CreateTableOptions(
                                name = TABLE_NAME,
                                gameType = DUEL,
                                deckType = FREEFORM,
                                // Two HUMAN seats: one for this driver, one for the app.
                                players = listOf(SeatPlayerType.Human, SeatPlayerType.Human),
                                rated = false,
                                winsNeeded = 1,
                                freeMulligans = 0,
                                skillLevel = SkillLevel.Casual,
                                spectatorsAllowed = true,
                            ),
                        ).getOrThrow()
                tableId = created.tableId
                say("table '${created.name}' created: ${created.tableId}")

                val tableStates = CopyOnWriteArrayList<TableState>()
                tableObserver =
                    launch {
                        session.tables
                            .observeTable(created.tableId, TableState(tableId = created.tableId))
                            .collect { tableStates += it }
                    }

                say("WAITING FOR THE APP to join '$TABLE_NAME' from the lobby…")

                // **The driver seats itself second, on purpose.** XMage picks who chooses the starting
                // player with `players[RandomUtil.nextInt(players.length)]` over the seats in join
                // order, and that player is asked a *required* target prompt before the deal. When the
                // app is picked, a read-only board cannot answer it and the game never even deals —
                // which is what happened on three runs in a row while the driver sat down first.
                // Joining after the app puts the app in slot 0 and this driver in slot 1, which flips
                // which seat the same draw lands on and lets the run reach the opening hand.
                awaitTable(tableStates, "the app to take a seat", APP_JOIN_TIMEOUT_MS) {
                    it != null && it.seats.count { seat -> seat.isOccupied } >= 1
                }
                session.tables
                    .joinTable(created.tableId, username, deck, playerType = SeatPlayerType.Human)
                    .getOrThrow()
                say("driver seated as $username (second)")

                // The server reports readiness itself — nothing here invents it.
                awaitTable(tableStates, "the table to become ready to start", APP_JOIN_TIMEOUT_MS) {
                    it?.isReadyToStart == true
                }
                say("both seats filled: ${tableStates.last().seats.map { "${it.name}/${it.playerType}" }}")

                session.tables.startMatch(created.tableId).getOrThrow()
                val starting =
                    awaitTable(tableStates, "the server to push MatchStarting", MATCH_START_TIMEOUT_MS) {
                        it?.matchStarting != null
                    }!!.matchStarting!!
                say("match starting; gameId=${starting.gameId} — the app should now be on its board")

                // Observe before joining: the push channel is replay-less, exactly as in production.
                gameObserver =
                    launch {
                        session.games.observeGame(starting.gameId, GameState(starting.gameId)).collect { report(it) }
                    }
                session.games.joinGame(starting.gameId).getOrThrow()

                // Play this side for as long as the run lasts. Every answer goes through the real
                // client, so what the app sees is the consequence of a genuine server round-trip.
                playOurSide(session.games, starting.gameId)
            } finally {
                gameObserver?.let { runCatching { it.cancelAndJoin() } }
                tableObserver?.let { runCatching { it.cancelAndJoin() } }
                tableId?.let { runCatching { session.tables.removeTable(it) } }
                session.close()
            }
        }
    }

    /**
     * Answers this driver's own prompts for the length of the run, deliberately slowly so a human
     * watching the emulator can see each change land.
     *
     * The mulligan answer alternates: **mulligan, then keep**. Each mulligan the driver takes changes
     * its own `handCount`, which the app draws in the opponent's vitals bar — a visible, server-caused
     * change on the app's board that this test caused. And while the server is asking *this* seat, the
     * app holds no priority ("Waiting for opponent"); while it is asking the app's seat, the app holds
     * it ("Your turn to act"). That is the priority indicator, in both directions, driven from here.
     */
    private suspend fun playOurSide(
        games: GameClient,
        gameId: String,
    ) {
        var mulligansTaken = 0
        val deadline = System.currentTimeMillis() + RUN_FOR_MS
        while (System.currentTimeMillis() < deadline) {
            delay(ANSWER_PAUSE_MS)
            val prompt = latestPrompt ?: continue
            if (prompt === lastAnswered) continue
            lastAnswered = prompt
            when (prompt) {
                is GamePrompt.Ask -> {
                    // The mulligan question. Take one, then keep, so the app sees our hand shrink.
                    val mulligan = mulligansTaken < MULLIGANS_TO_TAKE
                    if (mulligan) mulligansTaken++
                    say("answering Ask '${prompt.message}' with ${if (mulligan) "MULLIGAN" else "KEEP"}")
                    games.answerAsk(gameId, answer = mulligan)
                }

                is GamePrompt.Target -> {
                    val candidate = prompt.targetIds.firstOrNull() ?: prompt.cards.firstOrNull()?.id
                    if (prompt.isRequired && candidate != null) {
                        say("answering required Target '${prompt.message}' with $candidate")
                        games.chooseTarget(gameId, candidate)
                    } else {
                        say("declining Target '${prompt.message}'")
                        games.cancelPrompt(gameId)
                    }
                }

                is GamePrompt.Select -> {
                    // Play a land if the server offers one, else pass. Playing is what puts a permanent
                    // on the app's *opponent* battlefield.
                    val offered = latestState?.playable.orEmpty().firstOrNull()
                    if (offered != null) {
                        say("playing offered object ${offered.objectId}")
                        games.playObject(gameId, offered.objectId)
                    } else {
                        say("passing priority")
                        games.passPriority(gameId)
                    }
                }

                is GamePrompt.ChooseChoice ->
                    prompt.choices.firstOrNull()?.let { games.chooseChoice(gameId, it.key) }
                        ?: games.cancelPrompt(gameId)

                is GamePrompt.ChooseAbility -> games.cancelPrompt(gameId)
                is GamePrompt.ChoosePile -> games.choosePile(gameId, first = true)
                is GamePrompt.GetAmount -> games.chooseAmount(gameId, prompt.min)
                is GamePrompt.PlayMana -> games.cancelPrompt(gameId)
                is GamePrompt.PlayXMana -> games.announceX(gameId, 0)
                is GamePrompt.GetMultiAmount -> games.distributeAmounts(gameId, prompt.entries.map { it.min })
                // Nothing knows what a valid answer to an unrecognised prompt is; inventing one here
                // would be exactly what the closed prompt set exists to prevent.
                is GamePrompt.Unrecognised -> Unit
            }
        }
        // End the game deliberately rather than by walking away: `GAME_OVER` is the only thing that
        // populates the board's result region, and conceding from *this* side is the one way a
        // read-only board can be shown a real terminal result.
        say("conceding to end the game — the app should show the server's own end-of-game line")
        games.concede(gameId)
        delay(END_PAUSE_MS)
        say("run window over")
    }

    @Volatile
    private var latestState: GameState? = null

    @Volatile
    private var latestPrompt: GamePrompt? = null

    private var lastAnswered: GamePrompt? = null

    /** Print what the server just said, in the same terms the app's board draws it. */
    private fun report(state: GameState) {
        latestState = state
        latestPrompt = state.prompt
        val seats =
            state.players.joinToString(" | ") { p ->
                "${p.name}(life=${p.life} hand=${p.handCount} lib=${p.libraryCount} " +
                    "bf=${p.battlefield.size} active=${p.isActive} priority=${p.hasPriority})"
            }
        say(
            "STATE turn=${state.turn} ${state.phase}/${state.step} " +
                "viewerPriority=${state.viewerHasPriority} playable=${state.playable.size} " +
                "stack=${state.stack.size} hand=${state.hand.size} " +
                "prompt=${state.prompt?.let { it::class.simpleName + "('" + it.message + "')" }} " +
                "seats=[$seats] msg=${state.lastMessage?.text}",
        )
    }

    private fun say(line: String) = println("[driver] $line")

    private suspend fun awaitTable(
        states: List<TableState>,
        what: String,
        timeoutMs: Long,
        condition: (TableState?) -> Boolean,
    ): TableState? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val last = states.lastOrNull()
            if (condition(last)) return last
            delay(POLL_MS)
        }
        throw AssertionError("timed out after ${timeoutMs}ms waiting for $what; last table was ${states.lastOrNull()}")
    }

    /** One live app→bridge session, assembled from production parts only. */
    private class DriverSession(
        private val scope: CoroutineScope,
        private val target: ServerTarget,
    ) {
        val client = KtorBridgeClient()
        val tables: TableClient by lazy { TableClients.overBridge(client, client.connectionState) }
        val games: GameClient by lazy { GameClients.overBridge(client, client.connectionState) }
        private var collector: Job? = null

        suspend fun connect(username: String) {
            val connected = CompletableDeferred<Unit>()
            collector =
                scope.launch {
                    client.connect(target, Credentials(username)).collect { event ->
                        when (event) {
                            is SessionEvent.Connected -> connected.complete(Unit)
                            is SessionEvent.AuthFailed ->
                                connected.completeExceptionally(
                                    AssertionError("login rejected: ${event.message}"),
                                )
                            is SessionEvent.VersionUnsupported ->
                                connected.completeExceptionally(AssertionError("version rejected: ${event.detail}"))
                            else -> Unit
                        }
                    }
                }
            withTimeout(CONNECT_TIMEOUT_MS) { connected.await() }
        }

        suspend fun close() {
            runCatching { client.disconnect() }
            collector?.let { runCatching { it.cancelAndJoin() } }
        }
    }

    private fun requireBridge(): ServerTarget {
        val raw = System.getenv(BRIDGE_URL_ENV)
        Assume.assumeTrue("$BRIDGE_URL_ENV is not set; skipping the on-device board driver", !raw.isNullOrBlank())
        val authority =
            raw!!
                .trim()
                .trimEnd('/')
                .removePrefix("wss://")
                .removePrefix("ws://")
        val separator = authority.lastIndexOf(':')
        return ServerTarget(
            host = authority.substring(0, separator),
            port = authority.substring(separator + 1).toInt(),
            secure = raw.startsWith("wss://"),
        )
    }

    /**
     * The mono-basic deck `docs/live-test-decklists.md` records as working against the reference
     * server. Deck resolution is by **(setCode, collectorNumber)**, never by name — a wrong pair fails
     * with "Card not found" even when the name is spelled correctly.
     */
    private val deck =
        Deck(
            id = DeckId("board-driver-forests"),
            name = "board_driver_forests",
            author = "board-driver-it",
            main = listOf(DeckEntry(cardName = "Forest", setCode = "M21", collectorNumber = "272", quantity = 60)),
        )

    private companion object {
        const val BRIDGE_URL_ENV = "BRIDGE_URL"

        /** The name the app looks for in the lobby. */
        const val TABLE_NAME = "it_board"

        const val DUEL = "Two Player Duel"

        /** The only validator that is unconditionally valid (`deckMinSize == 0`). */
        const val FREEFORM = "Constructed - Freeform Unlimited"

        const val CONNECT_TIMEOUT_MS = 60_000L

        /** Long, on purpose: a human is signing in and joining on the emulator in this window. */
        const val APP_JOIN_TIMEOUT_MS = 600_000L

        const val MATCH_START_TIMEOUT_MS = 120_000L

        /** How long to keep playing this side once the game is up. */
        const val RUN_FOR_MS = 420_000L

        /** A beat after the concede so the terminal push reaches the app before teardown. */
        const val END_PAUSE_MS = 20_000L

        /** A deliberate beat between answers so each change is watchable on the emulator. */
        const val ANSWER_PAUSE_MS = 3_000L

        const val POLL_MS = 500L

        /** Take one mulligan before keeping, so our hand visibly shrinks on the app's opponent bar. */
        const val MULLIGANS_TO_TAKE = 1
    }
}
