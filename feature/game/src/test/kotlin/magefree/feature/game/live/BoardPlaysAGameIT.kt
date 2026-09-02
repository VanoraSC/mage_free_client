package magefree.feature.game.live

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import magefree.feature.game.board.FakeCardCatalog
import magefree.feature.game.board.GameBoardUiState
import magefree.feature.game.board.GameBoardViewModel
import magefree.feature.game.board.ManualPassPolicy
import magefree.feature.game.board.PromptControlsUi
import magefree.feature.game.board.cardFor
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
 * The **live two-client run**: a real game, played from the board's own logic, against a
 * second real client, with **both** seats observable.
 *
 * ## Why this test exists in this shape
 *
 * the board was read-only, and a read-only seat **hard-blocks the game**: upstream's
 * `Mulligan.executeMulliganPhase` → `HumanPlayer.chooseMulligan` → `waitForResponse` is an unbounded
 * `response.wait()`, so a client that cannot answer stops the game for *both* players at the mulligan.
 * Battlefields populating, the stack filling and turns advancing have therefore only ever been covered
 * hermetically. This is the first run in which they can be covered live.
 *
 * **The seat under test is driven through [GameBoardViewModel] itself** — not through [GameClient]. Every
 * decision leaves as a [BoardAction] through `act`, exactly as a finger on the screen would produce it,
 * and every decision is *chosen from what the board's own [PromptControlsUi] offered*. So what this run
 * exercises is the logic, not a parallel script that happens to talk to the same server.
 * `docs/live-test-decklists.md` records why deep-game experiments belong inside this that builds the
 * surface rather than as standalone probes; this is that.
 *
 * ## The deck, and why this one
 *
 * The the requirements deck: 20× `Mountain` (`10E` #376), 20× `Forked Bolt` (`ROE` #146), 20× `Dragon
 * Fodder` (`ALA` #97). Forked Bolt costs `{R}`, is genuinely multi-target (*2 damage divided among one or
 * two targets*) and can point at a player, so it produces a **target prompt on turn one** — the step
 * The server rewinds. Deck resolution is by **(setCode, collectorNumber)**, never by name.
 *
 * ## What it drives, in order
 *
 * 1. both seats answer the mulligan and keep;
 * 2. the app seat plays a land, and passes priority — turns advance;
 * 3. the app seat **casts Forked Bolt and cancels at the target step**: the card returns to hand, the
 *    stack clears, and the Mountain is left untapped;
 * 4. the app seat **casts it again and lets it resolve**, choosing a target, confirming with the final
 *    *done*, and paying by tapping its own land;
 * 5. throughout, the opponent's board is sampled so the stack can be compared on **both** sides.
 *
 * **This is a harness, not a hermetic suite.** It is env-gated on `BRIDGE_URL`, so `:feature:game:check`
 * stays offline (an unset variable *skips* it), and it prints a transcript rather than only a tick:
 * ```
 * BRIDGE_URL=localhost:8080 ./gradlew :feature:game:testDebugUnitTest --tests '*BoardPlaysAGameIT*' --rerun-tasks -i
 * ```
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BoardPlaysAGameIT {
    /** Which decision the app seat is making right now. The policy below switches on it. */
    private enum class Phase {
        /** Answer the mulligan, keep, and get to a turn. */
        Opening,

        /** Play a land when one is offered; otherwise pass. */
        BuildMana,

        /** Begin casting Forked Bolt and **decline the target step** (the rewind). */
        CastAndCancel,

        /** Cast it again, choose a target, confirm, and pay. */
        RecastAndResolve,

        /** Nothing more to do: keep passing so the game stays alive while the run finishes. */
        PassOnly,
    }

    @Volatile
    private var phase = Phase.Opening

    @Volatile
    private var opponentState: GameState? = null

    /** Set once the app seat has sent a pick for the recast, so the next step is the final *done*. */
    @Volatile
    private var pickedForRecast = false

    /**
     * The board **at the instant the cast began**, captured in the drive loop rather than by the script.
     *
     * The first run of this harness compared against a snapshot taken when the *phase* was armed, and by
     * the time the cast actually happened several turns had passed and the hand had grown — so "the card
     * left your hand" read as 6 → 6 and failed for a reason that had nothing to do with the board. The
     * rewind is a claim about one moment, so it is measured from that moment.
     */
    @Volatile
    private var handAtCast = -1

    @Volatile
    private var untappedAtCast = -1

    private val transcript = CopyOnWriteArrayList<String>()

    @Test
    fun `plays a real game from the board - mulligan, land, cast, cancel, recast, resolve, pass`() {
        val target = requireBridge()
        Dispatchers.setMain(Dispatchers.Default)
        runBlocking {
            val appName = username("app")
            val oppName = username("opp")
            val app = LiveSession(this, target)
            val opponent = LiveSession(this, target)
            app.connect(appName)
            opponent.connect(oppName)

            var tableId: String? = null
            val jobs = mutableListOf<Job>()
            try {
                // --- the table -------------------------------------------------------------------------
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
                tableId = created.tableId
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
                awaitTable(tableStates, "both seats to be ready") { it?.isReadyToStart == true }
                say("both seats filled: ${tableStates.last().seats.map { it.name }}")

                opponent.tables.startMatch(created.tableId).getOrThrow()
                val starting =
                    awaitTable(tableStates, "MatchStarting") { it?.matchStarting != null }!!.matchStarting!!
                say("match starting; gameId=${starting.gameId}")

                // --- the two seats ---------------------------------------------------------------------
                //
                // The app seat is a real ViewModel. Nothing below ever calls a GameClient verb for it.
                val viewModel = GameBoardViewModel(app.games, ManualPassPolicy, FakeCardCatalog())

                jobs +=
                    launch {
                        opponent.games.observeGame(starting.gameId, GameState(starting.gameId)).collect { opponentState = it }
                    }
                opponent.games.joinGame(starting.gameId).getOrThrow()
                jobs += launch { driveOpponent(opponent.games, starting.gameId) }

                viewModel.observe(starting.gameId)
                jobs += launch { driveApp(viewModel) }

                // --- 1. the mulligan, and a turn ---------------------------------------------------------
                awaitApp(viewModel, "the opening hand") { it.board.hand.count > 0 }
                val opening = awaitApp(viewModel, "the mulligan to be answered and a turn to start") { it.board.turn.number >= 1 }
                say("opening hand kept: ${opening.board.hand.count} cards, turn ${opening.board.turn.number}")
                check(opening.board.hand.count == KEPT_HAND) {
                    "expected a kept opening hand of $KEPT_HAND, saw ${opening.board.hand.count}"
                }

                // --- 2. a land on the battlefield, and turns advancing -----------------------------------
                phase = Phase.BuildMana
                val landed =
                    awaitApp(viewModel, "a land on our own battlefield", LONG_MS) {
                        it.board.viewerSeat
                            ?.battlefield
                            ?.any { p -> p.card.name == LAND } == true
                    }
                say("land played: our battlefield = ${landed.board.viewerSeat?.battlefield?.map { it.card.name }}")

                val turnAtLand = landed.board.turn.number
                val advanced = awaitApp(viewModel, "the turn to advance past $turnAtLand", LONG_MS) { it.board.turn.number > turnAtLand }
                say("turns advancing: $turnAtLand -> ${advanced.board.turn.number} (priority passed both ways)")

                // --- 3. cast, and cancel at the target step ----------------------------------------------
                awaitApp(viewModel, "Forked Bolt in hand with an untapped land", LONG_MS) { state ->
                    state.board.hand.cards
                        .any { it.card.name == BOLT } &&
                        untappedLands(state) >= 1
                }
                val beforeCast = viewModel.uiState.value
                say(
                    "READY TO CAST hand=${beforeCast.board.hand.count} stack=${beforeCast.board.stack.count} " +
                        "untappedLands=${untappedLands(beforeCast)}",
                )

                phase = Phase.CastAndCancel
                // The board reaches the target step: the spell is on the stack already.
                val atTargets =
                    awaitApp(viewModel, "the target prompt for Forked Bolt", LONG_MS) {
                        it.controls is PromptControlsUi.Targeting && it.cast != null
                    }
                say(
                    "AT TARGETS   hand=${atTargets.board.hand.count} stack=${atTargets.board.stack.count} " +
                        "cast=${atTargets.cast} prompt='${atTargets.controls?.message}' " +
                        "candidates=${atTargets.controls?.pickableObjectIds?.size}",
                )
                say("             opponent sees stack=${opponentState?.stack?.map { it.name }}")
                check(atTargets.board.hand.count == handAtCast - 1) {
                    "the cast must have moved the card out of hand: $handAtCast -> ${atTargets.board.hand.count}"
                }
                check(atTargets.board.stack.count >= 1) { "the spell must be on the stack while targets are chosen" }

                // The policy cancels; assert the server's rewind.
                val rewound =
                    awaitApp(viewModel, "the server to rewind the cast", LONG_MS) {
                        it.board.hand.count == handAtCast && it.board.stack.isEmpty
                    }
                say(
                    "AFTER CANCEL hand=${rewound.board.hand.count} stack=${rewound.board.stack.count} " +
                        "untappedLands=${untappedLands(rewound)}",
                )
                say("             opponent sees stack=${opponentState?.stack?.map { it.name }} (the rewind is not pushed)")
                check(untappedLands(rewound) == untappedAtCast) {
                    "cancelling must leave mana unspent: $untappedAtCast -> ${untappedLands(rewound)}"
                }

                // --- 4. cast it again, and let it resolve ------------------------------------------------
                val opponentLifeBefore =
                    rewound.board.opponentSeats
                        .firstOrNull()
                        ?.life ?: 0
                phase = Phase.RecastAndResolve
                pickedForRecast = false
                val resolved =
                    awaitApp(viewModel, "the recast Forked Bolt to resolve", VERY_LONG_MS) { state ->
                        val opponentLife =
                            state.board.opponentSeats
                                .firstOrNull()
                                ?.life ?: opponentLifeBefore
                        opponentLife < opponentLifeBefore && state.board.stack.isEmpty
                    }
                say(
                    "RESOLVED     opponent life $opponentLifeBefore -> ${resolved.board.opponentSeats.firstOrNull()?.life}, " +
                        "stack=${resolved.board.stack.count}, our hand=${resolved.board.hand.count}",
                )
                say("             opponent's own view: life=${opponentState?.viewer?.life} stack=${opponentState?.stack?.size}")

                phase = Phase.PassOnly
                delay(SETTLE_MS)
                say("opponent board after resolution: stack=${opponentState?.stack?.map { it.name }}")
                say("RUN COMPLETE")
            } finally {
                jobs.forEach { runCatching { it.cancelAndJoin() } }
                tableId?.let { runCatching { opponent.tables.removeTable(it) } }
                app.close()
                opponent.close()
                Dispatchers.resetMain()
            }
        }
    }

    // ---- the app seat: every decision goes out as a BoardAction ------------------------------------------

    /**
     * Drives the seat under test by repeatedly asking the **board's own controls** what may be done and
     * choosing among them, then sending the choice through [GameBoardViewModel.act].
     *
     * It answers one question at a time and waits for the question to change, which mirrors both the
     * server (it blocks on each answer) and a person (they cannot press two buttons at once).
     */
    private suspend fun driveApp(viewModel: GameBoardViewModel) {
        var lastAnswered: PromptControlsUi? = null
        var lastAnsweredAt = 0L
        while (true) {
            delay(POLL_MS)
            val state = viewModel.uiState.value
            val controls = state.controls ?: continue
            // **Not a plain equality check.** [PromptControlsUi] is a data class, so the *same question
            // asked again* — "Play instants and activated abilities", every round of priority, all game —
            // compares equal to the one just answered. A loop that skipped those would answer priority
            // once and then sit forever watching an identical prompt, which is exactly how the first run
            // of this harness stalled. A person in front of the screen has the same information and
            // resolves it the same way: if the control is still there a beat later, press it again.
            val sameAsBefore = controls == lastAnswered && System.currentTimeMillis() - lastAnsweredAt < REANSWER_MS
            if (sameAsBefore) continue
            checkSomethingToPress(state, controls)
            val action = decide(state, controls) ?: continue
            lastAnswered = controls
            lastAnsweredAt = System.currentTimeMillis()
            if (action is BoardAction.PlayObject && phase == Phase.CastAndCancel) {
                handAtCast = state.board.hand.count
                untappedAtCast = untappedLands(state)
                say("app  -> beginning the cast with hand=$handAtCast untappedLands=$untappedAtCast")
            }
            say("app  -> $action   (${controls::class.simpleName}: '${controls.message?.take(TRIM)}')")
            viewModel.act(action)
        }
    }

    /**
     * What a player would press, given what the board is offering.
     *
     * Everything it picks comes from [PromptControlsUi] — the ids from `pickableObjectIds`, the answers
     * from `buttons`. It never reads `GameState.playable` directly and never constructs an action the
     * controls did not offer, so a control the board fails to publish is a decision this cannot make.
     */
    private fun decide(
        state: GameBoardUiState,
        controls: PromptControlsUi,
    ): BoardAction? =
        when (controls) {
            is PromptControlsUi.Priority -> {
                val hand = state.board.hand.cards
                val playableInHand = hand.filter { it.objectId in controls.pickableObjectIds }
                val land = playableInHand.firstOrNull { it.card.name == LAND }
                val bolt = playableInHand.firstOrNull { it.card.name == BOLT }
                when {
                    phase == Phase.BuildMana && land != null -> BoardAction.PlayObject(land.objectId)
                    phase == Phase.CastAndCancel && bolt != null -> BoardAction.PlayObject(bolt.objectId)
                    phase == Phase.RecastAndResolve && bolt != null -> BoardAction.PlayObject(bolt.objectId)
                    // The pass button the controls themselves offer — through the policy seam.
                    else -> controls.buttons.firstOrNull { it.action == BoardAction.PassPriority }?.action
                }
            }

            is PromptControlsUi.Targeting ->
                when {
                    // Decline the target step and let the server rewind the whole cast.
                    phase == Phase.CastAndCancel -> BoardAction.CancelPrompt
                    phase == Phase.RecastAndResolve && !pickedForRecast ->
                        pickATarget(state, controls)?.also { pickedForRecast = true }
                    // The confirmation *is* the final done — every pick was already sent.
                    phase == Phase.RecastAndResolve -> BoardAction.FinishTargeting
                    else -> pickATarget(state, controls)
                }

            is PromptControlsUi.Mana ->
                controls.pickableObjectIds.firstOrNull()?.let(BoardAction::PlayManaSource)
                    ?: BoardAction.CancelPrompt

            is PromptControlsUi.Choices ->
                // Keep the opening hand: the *right* button, which the server labels itself ("Keep").
                controls.buttons.firstOrNull { it.action == BoardAction.AnswerAsk(yes = false) }?.action
                    ?: controls.buttons.firstOrNull()?.action

            is PromptControlsUi.Amount -> controls.amountRequest?.let { BoardAction.ChooseAmount(it.min) }
            is PromptControlsUi.MultiAmount -> BoardAction.DistributeAmounts(controls.amountRows.map { it.min })
            // This run is about casting and cancelling, not combat (has its own live run), so
            // a declaration is simply closed with its own *done* — the equivalent of declaring nothing.
            is PromptControlsUi.Declaration -> BoardAction.FinishTargeting
            // Nothing knows a valid answer; the board offers no control and neither does this.
            is PromptControlsUi.Notice -> null
        }

    /**
     * Choose a target **the way the screen offers one** — and only that way.
     *
     * This deliberately does *not* reach into `pickableObjectIds` first. The blocking defect this
     * shipped and had caught on a device was a Target prompt whose candidates were **players**: the
     * projection held the ids the whole time, so a harness that read them directly answered the prompt
     * happily while the screen showed nothing to press. Answering through the same two surfaces a finger
     * has — a promoted button in the panel, or a card the board actually draws — is what makes this run
     * able to see that class of gap at all.
     */
    private fun pickATarget(
        state: GameBoardUiState,
        controls: PromptControlsUi.Targeting,
    ): BoardAction? {
        val fromPanel = controls.buttons.map { it.action }.filterIsInstance<BoardAction.ChooseTarget>()
        if (fromPanel.isNotEmpty()) return fromPanel.first()
        val onTheBoard = controls.pickableObjectIds.firstOrNull { state.board.cardFor(it) != null }
        return onTheBoard?.let(BoardAction::ChooseTarget)
    }

    /**
     * The live form of "no prompt may leave the player with nothing to press".
     *
     * An answerable prompt that offers no button, no candidate card, no number **and** nothing the board
     * draws is a stall — the game stops for both seats, which is precisely what happened on device. A
     * timeout would eventually report it as "waiting for X", naming the symptom; this names the cause at
     * the moment it occurs.
     */
    private fun checkSomethingToPress(
        state: GameBoardUiState,
        controls: PromptControlsUi,
    ) {
        if (!controls.isAnswerable) return
        val tappableOnBoard = controls.pickableObjectIds.any { state.board.cardFor(it) != null }
        val answerable =
            controls.buttons.isNotEmpty() ||
                controls.candidateCards.isNotEmpty() ||
                controls.amountRequest != null ||
                controls.amountRows.isNotEmpty() ||
                tappableOnBoard
        check(answerable) {
            "DEAD PROMPT: ${controls::class.simpleName} '${controls.message}' offers nothing to press — " +
                "pickable=${controls.pickableObjectIds} none of which the board draws"
        }
    }

    // ---- the opponent seat: a plain client, so the app seat has someone to play against -------------------

    private suspend fun driveOpponent(
        games: GameClient,
        gameId: String,
    ) {
        var lastAnswered: GamePrompt? = null
        var lastAnsweredAt = 0L
        while (true) {
            delay(POLL_MS)
            val prompt = opponentState?.prompt ?: continue
            // Same reasoning as [driveApp]: the server asks the identical question every round of
            // priority, so equality alone would answer once and then stall the whole game.
            if (prompt == lastAnswered && System.currentTimeMillis() - lastAnsweredAt < REANSWER_MS) continue
            lastAnswered = prompt
            lastAnsweredAt = System.currentTimeMillis()
            when (prompt) {
                // Keep, so the game starts promptly.
                is GamePrompt.Ask -> games.answerAsk(gameId, answer = false)
                is GamePrompt.Select -> games.passPriority(gameId)
                is GamePrompt.Target -> {
                    val candidate = prompt.targetIds.firstOrNull() ?: prompt.cards.firstOrNull()?.id
                    if (prompt.isRequired && candidate != null) games.chooseTarget(gameId, candidate) else games.cancelPrompt(gameId)
                }

                is GamePrompt.ChooseChoice ->
                    prompt.choices.firstOrNull()?.let { games.chooseChoice(gameId, it.key) } ?: games.cancelPrompt(gameId)

                is GamePrompt.ChooseAbility -> games.cancelPrompt(gameId)
                is GamePrompt.ChoosePile -> games.choosePile(gameId, first = true)
                is GamePrompt.GetAmount -> games.chooseAmount(gameId, prompt.min)
                is GamePrompt.PlayMana -> games.cancelPrompt(gameId)
                is GamePrompt.PlayXMana -> games.announceX(gameId, 0)
                is GamePrompt.GetMultiAmount -> games.distributeAmounts(gameId, prompt.entries.map { it.min })
                is GamePrompt.Unrecognised -> Unit
            }
            say("opp  -> answered ${prompt::class.simpleName} '${prompt.message.take(TRIM)}'")
        }
    }

    // ---- helpers ------------------------------------------------------------------------------------------

    /** How many of our own lands are untapped — the mana-unspent check. */
    private fun untappedLands(state: GameBoardUiState): Int =
        state.board.viewerSeat
            ?.battlefield
            .orEmpty()
            .count { it.card.name == LAND && !it.isTapped }

    private suspend fun awaitApp(
        viewModel: GameBoardViewModel,
        what: String,
        timeoutMs: Long = DEFAULT_MS,
        predicate: (GameBoardUiState) -> Boolean,
    ): GameBoardUiState {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val state = viewModel.uiState.value
            if (predicate(state)) return state
            delay(POLL_MS)
        }
        val last = viewModel.uiState.value
        throw AssertionError(
            "timed out after ${timeoutMs}ms waiting for $what; last board was " +
                "turn=${last.board.turn.number} hand=${last.board.hand.count} stack=${last.board.stack.count} " +
                "priority=${last.board.priority.headline} controls=${last.controls?.let { it::class.simpleName }} " +
                "battlefield=${last.board.viewerSeat?.battlefield?.map { it.card.name }}",
        )
    }

    private suspend fun awaitTable(
        states: List<TableState>,
        what: String,
        timeoutMs: Long = TABLE_MS,
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

    private fun say(line: String) {
        transcript += "[live] $line"
        println("[live] $line")
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
                            is SessionEvent.VersionUnsupported ->
                                connected.completeExceptionally(AssertionError("version rejected: ${event.detail}"))
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
        Assume.assumeTrue("$BRIDGE_URL_ENV is not set; skipping the live board run", !raw.isNullOrBlank())
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
     * The the requirements deck. Resolution is by **(setCode, collectorNumber)**, never by name — a wrong
     * pair fails with "Card not found" even when the name is spelled correctly.
     */
    private val deck =
        Deck(
            id = DeckId("board-bolt"),
            name = "board_0057_bolt",
            author = "board-play-it",
            main =
                listOf(
                    DeckEntry(cardName = "Mountain", setCode = "10E", collectorNumber = "376", quantity = 20),
                    DeckEntry(cardName = "Forked Bolt", setCode = "ROE", collectorNumber = "146", quantity = 20),
                    DeckEntry(cardName = "Dragon Fodder", setCode = "ALA", collectorNumber = "97", quantity = 20),
                ),
        )

    private companion object {
        const val BRIDGE_URL_ENV = "BRIDGE_URL"
        const val TABLE_NAME = "story0057_board"
        const val DUEL = "Two Player Duel"

        /** The only validator that is unconditionally valid (`deckMinSize == 0`). */
        const val FREEFORM = "Constructed - Freeform Unlimited"

        const val LAND = "Mountain"
        const val BOLT = "Forked Bolt"

        /** A kept opening hand in this format. */
        const val KEPT_HAND = 7

        const val CONNECT_MS = 60_000L
        const val TABLE_MS = 120_000L
        const val DEFAULT_MS = 90_000L
        const val LONG_MS = 180_000L
        const val VERY_LONG_MS = 240_000L
        const val SETTLE_MS = 5_000L
        const val POLL_MS = 300L

        /** How long an identical question must sit unanswered before it is treated as asked again. */
        const val REANSWER_MS = 4_000L
        const val TRIM = 70
    }
}
