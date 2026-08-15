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
import magefree.feature.game.board.BoardUi
import magefree.feature.game.board.CombatRole
import magefree.feature.game.board.GameBoardUiState
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
import magefree.network.ktor.KtorBridgeClient
import magefree.network.table.CreateTableOptions
import magefree.network.table.SeatPlayerType
import magefree.network.table.TableClient
import magefree.network.table.TableClients
import magefree.network.table.TableState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * **Story 0061's live verification**: combat declared end to end, through the real
 * [GameBoardViewModel], from the board's **own** controls.
 *
 * ## What makes this a verification and not a probe
 *
 * `CombatProbeIT` and `CombatPairingProbeIT` answered *what does the server ask* by driving raw
 * [GameClient]s and printing what arrived. This run answers a different question — *can the board
 * actually play combat?* — and so it is bound by two rules:
 *
 * 1. **Both seats are `GameBoardViewModel`s.** Combat is two assignment problems that never belong to
 *    the same player at the same moment (§7.4), so the only way to exercise both through the board is to
 *    have a board on each side: whichever seat is attacking declares attackers, and the other declares
 *    blockers on the same turn.
 * 2. **Nothing is answered from `GameState`.** Every id comes from [PromptControlsUi.pickableObjectIds]
 *    and every reply from an action the controls themselves published. The raw snapshot is observed, but
 *    only to *record* facts (`playable` at the moment of a declaration, the resulting `combat` groups)
 *    and to identify the one `Ask` that must be answered affirmatively (§7.6). A harness that reached
 *    into `possibleAttackers` directly would pass happily against the very defect this story fixes —
 *    the ids were in the projection all along; what was missing was a surface.
 *
 * ## What it asserts
 *
 * - a declaration was reached, and the board offered creatures to declare — **while `playable` was
 *   empty**, which is the live confirmation of §7.2 and the whole shape of the defect;
 * - the creature the board declared is the one the server then reports as attacking, in its own
 *   `CombatGroup` — so the tap really did reach the game and not merely the log;
 * - the board was never in both roles at once.
 *
 * **Reaching combat is nondeterministic** — one run in six for the original probe. A run that expires
 * before a declaration fails with a message saying so; re-run it rather than reading anything into it.
 * Everything the run saw is printed either way.
 *
 * ## The deck and the three stalls (§7.6)
 * 24× `Mountain` (`10E` #376) + 36× `Dragon Fodder` (`ALA` #97): two 1/1 Goblins for `{1}{R}`, so both
 * seats have bodies within a couple of turns. Resolution is by **(setCode, collectorNumber)**. The three
 * stalls that otherwise eat a run are all handled below: the mana-pool *"Pass anyway?"* is answered
 * **affirmatively** (it is the only `Ask` carrying `autoAnswerMessage`), lands are played before spells
 * (`manaCost` is null for a land), and mana is paid from what the *mana prompt* offers.
 *
 * Env-gated on `BRIDGE_URL` so the hermetic gate stays offline:
 * ```
 * BRIDGE_URL=localhost:8080 ./gradlew :feature:game:testDebugUnitTest \
 *   --tests '*CombatDeclarationIT*' --rerun-tasks -i
 * ```
 */
class CombatDeclarationIT {
    /** The raw snapshot per seat — **recorded, never answered from**. See the class docs. */
    private val snapshots = ConcurrentHashMap<String, GameState>()

    /** What the board itself offered, per seat, at the moment of each declaration. */
    private val transcript = CopyOnWriteArrayList<String>()

    /** Creatures this harness has already declared, per seat and step, so a re-offer is not re-tapped. */
    private val declared = ConcurrentHashMap<String, MutableSet<String>>()

    @Volatile private var sawAttackerDeclaration = false

    @Volatile private var sawBlockerDeclaration = false

    @Volatile private var sawPairingQuestion = false

    @Volatile private var declaredWhilePlayableEmpty = false

    @Volatile private var everOfferedBothRoles = false

    /** The creature this run declared as an attacker, and whether the server then agreed it attacks. */
    @Volatile private var declaredAttackerId: String? = null

    @Volatile private var declaredAttackerConfirmed = false

    @Test
    fun `combat is declared from the board's own controls`() {
        val target = requireBridge()
        Dispatchers.setMain(Dispatchers.Default)
        runBlocking {
            val aName = username("boarda")
            val bName = username("boardb")
            val a = LiveSession(this, target)
            val b = LiveSession(this, target)
            a.connect(aName)
            b.connect(bName)

            val jobs = mutableListOf<Job>()
            try {
                val created =
                    a.tables
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

                val tableStates = CopyOnWriteArrayList<TableState>()
                jobs +=
                    launch {
                        a.tables
                            .observeTable(created.tableId, TableState(tableId = created.tableId))
                            .collect { tableStates += it }
                    }
                a.tables.joinTable(created.tableId, aName, deck, playerType = SeatPlayerType.Human).getOrThrow()
                b.tables.joinTable(created.tableId, bName, deck, playerType = SeatPlayerType.Human).getOrThrow()
                awaitTable(tableStates) { it?.isReadyToStart == true }
                a.tables.startMatch(created.tableId).getOrThrow()
                val starting = awaitTable(tableStates) { it?.matchStarting != null }!!.matchStarting!!
                say("game ${starting.gameId}")

                for ((who, session) in listOf("A" to a, "B" to b)) {
                    // The raw stream, for the record only.
                    jobs +=
                        launch {
                            session.games
                                .observeGame(starting.gameId, GameState(starting.gameId))
                                .collect {
                                    snapshots[who] = it
                                    noteCombat(who, it)
                                }
                        }
                    val viewModel = GameBoardViewModel(session.games, ManualPassPolicy)
                    viewModel.observe(starting.gameId)
                    jobs += launch { drive(who, viewModel) }
                }

                // Kept running past the three asserted facts to give the **pairing** question room to
                // arrive: with two attackers on the board a blocker could block either, which is
                // exactly when `selectBlockers` stops assigning silently and asks (§7.5). It is not
                // asserted, because whether it is ever ambiguous in a given game is upstream's call and
                // not this board's — the point is that the board answers it if it comes.
                val deadline = System.currentTimeMillis() + BUDGET_MS
                while (System.currentTimeMillis() < deadline &&
                    !(sawAttackerDeclaration && sawBlockerDeclaration && declaredAttackerConfirmed && sawPairingQuestion)
                ) {
                    delay(POLL_MS)
                }

                say("=".repeat(72))
                transcript.forEach(::say)
                say("-".repeat(72))
                say("attacker declaration offered by the board : $sawAttackerDeclaration")
                say("blocker  declaration offered by the board : $sawBlockerDeclaration")
                say("declared while `playable` was empty       : $declaredWhilePlayableEmpty")
                say("a pairing Target arrived mid-declaration  : $sawPairingQuestion")
                say("the server confirmed our attacker attacks : $declaredAttackerConfirmed")
                say("both roles offered at once (must be false): $everOfferedBothRoles")
                snapshots["A"]?.let { s ->
                    say("final: turn=${s.turn} step=${s.step} combatGroups=${s.combat.size}")
                    s.combat.forEach {
                        say("  group defender='${it.defenderName}' attackers=${it.attackerIds.size} blockers=${it.blockerIds.size}")
                    }
                }
                say("=".repeat(72))
            } finally {
                jobs.forEach { runCatching { it.cancelAndJoin() } }
                a.close()
                b.close()
                Dispatchers.resetMain()
            }
        }

        assertTrue(
            "no declaration was reached inside the budget — reaching combat is nondeterministic (§7.6), so re-run rather " +
                "than reading anything into this",
            sawAttackerDeclaration,
        )
        assertTrue(
            "the board offered a declaration while `playable` was empty and still had creatures to tap — this is the " +
                "defect 0061 fixes, so a run that never saw it has proved nothing",
            declaredWhilePlayableEmpty,
        )
        assertTrue(
            "the creature declared from the board's own controls must be the one the server reports as attacking",
            declaredAttackerConfirmed,
        )
        assertEquals("the board must never be in both of combat's roles at once (§7.4)", false, everOfferedBothRoles)
    }

    /** Records what the *server* says about combat, so the board's claims can be checked against it. */
    private fun noteCombat(
        who: String,
        state: GameState,
    ) {
        val attacker = declaredAttackerId ?: return
        if (state.combat.any { attacker in it.attackerIds }) {
            if (!declaredAttackerConfirmed) {
                val group = state.combat.first { attacker in it.attackerIds }
                transcript += "[$who] the server agrees: our declared attacker is in a CombatGroup against '${group.defenderName}'"
            }
            declaredAttackerConfirmed = true
        }
    }

    /**
     * One seat, driven **only** through what the board publishes.
     *
     * The single place the raw snapshot is consulted for a decision is the mana-pool *"Pass anyway?"*
     * (§7.6): it must be answered affirmatively or the game never advances, and the option that
     * identifies it is not something the controls carry. That is a harness limitation, not a board one —
     * a player reads the question.
     */
    private suspend fun drive(
        who: String,
        viewModel: GameBoardViewModel,
    ) {
        var lastControls: PromptControlsUi? = null
        var lastAt = 0L
        while (true) {
            delay(POLL_MS)
            val state = viewModel.uiState.value
            val controls = state.controls ?: continue
            if (controls == lastControls && System.currentTimeMillis() - lastAt < REANSWER_MS) continue
            lastControls = controls
            lastAt = System.currentTimeMillis()

            val action = decide(who, state, controls) ?: continue
            viewModel.act(action)
        }
    }

    private fun decide(
        who: String,
        state: GameBoardUiState,
        controls: PromptControlsUi,
    ): BoardAction? =
        when (controls) {
            is PromptControlsUi.Declaration -> declare(who, state, controls)

            is PromptControlsUi.Targeting -> {
                // A target arriving while a declaration is in flight **is** the pairing question
                // upstream asks when the choice is genuinely ambiguous (§7.5).
                state.declaration?.let { declaration ->
                    if (!sawPairingQuestion) {
                        transcript +=
                            "[$who] pairing question during ${declaration.role}: '${controls.message}' " +
                            "about '${declaration.pairingCreatureName}', ${controls.pickableObjectIds.size} candidate(s) " +
                            "+ ${controls.buttons.count { it.action is BoardAction.ChooseTarget }} in the panel"
                    }
                    sawPairingQuestion = true
                }
                controls.pickableObjectIds.firstOrNull()?.let(BoardAction::ChooseTarget)
                    ?: controls.buttons
                        .map { it.action }
                        .filterIsInstance<BoardAction.ChooseTarget>()
                        .firstOrNull()
                    ?: BoardAction.FinishTargeting
            }

            is PromptControlsUi.Priority -> {
                // Lands before spells (§7.6): a seat that casts first strands itself mid-payment and
                // silently retries forever. `manaCost` is null for a land, which is the only signal.
                val offered =
                    state.board.hand.cards
                        .filter { it.objectId in controls.pickableObjectIds }
                val land = offered.firstOrNull { it.card.display.manaCost == null }
                val spell = offered.firstOrNull { it.card.display.manaCost != null }
                when {
                    land != null -> BoardAction.PlayObject(land.objectId)
                    spell != null -> BoardAction.PlayObject(spell.objectId)
                    else -> controls.buttons.firstOrNull { it.action == BoardAction.PassPriority }?.action
                }
            }

            // The sources come from the mana prompt's own offer, never from `possibleTargets` (§7.6).
            is PromptControlsUi.Mana ->
                controls.pickableObjectIds.firstOrNull()?.let(BoardAction::PlayManaSource)
                    ?: BoardAction.CancelPrompt

            is PromptControlsUi.Choices -> {
                // The one raw-state read: "You still have mana in your mana pool… Pass anyway?" is the
                // only `Ask` carrying `autoAnswerMessage`, and answering it negatively loops forever.
                val passAnyway =
                    (snapshots[who]?.prompt as? GamePrompt.Ask)?.options?.text?.containsKey(AUTO_ANSWER) == true
                controls.buttons.firstOrNull { it.action == BoardAction.AnswerAsk(yes = passAnyway) }?.action
                    ?: controls.buttons.firstOrNull()?.action
            }

            is PromptControlsUi.Amount -> controls.amountRequest?.let { BoardAction.ChooseAmount(it.min) }
            is PromptControlsUi.MultiAmount -> BoardAction.DistributeAmounts(controls.amountRows.map { it.min })
            is PromptControlsUi.Notice -> null
        }

    /**
     * Declare one creature the **board** offered, or close the declaration when it has offered nothing
     * new.
     *
     * The already-declared set is this harness's own bookkeeping: upstream re-offers a creature that has
     * been declared (selecting it again is how a player takes it back), so a loop that simply took the
     * first candidate every time would toggle one creature in and out forever.
     */
    private fun declare(
        who: String,
        state: GameBoardUiState,
        controls: PromptControlsUi.Declaration,
    ): BoardAction {
        val snapshot = snapshots[who]
        val playableCount = snapshot?.playable?.size ?: -1
        val alreadyDone = declared.getOrPut("$who|${controls.role}|${state.board.turn.number}") { mutableSetOf() }
        val next = controls.pickableObjectIds.firstOrNull { it !in alreadyDone }

        when (controls.role) {
            CombatRole.Attacking -> sawAttackerDeclaration = true
            CombatRole.Blocking -> sawBlockerDeclaration = true
        }
        // The board publishes one role's candidates; the other role's must be absent from the same
        // controls. There is only one set to read, so "both at once" can only appear as a `Select`
        // carrying both keys — which is checked against the raw snapshot here.
        val raw = (snapshot?.prompt as? GamePrompt.Select)?.options
        if (raw != null && raw.possibleAttackers.isNotEmpty() && raw.possibleBlockers.isNotEmpty()) {
            everOfferedBothRoles = true
        }

        if (alreadyDone.isEmpty()) {
            transcript +=
                "[$who] turn ${state.board.turn.number} ${state.board.turn.phaseLabel}: the board offers " +
                "${controls.role} with ${controls.pickableObjectIds.size} creature(s) to tap, " +
                "buttons=${controls.buttons.map { it.label }}, and the server's `playable` holds $playableCount"
        }

        if (next == null) return BoardAction.FinishTargeting
        if (playableCount == 0) declaredWhilePlayableEmpty = true
        alreadyDone += next
        if (controls.role == CombatRole.Attacking && declaredAttackerId == null) declaredAttackerId = next
        val name = state.board.cardName(next)
        // ASCII only in the transcript: it is read through a console whose code page mangles anything else.
        transcript += "[$who] ${controls.role}: declaring '$name' via the board's own '${controls.actionLabelFor(next)}'"
        return controls.actionFor(next)!!
    }

    private fun BoardUi.cardName(objectId: String): String =
        (listOfNotNull(viewerSeat) + opponentSeats)
            .flatMap { it.battlefield }
            .firstOrNull { it.objectId == objectId }
            ?.card
            ?.name
            ?: objectId.take(8)

    private suspend fun awaitTable(
        states: List<TableState>,
        predicate: (TableState?) -> Boolean,
    ): TableState? =
        withTimeout(TABLE_MS) {
            while (!predicate(states.lastOrNull())) delay(POLL_MS)
            states.lastOrNull()
        }

    private fun say(line: String) {
        println("[combat-declaration] $line")
    }

    private fun username(prefix: String) =
        prefix + "_" +
            UUID
                .randomUUID()
                .toString()
                .filter { it != '-' }
                .take(6)

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
        Assume.assumeTrue("$BRIDGE_URL_ENV is not set; skipping the live combat run", !raw.isNullOrBlank())
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

    /** `docs/live-test-decklists.md` §5 — creature-dense on purpose, so combat is reachable at all. */
    private val deck =
        Deck(
            id = DeckId("combat-declaration"),
            name = "combat_declaration",
            author = "combat-declaration",
            main =
                listOf(
                    DeckEntry(cardName = "Mountain", setCode = "10E", collectorNumber = "376", quantity = 24),
                    DeckEntry(cardName = "Dragon Fodder", setCode = "ALA", collectorNumber = "97", quantity = 36),
                ),
        )

    private companion object {
        /** Options key present only on upstream's auto-answerable "Pass anyway?" question (§7.6). */
        const val AUTO_ANSWER = "autoAnswerMessage"

        const val BRIDGE_URL_ENV = "BRIDGE_URL"
        const val TABLE_NAME = "combat_declaration"
        const val DUEL = "Two Player Duel"
        const val FREEFORM = "Constructed - Freeform Unlimited"

        const val CONNECT_MS = 60_000L
        const val TABLE_MS = 120_000L
        const val BUDGET_MS = 780_000L
        const val POLL_MS = 200L
        const val REANSWER_MS = 1_200L
    }
}
