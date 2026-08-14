package magefree.feature.game.live

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * **The second combat probe: does the server ask for the *pairing*?**
 *
 * `CombatProbeIT` established the declaration shapes (requirements §7.2/§7.3): `Select attackers` with
 * `possibleAttackers`, `Select blockers` with `possibleBlockers`. §7.5 then recorded a design decision —
 * *tap the creature, and ask for the pairing only when it is genuinely ambiguous* — **with its
 * dependency stated**: that is only free if upstream behaves the same way. Two questions were left
 * explicitly unmeasured, and this run answers them:
 *
 * 1. **Attacking:** after picking an attacker, does the server ask **which defender** it attacks, when
 *    more than one legal defender exists? (`CombatProbeIT` could not tell: its board had only the
 *    opposing player, so there was nothing to disambiguate.)
 * 2. **Blocking:** after picking a blocker, does the server ask **which attacker** it blocks?
 *    (`CombatProbeIT` saw the blocking prompt arrive but its budget expired before answering it.)
 *
 * If upstream asks only when ambiguous, §7.5 costs nothing. If it **always** asks, then answering a
 * one-option question on the player's behalf is a policy decision that belongs behind the auto-pass
 * seam (§14.1) — so the answer changes where the code goes, which is why it is measured before the
 * combat story is written rather than after.
 *
 * ## Shape of the run
 *
 * **Both seats are driven over a raw [GameClient]**, unlike `CombatProbeIT`. This is a protocol
 * question, not a UI one, and the board has no combat handling to drive: a ViewModel seat cannot pick
 * an attacker at all (§7.2 — `pickable=0`). Each seat picks **one** creature at a time and then simply
 * observes, so a follow-up prompt has room to arrive and be seen.
 *
 * Every prompt is printed **the moment its message changes**, per seat, rather than deduped by kind —
 * a follow-up is precisely a *new message in the same step*, which a coarser key would hide.
 *
 * ## The deck
 * 24× `Mountain` (`10E` #376), 24× `Dragon Fodder` (`ALA` #97), 12× `Tibalt, the Fiend-Blooded`
 * (`AVR` #161). Dragon Fodder gives two 1/1 bodies for `{1}{R}` (attackers *and* blockers); Tibalt is a
 * `{R}{R}` planeswalker, and a planeswalker on the battlefield is what creates a **second legal
 * defender** — the whole point of question 1. Resolution is by **(setCode, collectorNumber)**.
 *
 * Env-gated on `BRIDGE_URL`. Run with:
 * ```
 * BRIDGE_URL=localhost:8080 ./gradlew :feature:game:testDebugUnitTest --tests '*CombatPairingProbeIT*' --rerun-tasks
 * ```
 */
class CombatPairingProbeIT {
    private val states = ConcurrentHashMap<String, GameState>()
    private val lastMessage = ConcurrentHashMap<String, String>()

    private var sawDefenderQuestion = false
    private var sawAttackerPairingQuestion = false
    private var pickedAnAttacker = false
    private var pickedABlocker = false

    @Test
    fun `probe - does the server ask which defender, and which attacker a blocker blocks`() {
        val target = requireBridge()
        runBlocking {
            val a = LiveSession(this, target)
            val b = LiveSession(this, target)
            val aName = username("atk")
            val bName = username("blk")
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
                say("table created")

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
                    jobs +=
                        launch {
                            session.games
                                .observeGame(starting.gameId, GameState(starting.gameId))
                                .collect { states[who] = it; report(who, it) }
                        }
                    session.games.joinGame(starting.gameId).getOrThrow()
                    jobs += launch { drive(who, session.games, starting.gameId) }
                }

                val deadline = System.currentTimeMillis() + BUDGET_MS
                while (System.currentTimeMillis() < deadline &&
                    !(sawDefenderQuestion && sawAttackerPairingQuestion)
                ) {
                    delay(POLL_MS)
                }

                say("=".repeat(72))
                say("ANSWER 1 — after picking an attacker, was a 'which defender' question asked? $sawDefenderQuestion")
                say("           (an attacker was picked at all: $pickedAnAttacker)")
                say("ANSWER 2 — after picking a blocker, was a 'which attacker' question asked? $sawAttackerPairingQuestion")
                say("           (a blocker was picked at all: $pickedABlocker)")
                states["A"]?.let { s ->
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
            }
        }
    }

    /**
     * Prints a seat's prompt **whenever its message changes**. A pairing follow-up is a new message
     * inside the same step, so anything keyed more coarsely than the message would hide exactly the
     * thing this run exists to see.
     */
    private fun report(
        who: String,
        state: GameState,
    ) {
        val prompt = state.prompt ?: return
        val message = prompt.message
        if (lastMessage.put(who, message) == message) return

        val kind = prompt::class.simpleName
        say("[$who] step=${state.step} turn=${state.turn} $kind msg='$message'")
        when (prompt) {
            is GamePrompt.Select -> {
                val o = prompt.options
                if (o.ids.isNotEmpty()) say("      ids=${o.ids.mapValues { it.value.size }} text=${o.text.keys}")
            }
            is GamePrompt.Target -> {
                say(
                    "      required=${prompt.isRequired} targets=${prompt.targetIds.size} " +
                        "cards=${prompt.cards.map { it.name }} ids=${prompt.options.ids.mapValues { it.value.size }}",
                )
                say("      -> ${describe(state, prompt.targetIds)}")
                // A Target arriving *during a declaration step*, right after a pick, is the pairing
                // question — the shape §7.5's decision hangs on.
                if (state.step == PhaseStep.DeclareAttackers && pickedAnAttacker) {
                    sawDefenderQuestion = true
                    say("      *** THIS IS THE 'WHICH DEFENDER' QUESTION ***")
                }
                if (state.step == PhaseStep.DeclareBlockers && pickedABlocker) {
                    sawAttackerPairingQuestion = true
                    say("      *** THIS IS THE 'WHICH ATTACKER' QUESTION ***")
                }
            }
            else -> Unit
        }
    }

    /** Names ids against everything the snapshot draws, so the transcript reads. */
    private fun describe(
        state: GameState,
        ids: List<String>,
    ): List<String> =
        ids.map { id ->
            state.players.flatMap { it.battlefield }.firstOrNull { it.card.id == id }?.card?.name
                ?: state.players.firstOrNull { it.playerId == id }?.name?.let { "PLAYER:$it" }
                ?: id.take(8)
        }

    private suspend fun drive(
        who: String,
        games: GameClient,
        gameId: String,
    ) {
        var lastKey: String? = null
        var lastAt = 0L
        while (true) {
            delay(POLL_MS)
            val state = states[who] ?: continue
            val prompt = state.prompt ?: continue
            val key = "${prompt::class.simpleName}|${prompt.message}"
            if (key == lastKey && System.currentTimeMillis() - lastAt < REANSWER_MS) continue
            lastKey = key
            lastAt = System.currentTimeMillis()

            when (prompt) {
                // The mana-pool "Pass anyway?" must be answered affirmatively or the game never
                // advances (§7.6). It is the only Ask carrying `autoAnswerMessage`.
                is GamePrompt.Ask ->
                    games.answerAsk(gameId, answer = prompt.options.text.containsKey(AUTO_ANSWER))

                is GamePrompt.Select -> {
                    val attackers = prompt.options.possibleAttackers
                    val blockers = prompt.options.possibleBlockers
                    // Lands before spells, or the seat strands itself mid-payment (§7.6).
                    val landIds = state.hand.filter { it.manaCost == null }.map { it.id }.toSet()
                    val land = state.playable.firstOrNull { it.objectId in landIds }
                    when {
                        // **One pick, then stop and watch.** Picking everything at once would answer
                        // any follow-up by accident and hide it.
                        attackers.isNotEmpty() && !pickedAnAttacker -> {
                            pickedAnAttacker = true
                            say("[$who] -> picking ONE attacker of ${attackers.size}, then observing")
                            games.chooseTarget(gameId, attackers.first())
                        }
                        attackers.isNotEmpty() -> games.cancelPrompt(gameId)

                        blockers.isNotEmpty() && !pickedABlocker -> {
                            pickedABlocker = true
                            say("[$who] -> picking ONE blocker of ${blockers.size}, then observing")
                            games.chooseTarget(gameId, blockers.first())
                        }
                        blockers.isNotEmpty() -> games.cancelPrompt(gameId)

                        land != null -> games.playObject(gameId, land.objectId)
                        state.playable.isNotEmpty() -> games.playObject(gameId, state.playable.first().objectId)
                        else -> games.passPriority(gameId)
                    }
                }

                is GamePrompt.Target -> {
                    val pick = prompt.targetIds.firstOrNull()
                    if (pick != null) games.chooseTarget(gameId, pick) else games.cancelPrompt(gameId)
                }

                // Mana sources live in `playable`, never in `possibleTargets` (§7.6).
                is GamePrompt.PlayMana -> {
                    val source = state.playable.firstOrNull()?.objectId
                    if (source != null) {
                        games.playManaSource(gameId, source)
                    } else {
                        games.cancelPrompt(gameId)
                        games.passPriority(gameId)
                    }
                }

                else -> runCatching { games.cancelPrompt(gameId) }
            }
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

    private fun say(line: String) = println("[pairing-probe] $line")

    private fun username(prefix: String) =
        prefix + "_" + UUID.randomUUID().toString().filter { it != '-' }.take(6)

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
        Assume.assumeTrue("$BRIDGE_URL_ENV is not set; skipping the pairing probe", !raw.isNullOrBlank())
        val authority = raw!!.trim().trimEnd('/').removePrefix("wss://").removePrefix("ws://")
        val separator = authority.lastIndexOf(':')
        return ServerTarget(
            host = authority.substring(0, separator),
            port = authority.substring(separator + 1).toInt(),
            secure = raw.startsWith("wss://"),
        )
    }

    private val deck =
        Deck(
            id = DeckId("combat-pairing"),
            name = "combat_pairing",
            author = "combat-pairing-probe",
            main =
                listOf(
                    DeckEntry(cardName = "Mountain", setCode = "10E", collectorNumber = "376", quantity = 24),
                    DeckEntry(cardName = "Dragon Fodder", setCode = "ALA", collectorNumber = "97", quantity = 24),
                    DeckEntry(cardName = "Tibalt, the Fiend-Blooded", setCode = "AVR", collectorNumber = "161", quantity = 12),
                ),
        )

    private companion object {
        const val AUTO_ANSWER = "autoAnswerMessage"
        const val BRIDGE_URL_ENV = "BRIDGE_URL"
        const val TABLE_NAME = "combat_pairing"
        const val DUEL = "Two Player Duel"
        const val FREEFORM = "Constructed - Freeform Unlimited"

        const val CONNECT_MS = 60_000L
        const val TABLE_MS = 120_000L
        const val BUDGET_MS = 780_000L
        const val POLL_MS = 200L
        const val REANSWER_MS = 1_200L
    }
}
