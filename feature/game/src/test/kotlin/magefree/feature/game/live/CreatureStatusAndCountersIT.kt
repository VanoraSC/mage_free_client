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
import magefree.feature.game.board.PermanentUi
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
import org.junit.Assume
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Story 0058 — the **live** proof that the board renders what a permanent *currently is*.
 *
 * ## What this has to show that a hermetic test cannot
 *
 * Every claim in this story is a claim about the **server's** data: that `mage.view.CardView` really
 * answers `isCreature()` over the live object, really sends `"0"`/`"0"` for a noncreature permanent's
 * power, really sets summoning sickness on a land, and really carries counters. A fake can be made to
 * say all of that; only a real game proves it. In particular the sharpest case — a permanent whose
 * creature-ness **changes** — cannot be observed at all without a game to change it in.
 *
 * ## The deck, and why this one (recorded in `docs/live-test-decklists.md` §5)
 *
 * - **Mutavault** (`M14` #228) — a land that is *not* a creature, with `{1}: … becomes a 2/2 creature
 *   until end of turn`. One card, one activation, no other card needed: the cheapest live path to a
 *   permanent whose creature-ness changes. It also taps for `{C}`, so a second copy pays for the first.
 * - **Dryad Arbor** (`FUT` #174) — a land that *is* a creature with no effect involved (`Land Creature
 *   — Forest Dryad`, 1/1). The static half of the same pair, and a green source for the Servant.
 * - **Servant of the Scale** (`DTK` #203) — `{G}`, enters as a 0/0 **with a +1/+1 counter**, so one
 *   cheap cast produces a counter to render (and a creature whose printed 0/0 is not its current P/T).
 *
 * All three verified in the bundled catalog before the run; resolution is by **(setCode,
 * collectorNumber)**, never by name.
 *
 * ## What it asserts
 *
 * 1. an ordinary land is **not** a creature: no P/T, and no summoning-sickness mark even though the
 *    server sets the flag — with the raw `power` the server sent shown in the transcript, so "we
 *    suppressed it" is visibly distinct from "the server sent nothing";
 * 2. a land that **is** a creature by printing (Dryad Arbor) shows its P/T;
 * 3. the **same** Mutavault, before and after its own ability resolves, goes from no P/T to `2/2` —
 *    the same object id, two snapshots apart;
 * 4. a permanent's counters arrive and render by name and count.
 *
 * Env-gated on `BRIDGE_URL`, so `:feature:game:check` stays offline (unset ⇒ *skipped*):
 * ```
 * BRIDGE_URL=localhost:8080 ./gradlew :feature:game:testDebugUnitTest \
 *   --tests '*CreatureStatusAndCountersIT*' --rerun-tasks -i
 * ```
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CreatureStatusAndCountersIT {
    /** What the app seat is trying to do next. */
    private enum class Goal {
        /** Answer the mulligan and get to a turn. */
        Opening,

        /** Play every land offered, and cast the Servant once its mana exists. */
        Develop,

        /** Activate a Mutavault's animate ability. */
        Animate,

        /** Nothing more to do; keep passing so the game stays alive. */
        PassOnly,
    }

    @Volatile
    private var goal = Goal.Opening

    @Volatile
    private var opponentState: GameState? = null

    /** The Mutavault we are animating — fixed once chosen, because the assertion is about *one* object. */
    @Volatile
    private var animatingId: String? = null

    private val transcript = CopyOnWriteArrayList<String>()

    @Test
    fun `renders creature status and counters from a real game, including a permanent that becomes a creature`() {
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
                opponent.tables.startMatch(created.tableId).getOrThrow()
                val starting = awaitTable(tableStates, "MatchStarting") { it?.matchStarting != null }!!.matchStarting!!
                say("match starting; gameId=${starting.gameId}")

                val viewModel = GameBoardViewModel(app.games, ManualPassPolicy, FakeCardCatalog())
                jobs +=
                    launch {
                        opponent.games.observeGame(starting.gameId, GameState(starting.gameId)).collect { opponentState = it }
                    }
                opponent.games.joinGame(starting.gameId).getOrThrow()
                jobs += launch { driveOpponent(opponent.games, starting.gameId) }
                viewModel.observe(starting.gameId)
                jobs += launch { driveApp(viewModel) }

                awaitApp(viewModel, "the opening hand") { it.board.hand.count > 0 }
                awaitApp(viewModel, "a turn to start") { it.board.turn.number >= 1 }
                goal = Goal.Develop

                // --- 1. a land that is not a creature ----------------------------------------------------
                val withLand =
                    awaitApp(viewModel, "a Mutavault on our own battlefield", LONG_MS) { state ->
                        ours(state).any { it.card.name == VAULT }
                    }
                val plainVault = ours(withLand).first { it.card.name == VAULT }
                val rawVault = opponentSeesOurs(plainVault.objectId)
                say(
                    "PLAIN LAND   ${plainVault.card.name} id=${plainVault.objectId} " +
                        "board.powerToughness=${plainVault.card.powerToughness} " +
                        "board.showsSummoningSick=${plainVault.showsSummoningSickness} " +
                        "server.power=${rawVault?.power}/${rawVault?.toughness} server.isCreature=${rawVault?.isCreature} " +
                        "server.summoningSick=${opponentSeesOurPermanent(plainVault.objectId)?.hasSummoningSickness} " +
                        "server.cardTypes=${rawVault?.cardTypes}",
                )
                check(plainVault.card.powerToughness == null) {
                    "a Mutavault that is not animated must show no P/T, saw '${plainVault.card.powerToughness}'"
                }
                check(!plainVault.card.isCreature) { "the server must not call an unanimated Mutavault a creature" }
                check(!plainVault.showsSummoningSickness) {
                    "summoning sickness must not be drawn on a noncreature land"
                }
                // The point of the whole story: the server *did* send a power; the board declined to show it.
                check(rawVault?.power != null) {
                    "the server is expected to send a power for a noncreature permanent — if it stopped, this " +
                        "story's premise changed"
                }

                // --- 2. a land that IS a creature, with no effect involved --------------------------------
                val withArbor =
                    awaitApp(viewModel, "a Dryad Arbor on our own battlefield", LONG_MS) { state ->
                        ours(state).any { it.card.name == ARBOR }
                    }
                val arbor = ours(withArbor).first { it.card.name == ARBOR }
                say(
                    "LAND CREATURE ${arbor.card.name} powerToughness=${arbor.card.powerToughness} " +
                        "isCreature=${arbor.card.isCreature} showsSummoningSick=${arbor.showsSummoningSickness} " +
                        "typeLine='${arbor.card.display.typeLine}'",
                )
                check(arbor.card.isCreature) { "Dryad Arbor is a Land Creature — the server says so" }
                check(arbor.card.powerToughness == "1/1") { "expected 1/1, saw '${arbor.card.powerToughness}'" }

                // --- 3. the same permanent, becoming a creature -------------------------------------------
                awaitApp(viewModel, "two Mutavaults, so one can pay for the other", LONG_MS) { state ->
                    ours(state).count { it.card.name == VAULT && !it.isTapped } >= 2
                }
                goal = Goal.Animate
                val animated =
                    awaitApp(viewModel, "a Mutavault to become a creature", VERY_LONG_MS) { state ->
                        ours(state).any { it.card.name == VAULT && it.card.isCreature }
                    }
                val creatureVault = ours(animated).first { it.card.name == VAULT && it.card.isCreature }
                val rawAnimated = opponentSeesOurs(creatureVault.objectId)
                say(
                    "ANIMATED     id=${creatureVault.objectId} powerToughness=${creatureVault.card.powerToughness} " +
                        "isCreature=${creatureVault.card.isCreature} showsSummoningSick=${creatureVault.showsSummoningSickness} " +
                        "server.cardTypes=${rawAnimated?.cardTypes} server.power=${rawAnimated?.power}/${rawAnimated?.toughness} " +
                        "typeLine='${creatureVault.card.display.typeLine}'",
                )
                check(creatureVault.card.powerToughness == "2/2") {
                    "an animated Mutavault must show 2/2, saw '${creatureVault.card.powerToughness}'"
                }
                if (creatureVault.objectId == animatingId) {
                    say("             the object that changed is the one we activated: $animatingId")
                }
                // Back to developing, or nothing else would ever be cast.
                goal = Goal.Develop

                // --- 4. counters --------------------------------------------------------------------------
                val withCounters =
                    awaitApp(viewModel, "a permanent carrying a counter", VERY_LONG_MS) { state ->
                        ours(state).any { it.card.counters.isNotEmpty() }
                    }
                val counted = ours(withCounters).first { it.card.counters.isNotEmpty() }
                say(
                    "COUNTERS     ${counted.card.name} counters=${counted.card.counters.map { it.label }} " +
                        "powerToughness=${counted.card.powerToughness} " +
                        "server=${opponentSeesOurs(counted.objectId)?.counters}",
                )
                check(counted.card.counters.any { it.count > 0 }) { "a counter must carry its count" }

                goal = Goal.PassOnly
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

    // ---- the app seat --------------------------------------------------------------------------------

    private suspend fun driveApp(viewModel: GameBoardViewModel) {
        var lastAnswered: PromptControlsUi? = null
        var lastAnsweredAt = 0L
        while (true) {
            delay(POLL_MS)
            val state = viewModel.uiState.value
            val controls = state.controls ?: continue
            // The same question asked again compares equal (see BoardPlaysAGameIT): re-answer after a beat
            // rather than sitting on an identical prompt forever.
            if (controls == lastAnswered && System.currentTimeMillis() - lastAnsweredAt < REANSWER_MS) continue
            val action = decide(state, controls) ?: continue
            lastAnswered = controls
            lastAnsweredAt = System.currentTimeMillis()
            state.actionError?.let { say("app  !! the server declined the last action: $it") }
            say("app  -> $action   (${controls::class.simpleName}: '${controls.message?.take(TRIM)}')")
            viewModel.act(action)
        }
    }

    private fun decide(
        state: GameBoardUiState,
        controls: PromptControlsUi,
    ): BoardAction? =
        when (controls) {
            is PromptControlsUi.Priority -> {
                val offeredInHand =
                    state.board.hand.cards
                        .filter { it.objectId in controls.pickableObjectIds }
                val land = offeredInHand.firstOrNull { it.card.name == VAULT || it.card.name == ARBOR }
                val servant = offeredInHand.firstOrNull { it.card.name == SERVANT }
                // A Mutavault that is offered, untapped, and not already a creature: the animation
                // candidate. Another untapped one has to exist to pay the {1}.
                val animatable =
                    ours(state)
                        .filter { it.card.name == VAULT && !it.isTapped && !it.card.isCreature }
                        .firstOrNull { it.objectId in controls.pickableObjectIds }
                when {
                    goal == Goal.Animate && animatable != null && ours(state).count { it.card.name == VAULT && !it.isTapped } >= 2 ->
                        BoardAction.PlayObject(animatable.objectId).also { animatingId = animatable.objectId }

                    goal == Goal.Develop && land != null -> BoardAction.PlayObject(land.objectId)
                    goal == Goal.Develop && servant != null -> BoardAction.PlayObject(servant.objectId)
                    else -> controls.buttons.firstOrNull { it.action == BoardAction.PassPriority }?.action
                }
            }

            // Mutavault has two activatable abilities, so the server asks which. Pick the one that is not
            // the mana ability — by the server's own text, since the ability ids mean nothing to us.
            is PromptControlsUi.Choices -> {
                val animate =
                    controls.buttons.firstOrNull { button ->
                        button.action is BoardAction.ChooseAbility && button.label.contains(BECOMES, ignoreCase = true)
                    }
                animate?.action
                    // The mulligan is a Choices too: "Keep" is the right-hand button (`yes = false`).
                    ?: controls.buttons.firstOrNull { it.action == BoardAction.AnswerAsk(yes = false) }?.action
                    ?: controls.buttons.firstOrNull()?.action
            }

            is PromptControlsUi.Mana -> {
                // Pay with a land that can actually be tapped: untapped, ours, and not the permanent whose
                // ability we are in the middle of activating. A first attempt that simply took the first
                // offered id tapped the same source 199 times without the payment ever completing.
                val candidates = ours(state).filter { it.objectId in controls.pickableObjectIds }
                say(
                    "mana candidates: " +
                        candidates.joinToString {
                            "${it.card.name}(tapped=${it.isTapped},creature=${it.card.isCreature},sick=${it.hasSummoningSickness})"
                        } +
                        " | offered ids not on our board: " +
                        controls.pickableObjectIds.count { id -> candidates.none { it.objectId == id } },
                )
                // Mutavault makes {C} and Dryad Arbor makes {G}, and {C} cannot pay {G} — so the source is
                // chosen by what the server says it is owed, read off its own prompt.
                val preferred = if (controls.message?.contains(GREEN) == true) ARBOR else VAULT
                val payer =
                    candidates.firstOrNull { !it.isTapped && it.objectId != animatingId && it.card.name == preferred }
                        ?: candidates.firstOrNull { !it.isTapped && it.objectId != animatingId }
                        ?: candidates.firstOrNull { !it.isTapped }
                payer?.let { BoardAction.PlayManaSource(it.objectId) }
            }

            is PromptControlsUi.Targeting ->
                controls.buttons
                    .map { it.action }
                    .filterIsInstance<BoardAction.ChooseTarget>()
                    .firstOrNull()
                    ?: controls.pickableObjectIds.firstOrNull()?.let(BoardAction::ChooseTarget)
                    ?: BoardAction.CancelPrompt

            is PromptControlsUi.Amount -> controls.amountRequest?.let { BoardAction.ChooseAmount(it.min) }
            is PromptControlsUi.MultiAmount -> BoardAction.DistributeAmounts(controls.amountRows.map { it.min })
            // This run is about what a permanent *is*, not about combat (story 0061 has its own live
            // run), so a declaration is closed with its own *done* rather than answered.
            is PromptControlsUi.Declaration -> BoardAction.FinishTargeting
            is PromptControlsUi.Notice -> null
        }

    // ---- the opponent seat ---------------------------------------------------------------------------

    private suspend fun driveOpponent(
        games: GameClient,
        gameId: String,
    ) {
        var lastAnswered: GamePrompt? = null
        var lastAnsweredAt = 0L
        while (true) {
            delay(POLL_MS)
            val prompt = opponentState?.prompt ?: continue
            if (prompt == lastAnswered && System.currentTimeMillis() - lastAnsweredAt < REANSWER_MS) continue
            lastAnswered = prompt
            lastAnsweredAt = System.currentTimeMillis()
            when (prompt) {
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
        }
    }

    // ---- helpers -------------------------------------------------------------------------------------

    /** The app seat's own battlefield, as the board projects it. */
    private fun ours(state: GameBoardUiState): List<PermanentUi> =
        state.board.viewerSeat
            ?.battlefield
            .orEmpty()

    /**
     * The **raw** `GameCard` for one of our permanents as the server sent it to the *opponent* — the
     * un-projected data, so the transcript can show that the board suppressed a power the server really
     * did send rather than one it never sent.
     */
    private fun opponentSeesOurs(objectId: String) = opponentSeesOurPermanent(objectId)?.card

    private fun opponentSeesOurPermanent(objectId: String) =
        opponentState
            ?.players
            .orEmpty()
            .filterNot { it.isViewer }
            .flatMap { it.battlefield }
            .firstOrNull { it.card.id == objectId }

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
                "turn=${last.board.turn.number} hand=${last.board.hand.count} " +
                "battlefield=${ours(last).map { "${it.card.name}(creature=${it.card.isCreature},pt=${it.card.powerToughness})" }} " +
                "controls=${last.controls?.let { it::class.simpleName }} prompt='${last.controls?.message}'",
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

    /**
     * One live app→bridge session, assembled from production parts only.
     *
     * A deliberate copy of `BoardPlaysAGameIT`'s: 0057's harness is a working live run and is left
     * untouched by this story.
     */
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
        Assume.assumeTrue("$BRIDGE_URL_ENV is not set; skipping the live creature-status run", !raw.isNullOrBlank())
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

    /** Story 0058's deck — see the class docs, and `docs/live-test-decklists.md` §5. */
    private val deck =
        Deck(
            id = DeckId("board-0058-animate"),
            name = "board_0058_animate",
            author = "story-0058",
            main =
                listOf(
                    DeckEntry(cardName = "Mutavault", setCode = "M14", collectorNumber = "228", quantity = 24),
                    DeckEntry(cardName = "Dryad Arbor", setCode = "FUT", collectorNumber = "174", quantity = 18),
                    DeckEntry(cardName = "Servant of the Scale", setCode = "DTK", collectorNumber = "203", quantity = 18),
                ),
        )

    private companion object {
        const val BRIDGE_URL_ENV = "BRIDGE_URL"
        const val TABLE_NAME = "story0058_status"
        const val DUEL = "Two Player Duel"

        /** The only validator that is unconditionally valid (`deckMinSize == 0`). */
        const val FREEFORM = "Constructed - Freeform Unlimited"

        const val VAULT = "Mutavault"
        const val ARBOR = "Dryad Arbor"
        const val SERVANT = "Servant of the Scale"

        /** The word that tells Mutavault's animate ability apart from its mana ability, in the server's own text. */
        const val BECOMES = "becomes"

        /** The symbol in a mana prompt that says only the green source will do. */
        const val GREEN = "{G}"

        const val CONNECT_MS = 60_000L
        const val TABLE_MS = 120_000L
        const val DEFAULT_MS = 90_000L
        const val LONG_MS = 180_000L
        const val VERY_LONG_MS = 300_000L
        const val POLL_MS = 300L
        const val REANSWER_MS = 1_500L
        const val TRIM = 60
    }
}
