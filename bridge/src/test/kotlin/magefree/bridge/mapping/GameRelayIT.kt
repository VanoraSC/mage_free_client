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
import magefree.protocol.GameCardView
import magefree.protocol.GameInformed
import magefree.protocol.GameOver
import magefree.protocol.GamePermanentView
import magefree.protocol.GamePrompt
import magefree.protocol.GamePrompted
import magefree.protocol.GameStarted
import magefree.protocol.GameStateUpdated
import magefree.protocol.GameStateView
import magefree.protocol.GetAmountPrompt
import magefree.protocol.GetMultiAmountPrompt
import magefree.protocol.MatchStarting
import magefree.protocol.PhaseStepCode
import magefree.protocol.PlayManaPrompt
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
import org.junit.jupiter.api.Assertions.assertFalse
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
 * **Two more scenarios live here**, sharing this class's harness (connect, seat, start, pump,
 * teardown) rather than copying it — only the deck and the play loop differ:
 * - story 0086, with [BURN_DECK]: actually *casts* something, the one way to get a targeted spell onto
 *   a real stack and read `CardView.getTargets()` back off the snapshot the server pushes;
 * - story 0087, with [AURA_DECK]: puts an Aura on a creature each player controls, which is the only
 *   way to see `attachedControllerDiffers` be true.
 *
 * **A failed run can leave a table behind on a long-lived server**, and `LobbyRelayIT` — which
 * asserts the reference room has no open tables — is where that shows up, not here. [teardown] is
 * best effort by design (a teardown failure must not mask the assertion that caused it), so after a
 * run that failed mid-game, restart `xmage-server` before reading a `LobbyRelayIT` failure as real.
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

        /**
         * Story 0086's deck: half `Mountain` (M21 #269), half `Lightning Bolt` (M10 #146) — the
         * cheapest deck that can put a **targeted** spell on a real stack. One land casts a Bolt, so
         * the whole scenario fits inside a single turn; the even split makes an opening hand missing
         * one or the other about a 1-in-200 draw, and the play loop simply takes another turn when it
         * happens rather than failing.
         */
        val BURN_DECK =
            DeckList(
                name = "it_bolts",
                author = "game-relay-it",
                cards =
                    listOf(
                        DeckListCard(cardName = "Mountain", setCode = "M21", collectorNumber = "269", amount = 30),
                        DeckListCard(cardName = "Lightning Bolt", setCode = "M10", collectorNumber = "146", amount = 30),
                    ),
                sideboard = emptyList(),
            )

        /** The spell story 0086's live check casts, by the name the server sends back. */
        const val BOLT = "Lightning Bolt"

        /** The land that pays for it. */
        const val MOUNTAIN = "Mountain"

        /** A cap for the 0086 loop: several turns' worth of priority prompts, not a single turn's. */
        const val MAX_ANSWERS_TO_CAST = 400

        /**
         * Story 0087's deck: mono-green, so every cost is payable from one basic. `Rancor` (`EMA`
         * #180, `{G}`) enchants **any** creature — upstream's `TargetCreaturePermanent`, not "a
         * creature you control" — which is what makes the differing-controller case reachable at all.
         * `Grizzly Bears` (`8ED` #256, `{1}{G}`) is the creature to enchant.
         */
        val AURA_DECK =
            DeckList(
                name = "it_rancor",
                author = "game-relay-it",
                cards =
                    listOf(
                        DeckListCard(cardName = "Forest", setCode = "M21", collectorNumber = "272", amount = 24),
                        DeckListCard(cardName = "Grizzly Bears", setCode = "8ED", collectorNumber = "256", amount = 20),
                        DeckListCard(cardName = "Rancor", setCode = "EMA", collectorNumber = "180", amount = 16),
                    ),
                sideboard = emptyList(),
            )

        const val RANCOR = "Rancor"
        const val BEARS = "Grizzly Bears"
        const val FOREST = "Forest"

        /** Story 0087 needs several turns of real play, not one; the 0051 loop's 180s is too tight. */
        const val AURA_PLAY_TIMEOUT_MS = 240_000L
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

    /**
     * **Story 0086's live check.** The hermetic mapper tests craft a `CardView` with a `targets` list
     * already on it, so they prove the mapper reads the field — never that the *server* fills it on the
     * path this bridge actually reads. That is what this asserts, and it is the only way to assert it:
     * cast a real `Lightning Bolt` at the AI seat through a real `SessionImpl`, and read the target id
     * back out of the snapshot the server pushes while the spell sits on the stack.
     *
     * **Deterministic on purpose.** The target is not whatever the AI happened to point at — it is the
     * opponent's own player id, chosen by this test from the server's own `GAME_TARGET` candidates, and
     * asserted by identity. (The AI is holding the same deck and will be bolting *us*; a stack entry
     * carrying the opponent's id can therefore only be the one we cast.) Nothing here depends on an AI
     * decision.
     *
     * **Targeting a player, not a creature, is the point of the flat list.** `CardView.addTargets`
     * resolves every id through `game.getObject(uuid)` and appends them to one list with no per-kind
     * branching, so a player target, a permanent target and a stack-object target are indistinguishable
     * on the wire — which is exactly the contract [GameViewMapper.mapCard] claims. A player is also the
     * one target that needs no board state to exist, which keeps the scenario inside one turn.
     */
    @Test
    fun `a spell on a live stack carries the target the server was told to point it at (story 0086)`() =
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

                val created =
                    withContext(Dispatchers.IO) {
                        TableRelay.createTable(session, roomId, options(name = "it_tgt_$username"))
                    }
                assertTrue(created is TableCreated, "the real server should accept the mapped MatchOptions; got $created")
                tableId = UUID.fromString((created as TableCreated).table.tableId)

                assertJoined(join(session, roomId, tableId, AI_SEAT_NAME, SeatPlayerTypeCode.COMPUTER_MAD, BURN_DECK))
                assertJoined(join(session, roomId, tableId, username, SeatPlayerTypeCode.HUMAN, BURN_DECK))

                val start = withContext(Dispatchers.IO) { TableRelay.startMatch(session, roomId, tableId) }
                assertEquals(TableActionResult(action = TableActionCode.START_MATCH, ok = true), start)

                val matchStarting = events.awaitOfType<MatchStarting>(MATCH_START_TIMEOUT_MS, "a START_GAME push")
                gameId = UUID.fromString(matchStarting.gameId)

                val joined = withContext(Dispatchers.IO) { GameRelay.joinGame(session, gameId) }
                assertTrue(joined.ok, "the real server should accept joinGame for a match we are seated in; got $joined")

                val started = events.awaitOfType<GameStarted>(GAME_INIT_TIMEOUT_MS, "a GAME_INIT push")
                val opponentId =
                    started.state.players
                        .single { it.name == AI_SEAT_NAME }
                        .playerId

                val observed = castABoltAt(opponentId, session, gameId, events)

                val bolt =
                    requireNotNull(observed.targetedStackEntry) {
                        "no snapshot ever carried a stack entry targeting the opponent; cast=${observed.castSent} " +
                            "answered=${observed.answered} callbacks=${callbackSummary()} last=${observed.last}"
                    }
                assertEquals(BOLT, bolt.name, "the stack entry we matched should be the spell we cast")
                assertEquals(
                    listOf(opponentId),
                    bolt.targets,
                    "the server's own CardView.getTargets() should carry exactly the target we chose",
                )
                assertTrue(
                    observed.last!!.players.any { it.playerId == bolt.targets.single() },
                    "the target id must resolve against the same snapshot that carried it",
                )

                println(
                    "GameRelayIT[0086]: stackEntry=${bolt.name} targets=${bolt.targets} " +
                        "answered=${observed.answered} callbacks=${callbackSummary()}",
                )
            } finally {
                pump?.cancel()
                events.close()
                teardown(session, roomId, tableId, gameId)
            }
        }

    /** What the 0086 cast-and-observe loop saw. */
    private class BoltObserved {
        var targetedStackEntry: GameCardView? = null
        var last: GameStateView? = null
        var castSent: Boolean = false
        var answered: Int = 0
    }

    /**
     * Plays a land, casts a `Lightning Bolt` at [opponentId], and returns as soon as a pushed snapshot
     * shows it on the stack pointing there.
     *
     * **Every decision is taken from the server's own `canPlayObjects`** ([GameStateView.playable]), not
     * from this test's idea of the rules: cast the Bolt when the server says it is castable, otherwise
     * play a land when the server says it is playable, otherwise pass. That is what makes a missing
     * Mountain (or a missing Bolt) in the opening hand cost one more turn rather than a failed run — and
     * it means a refused reply cannot turn into an infinite prompt loop, because the loop only ever
     * sends back what the server just offered.
     *
     * The two prompts a cast produces are answered in the order upstream asks them: `GAME_TARGET` first
     * (`AbilityImpl.activate` chooses targets before paying), then `GAME_PLAY_MANA`, answered with the
     * untapped Mountain — a `Mountain` has exactly one mana ability, so tapping it pays the whole cost.
     */
    private suspend fun castABoltAt(
        opponentId: String,
        session: SessionImpl,
        gameId: UUID,
        events: Channel<ServerMessage>,
    ): BoltObserved {
        val observed = BoltObserved()
        val deadline = System.currentTimeMillis() + PLAY_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val remaining = minOf(PUSH_TIMEOUT_MS, deadline - System.currentTimeMillis())
            val event = withTimeoutOrNull(remaining) { events.receive() } ?: break

            stateOf(event)?.let { state ->
                observed.last = state
                state.stack.firstOrNull { opponentId in it.targets }?.let { observed.targetedStackEntry = it }
            }
            if (observed.targetedStackEntry != null) break
            if (event is GameOver) break
            if (event !is GamePrompted) continue

            val state = event.state
            val prompt = event.prompt
            val playable = state.playable.map { it.objectId }.toSet()
            val bolt = state.hand.firstOrNull { it.name == BOLT && it.id in playable }
            val mountainInHand = state.hand.firstOrNull { it.name == MOUNTAIN && it.id in playable }
            val untappedMountain =
                state.players
                    .singleOrNull { it.viewer }
                    ?.battlefield
                    ?.firstOrNull { it.card.name == MOUNTAIN && !it.tapped }
            when {
                prompt is SelectPrompt && bolt != null -> {
                    sendUuid(session, gameId, bolt.id)
                    observed.castSent = true
                }
                prompt is SelectPrompt && mountainInHand != null -> sendUuid(session, gameId, mountainInHand.id)
                prompt is SelectPrompt -> withContext(Dispatchers.IO) { GameRelay.sendPlayerBoolean(session, gameId, false) }
                // Only once we are the one casting: before that, a required GAME_TARGET is the server
                // asking who chooses to go first, which `answer` already handles correctly.
                prompt is TargetPrompt && observed.castSent && opponentId in prompt.targetIds ->
                    sendUuid(session, gameId, opponentId)
                prompt is PlayManaPrompt && untappedMountain != null -> sendUuid(session, gameId, untappedMountain.card.id)
                else -> answer(session, gameId, prompt)
            }
            observed.answered++
            if (observed.answered > MAX_ANSWERS_TO_CAST) {
                throw AssertionError(
                    "answered $MAX_ANSWERS_TO_CAST prompts without ever getting a targeted spell onto the stack. " +
                        "cast=${observed.castSent} last prompt: ${event.prompt}. Callbacks: ${callbackSummary()}",
                )
            }
        }
        return observed
    }

    /**
     * **Story 0087's live check.** A crafted `PermanentView` can be given any combination of
     * attachment fields, so a fixture proves only that the mapper reads them. This proves the *server*
     * fills them, in a real game, on the path the bridge reads — and it covers the case §7.4 calls the
     * easily-missed one: **your Aura on their creature**, which no single-player fixture can produce.
     *
     * **Both directions are asserted against each other.** For every attachment found, the host named
     * by `attachedTo` must list it back in `attachments`. That invariant is the cheapest possible check
     * that the two fields were read off the same snapshot rather than drifting apart, and in the
     * differing-controller case it holds *across* two players' battlefields — the Aura sits on ours,
     * the creature on theirs.
     */
    @Test
    fun `a live aura is carried in both directions, on our creature and on theirs (story 0087)`() =
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

                val created =
                    withContext(Dispatchers.IO) {
                        TableRelay.createTable(session, roomId, options(name = "it_aura_$username"))
                    }
                assertTrue(created is TableCreated, "the real server should accept the mapped MatchOptions; got $created")
                tableId = UUID.fromString((created as TableCreated).table.tableId)

                assertJoined(join(session, roomId, tableId, AI_SEAT_NAME, SeatPlayerTypeCode.COMPUTER_MAD, AURA_DECK))
                assertJoined(join(session, roomId, tableId, username, SeatPlayerTypeCode.HUMAN, AURA_DECK))

                val start = withContext(Dispatchers.IO) { TableRelay.startMatch(session, roomId, tableId) }
                assertEquals(TableActionResult(action = TableActionCode.START_MATCH, ok = true), start)

                val matchStarting = events.awaitOfType<MatchStarting>(MATCH_START_TIMEOUT_MS, "a START_GAME push")
                gameId = UUID.fromString(matchStarting.gameId)

                val joined = withContext(Dispatchers.IO) { GameRelay.joinGame(session, gameId) }
                assertTrue(joined.ok, "the real server should accept joinGame for a match we are seated in; got $joined")

                events.awaitOfType<GameStarted>(GAME_INIT_TIMEOUT_MS, "a GAME_INIT push")

                val observed = playUntilBothAuraCasesSeen(session, gameId, events)

                val ours =
                    requireNotNull(observed.ownCreature) {
                        "never saw a Rancor on our own creature; answered=${observed.answered} " +
                            "callbacks=${callbackSummary()} last=${observed.last}"
                    }
                assertTrue(ours.aura.attachedToPermanent, "the host is a creature, so upstream must say the host is a permanent")
                assertFalse(ours.aura.attachedControllerDiffers, "we control both the Aura and the creature")
                assertEquals(ours.host.card.id, ours.aura.attachedTo)
                assertTrue(
                    ours.aura.card.id in ours.host.attachments,
                    "the host must list the Aura back: attachedTo=${ours.aura.attachedTo} attachments=${ours.host.attachments}",
                )
                assertTrue(ours.hostControlledByViewer, "our own creature should sit on our own battlefield")

                val theirs =
                    requireNotNull(observed.opponentCreature) {
                        "never saw a Rancor on the opponent's creature — the case this story exists for; " +
                            "answered=${observed.answered} callbacks=${callbackSummary()} last=${observed.last}"
                    }
                assertTrue(theirs.aura.attachedToPermanent)
                assertTrue(theirs.aura.attachedControllerDiffers, "our Aura on their creature is exactly what this flag is for")
                assertEquals(theirs.host.card.id, theirs.aura.attachedTo)
                assertTrue(
                    theirs.aura.card.id in theirs.host.attachments,
                    "the round trip must hold across two battlefields too: attachments=${theirs.host.attachments}",
                )
                assertFalse(theirs.hostControlledByViewer, "the host is the opponent's creature, on the opponent's battlefield")

                println(
                    "GameRelayIT[0087]: ourAura=${ours.aura.card.id}->${ours.aura.attachedTo} differs=false; " +
                        "theirAura=${theirs.aura.card.id}->${theirs.aura.attachedTo} differs=true; " +
                        "answered=${observed.answered} callbacks=${callbackSummary()}",
                )
            } finally {
                pump?.cancel()
                events.close()
                teardown(session, roomId, tableId, gameId)
            }
        }

    /** One observed attachment: the Aura, the permanent it named, and whose battlefield that host is on. */
    private class Attachment(
        val aura: GamePermanentView,
        val host: GamePermanentView,
        val hostControlledByViewer: Boolean,
    )

    /** What the 0087 loop saw. */
    private class AuraObserved {
        var ownCreature: Attachment? = null
        var opponentCreature: Attachment? = null
        var last: GameStateView? = null
        var answered: Int = 0
    }

    /**
     * Plays green until a `Rancor` sits on **our** creature and another sits on **theirs**.
     *
     * Like the 0086 loop, every decision comes from the server's own `canPlayObjects` — cast what it
     * says is castable, play the land it says is playable, otherwise pass — so a slow draw costs a turn
     * rather than the run, and a refused reply cannot become an unbounded prompt loop. The one addition
     * is that action is confined to **our own precombat main phase**: outside it a `GAME_SELECT` is a
     * combat declaration, and answering one with an object id would declare an attacker or a blocker
     * rather than cast anything.
     *
     * The opponent's creature is the one thing here that is not ours to arrange. It is also not a coin
     * flip: the AI holds the same 20-creature deck and plays a Bear on essentially every turn it can,
     * and the loop simply keeps passing until one is there.
     */
    private suspend fun playUntilBothAuraCasesSeen(
        session: SessionImpl,
        gameId: UUID,
        events: Channel<ServerMessage>,
    ): AuraObserved {
        val observed = AuraObserved()
        val deadline = System.currentTimeMillis() + AURA_PLAY_TIMEOUT_MS
        var pendingTarget: String? = null
        val tappedForThisPayment = mutableSetOf<String>()

        while (System.currentTimeMillis() < deadline) {
            val remaining = minOf(PUSH_TIMEOUT_MS, deadline - System.currentTimeMillis())
            val event = withTimeoutOrNull(remaining) { events.receive() } ?: break

            stateOf(event)?.let { state ->
                observed.last = state
                attachmentOf(state, differingController = false)?.let { observed.ownCreature = it }
                attachmentOf(state, differingController = true)?.let { observed.opponentCreature = it }
            }
            if (observed.ownCreature != null && observed.opponentCreature != null) break
            if (event is GameOver) break
            if (event !is GamePrompted) continue

            val state = event.state
            val prompt = event.prompt
            val playable = state.playable.map { it.objectId }.toSet()
            val ourBoard =
                state.players
                    .singleOrNull { it.viewer }
                    ?.battlefield
                    .orEmpty()
            val theirBoard = state.players.filterNot { it.viewer }.flatMap { it.battlefield }
            val ourMainPhase =
                state.viewerHasPriority &&
                    state.viewerPlayerId != null &&
                    state.viewerPlayerId == state.activePlayerId &&
                    state.phase == TurnPhaseCode.PRECOMBAT_MAIN &&
                    state.step == PhaseStepCode.PRECOMBAT_MAIN

            when {
                prompt is SelectPrompt && ourMainPhase -> {
                    tappedForThisPayment.clear()
                    val forest = state.hand.firstOrNull { it.name == FOREST && it.id in playable }
                    val bears = state.hand.firstOrNull { it.name == BEARS && it.id in playable }
                    val rancor = state.hand.firstOrNull { it.name == RANCOR && it.id in playable }
                    val ourBareCreature = ourBoard.firstOrNull { it.card.creature && it.attachments.isEmpty() }
                    val theirCreature = theirBoard.firstOrNull { it.card.creature }
                    when {
                        forest != null -> sendUuid(session, gameId, forest.id)
                        ourBoard.none { it.card.creature } && bears != null -> sendUuid(session, gameId, bears.id)
                        rancor != null && observed.ownCreature == null && ourBareCreature != null -> {
                            pendingTarget = ourBareCreature.card.id
                            sendUuid(session, gameId, rancor.id)
                        }
                        rancor != null && observed.opponentCreature == null && theirCreature != null -> {
                            pendingTarget = theirCreature.card.id
                            sendUuid(session, gameId, rancor.id)
                        }
                        else -> withContext(Dispatchers.IO) { GameRelay.sendPlayerBoolean(session, gameId, false) }
                    }
                }
                prompt is TargetPrompt && pendingTarget != null && pendingTarget in prompt.targetIds -> {
                    sendUuid(session, gameId, pendingTarget)
                    pendingTarget = null
                }
                prompt is PlayManaPrompt -> {
                    // Track what we have already tapped for this payment rather than trusting the
                    // snapshot to have caught up: sending the same land twice is a refused reply, and
                    // a refused reply is re-asked immediately.
                    val land =
                        ourBoard.firstOrNull { it.card.name == FOREST && !it.tapped && it.card.id !in tappedForThisPayment }
                    if (land != null) {
                        tappedForThisPayment += land.card.id
                        sendUuid(session, gameId, land.card.id)
                    } else {
                        withContext(Dispatchers.IO) { GameRelay.sendPlayerBoolean(session, gameId, false) }
                    }
                }
                else -> answer(session, gameId, prompt)
            }
            observed.answered++
            if (observed.answered > MAX_ANSWERS_TO_CAST) {
                throw AssertionError(
                    "answered $MAX_ANSWERS_TO_CAST prompts without seeing both aura cases. " +
                        "own=${observed.ownCreature != null} opponent=${observed.opponentCreature != null} " +
                        "last prompt: ${event.prompt}. Callbacks: ${callbackSummary()}",
                )
            }
        }
        return observed
    }

    /**
     * The first attachment **we control** in [state] whose `attachedControllerDiffers` matches
     * [differingController], paired with the permanent its `attachedTo` names — or `null` when there is
     * none, or when the host is not in the snapshot (which would itself be a bug worth failing on
     * later, with the whole state in the message, rather than here with none of it).
     *
     * **`controlledByViewer` is load-bearing, and the first run proved it.** Both seats hold
     * [AURA_DECK], so the AI casts Rancor too — the first version of this matched *its* Aura on *its*
     * own creature within eight seconds and then failed asserting the host was on our battlefield.
     * Filtering to Auras we control is what makes the test about the casts this test made, rather than
     * about whatever the AI happened to do.
     */
    private fun attachmentOf(
        state: GameStateView,
        differingController: Boolean,
    ): Attachment? {
        val everything = state.players.flatMap { player -> player.battlefield.map { player to it } }
        val (_, aura) =
            everything.firstOrNull { (_, permanent) ->
                permanent.controlledByViewer &&
                    permanent.attachedToPermanent &&
                    permanent.attachedControllerDiffers == differingController
            } ?: return null
        val (hostOwner, host) = everything.firstOrNull { (_, permanent) -> permanent.card.id == aura.attachedTo } ?: return null
        return Attachment(aura = aura, host = host, hostControlledByViewer = hostOwner.viewer)
    }

    /** Sends [id] as the reply to the outstanding prompt, on [Dispatchers.IO] like every other reply. */
    private suspend fun sendUuid(
        session: SessionImpl,
        gameId: UUID,
        id: String,
    ) = withContext(Dispatchers.IO) { GameRelay.sendPlayerUuid(session, gameId, UUID.fromString(id)) }

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
        deck: DeckList = DECK,
    ): TableActionResult =
        withContext(Dispatchers.IO) {
            TableRelay.joinTable(
                session,
                roomId,
                tableId,
                seatName = seatName,
                deck = deck,
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
