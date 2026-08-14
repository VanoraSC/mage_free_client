package magefree.feature.game.live

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import magefree.decks.model.Deck
import magefree.decks.model.DeckEntry
import magefree.decks.model.DeckId
import magefree.feature.game.board.BoardAction
import magefree.feature.game.board.GameBoardViewModel
import magefree.feature.game.board.ManualPassPolicy
import magefree.feature.game.board.PromptControlsUi
import magefree.model.Credentials
import magefree.model.ServerTarget
import magefree.model.SessionEvent
import magefree.model.SkillLevel
import magefree.network.game.GameClient
import magefree.network.game.GameClients
import magefree.network.game.GamePrompt
import magefree.network.game.GameState
import magefree.network.game.PhaseStep
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
 * **A probe, not an assertion suite** — it exists to answer one question that no run has ever answered:
 * *what does the server actually ask when attackers and blockers are declared?*
 *
 * ## Why this exists
 *
 * `docs/game-board-requirements.md` §7.1 is the entire combat specification, and it decides only that
 * combat will reuse the tap model. §17 records why that is an assumption rather than a finding:
 *
 * > **Not covered by this run:** combat declaration and blocks were never reached (`combatSteps=0`) —
 * > the loop spent nine turns drawing lands before it had creatures, then ended at the cancel.
 *
 * So the prompt *shape* for a declaration is unknown. `PromptOptions` already exposes
 * `possibleAttackers`/`possibleBlockers`, and the bridge's `optionsView()` carries **any** collection
 * valued option through generically — so the data path is complete **if** upstream populates those keys.
 * Whether it does, on which prompt kind, and what the message reads, is what this run records.
 *
 * `docs/live-test-decklists.md` warns that deep-game experiments belong *inside* the story that builds
 * the surface rather than as standalone probes. That guidance is right, and this is the deliberate
 * exception: the combat story cannot be written until the prompt shapes are known, so the probe comes
 * first and stays small — it reaches combat, prints what arrives, and stops.
 *
 * ## Shape of the run
 *
 * - **The opponent seat is the attacker**, driven over a raw [GameClient] so it can answer a declaration
 *   straight from `possibleAttackers`. It has to be this way round: the board has no combat handling
 *   yet, so a ViewModel-driven seat cannot be relied on to attack.
 * - **The app seat is a real [GameBoardViewModel]**, so the run also records *what today's projection
 *   does* with a declaration prompt — which is the other half of what the story needs to know.
 * - Every prompt on both seats is printed **verbatim**, with its option keys, because the point is the
 *   shape and not a pass/fail.
 *
 * ## The deck
 * 24× `Mountain` (`10E` #376) + 36× `Dragon Fodder` (`ALA` #97). Dragon Fodder is `{1}{R}` for **two**
 * 1/1 Goblin tokens, so both seats have attackers and blockers within a couple of turns — the specific
 * failure §17 hit was nine turns of drawing lands before any creature existed. Resolution is by
 * **(setCode, collectorNumber)**, never by name.
 *
 * Env-gated on `BRIDGE_URL` so the hermetic gate stays offline. Run it with:
 * ```
 * BRIDGE_URL=localhost:8080 ./gradlew :feature:game:testDebugUnitTest --tests '*CombatProbeIT*' --rerun-tasks -i
 * ```
 */
class CombatProbeIT {
    private var opponentState: GameState? = null
    private var appState: GameState? = null

    /** Prompts already printed, so a re-pushed identical question does not flood the transcript. */
    private val seen = CopyOnWriteArrayList<String>()

    private var sawAttackerPrompt = false
    private var sawBlockerPrompt = false

    @Test
    fun `probe - what the server asks when attackers and blockers are declared`() {
        val target = requireBridge()
        Dispatchers.setMain(Dispatchers.Default)
        runBlocking {
            val appName = username("app")
            val oppName = username("opp")
            val app = LiveSession(this, target)
            val opponent = LiveSession(this, target)
            app.connect(appName)
            opponent.connect(oppName)

            val jobs = mutableListOf<Job>()
            try {
                val created =
                    opponent.tables
                        .createTable(
                            CreateTableOptions(
                                name = TABLE_NAME,
                                gameType = DUEL,
                                deckType = FREEFORM,
                                players = listOf(SeatPlayerType.Human, SeatPlayerType.Human),
                                rated = false,
                                winsNeeded = 1,
                                freeMulligans = 0,
                                skillLevel = SkillLevel.Casual,
                                spectatorsAllowed = true,
                            ),
                        ).getOrThrow()
                say("table '${created.name}' created")

                val tableStates = CopyOnWriteArrayList<TableState>()
                jobs +=
                    launch {
                        opponent.tables
                            .observeTable(created.tableId, TableState(tableId = created.tableId))
                            .collect { tableStates += it }
                    }

                opponent.tables.joinTable(created.tableId, oppName, deck, playerType = SeatPlayerType.Human).getOrThrow()
                app.tables.joinTable(created.tableId, appName, deck, playerType = SeatPlayerType.Human).getOrThrow()
                awaitTable(tableStates) { it?.isReadyToStart == true }
                opponent.tables.startMatch(created.tableId).getOrThrow()
                val starting = awaitTable(tableStates) { it?.matchStarting != null }!!.matchStarting!!
                say("match starting; gameId=${starting.gameId}")

                jobs +=
                    launch {
                        opponent.games
                            .observeGame(starting.gameId, GameState(starting.gameId))
                            .collect { opponentState = it; report("opp", it) }
                    }
                opponent.games.joinGame(starting.gameId).getOrThrow()
                jobs += launch { driveOpponent(opponent.games, starting.gameId) }

                val viewModel = GameBoardViewModel(app.games, ManualPassPolicy)
                jobs +=
                    launch {
                        app.games
                            .observeGame(starting.gameId, GameState(starting.gameId))
                            .collect { appState = it; report("app", it) }
                    }
                viewModel.observe(starting.gameId)
                jobs += launch { driveApp(viewModel) }

                // Run to a budget rather than to an assertion: this is a probe.
                val deadline = System.currentTimeMillis() + BUDGET_MS
                while (System.currentTimeMillis() < deadline && !(sawAttackerPrompt && sawBlockerPrompt)) {
                    delay(POLL_MS)
                }

                say("=".repeat(70))
                say("RESULT  attackerPromptSeen=$sawAttackerPrompt  blockerPromptSeen=$sawBlockerPrompt")
                say("        final turn=${appState?.turn} step=${appState?.step}")
                say("        combat groups=${appState?.combat?.size ?: 0}")
                appState?.combat?.forEach { group ->
                    say("        group defender='${group.defenderName}' attackers=${group.attackerIds.size} blockers=${group.blockerIds.size}")
                }
                say("=".repeat(70))
            } finally {
                jobs.forEach { runCatching { it.cancelAndJoin() } }
                app.close()
                opponent.close()
                Dispatchers.resetMain()
            }
        }
    }

    /** Prints any prompt not printed before, with everything that might matter to the combat story. */
    private fun report(
        who: String,
        state: GameState,
    ) {
        val prompt = state.prompt ?: return
        val key = "$who|${prompt::class.simpleName}|${prompt.message}|${state.step}"
        if (!seen.addIfAbsent(key)) return

        val step = state.step
        say("[$who] step=$step turn=${state.turn} prompt=${prompt::class.simpleName} msg='${prompt.message}'")
        when (prompt) {
            is GamePrompt.Select -> {
                val o = prompt.options
                say("      options.text=${o.text.keys}  options.ids=${o.ids.mapValues { it.value.size }}")
                if (o.possibleAttackers.isNotEmpty()) {
                    sawAttackerPrompt = true
                    say("      *** possibleAttackers=${o.possibleAttackers.size} -> ${nameAll(state, o.possibleAttackers)}")
                }
                if (o.possibleBlockers.isNotEmpty()) {
                    sawBlockerPrompt = true
                    say("      *** possibleBlockers=${o.possibleBlockers.size} -> ${nameAll(state, o.possibleBlockers)}")
                }
                say("      playable=${state.playable.size}")
            }

            is GamePrompt.Target -> {
                val o = prompt.options
                say("      required=${prompt.isRequired} targetIds=${prompt.targetIds.size} cards=${prompt.cards.map { it.name }}")
                say("      options.text=${o.text.keys}  options.ids=${o.ids.mapValues { it.value.size }}")
                say("      targets -> ${nameAll(state, prompt.targetIds)}")
                if (step == PhaseStep.DeclareAttackers) sawAttackerPrompt = true
                if (step == PhaseStep.DeclareBlockers) sawBlockerPrompt = true
            }

            else -> say("      options.text=${prompt.optionsTextKeys()}")
        }
    }

    /** Resolves ids to names using whatever the snapshot draws, so the transcript is readable. */
    private fun nameAll(
        state: GameState,
        ids: List<String>,
    ): List<String> =
        ids.map { id ->
            state.players
                .flatMap { it.battlefield }
                .firstOrNull { it.card.id == id }
                ?.card
                ?.name
                ?: state.players.firstOrNull { it.playerId == id }?.name
                ?: id.take(8)
        }

    private fun GamePrompt.optionsTextKeys(): Set<String> =
        when (this) {
            is GamePrompt.Ask -> options.text.keys
            is GamePrompt.PlayMana -> options.text.keys
            is GamePrompt.PlayXMana -> options.text.keys
            is GamePrompt.GetAmount -> options.text.keys
            is GamePrompt.GetMultiAmount -> options.text.keys
            else -> emptySet()
        }

    /**
     * The attacking seat, over a raw client: play lands and Dragon Fodder, **attack with everything the
     * server offers**, and otherwise pass. It answers a declaration straight from `possibleAttackers`,
     * which is the whole reason this seat is not the ViewModel one.
     */
    private suspend fun driveOpponent(
        games: GameClient,
        gameId: String,
    ) {
        var lastKey: String? = null
        var lastAt = 0L
        while (true) {
            delay(POLL_MS)
            val state = opponentState ?: continue
            val prompt = state.prompt ?: continue
            val key = "${prompt::class.simpleName}|${prompt.message}"
            if (key == lastKey && System.currentTimeMillis() - lastAt < REANSWER_MS) continue
            lastKey = key
            lastAt = System.currentTimeMillis()

            when (prompt) {
                is GamePrompt.Ask -> games.answerAsk(gameId, answer = false)

                is GamePrompt.Select -> {
                    val attackers = prompt.options.possibleAttackers
                    val playable = state.playable
                    when {
                        // Attack with everything offered, then say done.
                        attackers.isNotEmpty() -> {
                            say("opp  -> declaring ${attackers.size} attacker(s)")
                            attackers.forEach { games.chooseTarget(gameId, it) }
                            games.cancelPrompt(gameId)
                        }
                        playable.isNotEmpty() -> games.playObject(gameId, playable.first().objectId)
                        else -> games.passPriority(gameId)
                    }
                }

                is GamePrompt.Target -> {
                    val pick = prompt.targetIds.firstOrNull()
                    if (pick != null) games.chooseTarget(gameId, pick) else games.cancelPrompt(gameId)
                }

                is GamePrompt.PlayMana -> {
                    val source = prompt.options.possibleTargets.firstOrNull()
                    if (source != null) games.playManaSource(gameId, source) else games.cancelPrompt(gameId)
                }

                else -> runCatching { games.cancelPrompt(gameId) }
            }
        }
    }

    /**
     * The app seat, through the board's own logic — it plays what the controls offer and passes
     * otherwise. It is **not** expected to block: recording what the board offers (or fails to offer)
     * at a declaration is one of the things this probe is for.
     */
    private suspend fun driveApp(viewModel: GameBoardViewModel) {
        var lastControls: PromptControlsUi? = null
        var lastAt = 0L
        while (true) {
            delay(POLL_MS)
            val state = viewModel.uiState.value
            val controls = state.controls ?: continue
            if (controls == lastControls && System.currentTimeMillis() - lastAt < REANSWER_MS) continue
            lastControls = controls
            lastAt = System.currentTimeMillis()

            val step = state.board.turn.step
            if (step == PhaseStep.DeclareBlockers || step == PhaseStep.DeclareAttackers) {
                say(
                    "app  ** at $step the board offers: ${controls::class.simpleName} " +
                        "buttons=${controls.buttons.map { it.label }} " +
                        "pickable=${controls.pickableObjectIds.size}",
                )
            }

            val action =
                when (controls) {
                    is PromptControlsUi.Priority -> {
                        val playable =
                            state.board.hand.cards.firstOrNull { it.objectId in controls.pickableObjectIds }
                        playable?.let { BoardAction.PlayObject(it.objectId) }
                            ?: controls.buttons.firstOrNull { it.action == BoardAction.PassPriority }?.action
                    }

                    is PromptControlsUi.Mana ->
                        controls.pickableObjectIds.firstOrNull()?.let(BoardAction::PlayManaSource)
                            ?: BoardAction.CancelPrompt

                    is PromptControlsUi.Targeting ->
                        controls.pickableObjectIds.firstOrNull()?.let(BoardAction::ChooseTarget)
                            ?: BoardAction.CancelPrompt

                    is PromptControlsUi.Choices ->
                        controls.buttons.firstOrNull { it.action == BoardAction.AnswerAsk(yes = false) }?.action
                            ?: controls.buttons.firstOrNull()?.action

                    is PromptControlsUi.Amount -> controls.amountRequest?.let { BoardAction.ChooseAmount(it.min) }
                    is PromptControlsUi.MultiAmount -> BoardAction.DistributeAmounts(controls.amountRows.map { it.min })
                    is PromptControlsUi.Notice -> null
                } ?: continue

            viewModel.act(action)
        }
    }

    private suspend fun awaitTable(
        states: List<TableState>,
        predicate: (TableState?) -> Boolean,
    ): TableState? =
        withTimeout(TABLE_MS) {
            while (!predicate(states.lastOrNull())) delay(POLL_MS)
            states.lastOrNull()
        }

    private fun say(line: String) {
        println("[combat-probe] $line")
    }

    private fun username(prefix: String) =
        prefix + "_" + UUID.randomUUID().toString().filter { it != '-' }.take(6)

    /** One live app→bridge session, assembled from production parts only. */
    private class LiveSession(
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
                                connected.completeExceptionally(AssertionError("login rejected: ${event.message}"))
                            else -> Unit
                        }
                    }
                }
            withTimeout(CONNECT_MS) { connected.await() }
        }

        suspend fun close() {
            runCatching { client.disconnect() }
            collector?.let { runCatching { it.cancelAndJoin() } }
        }
    }

    private fun requireBridge(): ServerTarget {
        val raw = System.getenv(BRIDGE_URL_ENV)
        Assume.assumeTrue("$BRIDGE_URL_ENV is not set; skipping the combat probe", !raw.isNullOrBlank())
        val authority = raw!!.trim().trimEnd('/').removePrefix("wss://").removePrefix("ws://")
        val separator = authority.lastIndexOf(':')
        return ServerTarget(
            host = authority.substring(0, separator),
            port = authority.substring(separator + 1).toInt(),
            secure = raw.startsWith("wss://"),
        )
    }

    /**
     * Creature-dense on purpose: Dragon Fodder is `{1}{R}` for **two** 1/1 Goblins, so both seats have
     * bodies within a couple of turns. §17's run failed to reach combat at all because its deck spent
     * nine turns drawing lands.
     */
    private val deck =
        Deck(
            id = DeckId("combat-probe"),
            name = "combat_probe",
            author = "combat-probe",
            main =
                listOf(
                    DeckEntry(cardName = "Mountain", setCode = "10E", collectorNumber = "376", quantity = 24),
                    DeckEntry(cardName = "Dragon Fodder", setCode = "ALA", collectorNumber = "97", quantity = 36),
                ),
        )

    private companion object {
        const val BRIDGE_URL_ENV = "BRIDGE_URL"
        const val TABLE_NAME = "combat_probe"
        const val DUEL = "Two Player Duel"
        const val FREEFORM = "Constructed - Freeform Unlimited"

        const val CONNECT_MS = 60_000L
        const val TABLE_MS = 120_000L
        const val BUDGET_MS = 420_000L
        const val POLL_MS = 300L
        const val REANSWER_MS = 4_000L
    }
}
