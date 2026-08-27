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
import magefree.protocol.AskPrompt
import magefree.protocol.ChooseAbilityPrompt
import magefree.protocol.ChooseChoicePrompt
import magefree.protocol.CommandObjectKind
import magefree.protocol.CreateTableOptions
import magefree.protocol.DeckList
import magefree.protocol.DeckListCard
import magefree.protocol.GameCardView
import magefree.protocol.GameInformed
import magefree.protocol.GameOver
import magefree.protocol.GamePermanentView
import magefree.protocol.GamePlayerView
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
import magefree.protocol.ProtocolJson
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
 * **The real proof**: a live game crossing the bridge, driven against the
 * reference server through a **real** [SessionImpl].
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
 * **Three more scenarios live here**, sharing this class's harness (connect, seat, start, pump,
 * teardown) rather than copying it — only the deck and the play loop differ:
 * - [BURN_DECK] casts a targeted spell, the only way to get real targets onto a real stack and read
 *   `CardView.getTargets()` back off a pushed snapshot;
 * - [AURA_DECK] puts an Aura on a land each player controls, which is the only way to see
 *   `attachedControllerDiffers` be true;
 * - [PLAYER_STATE_DECK] produces poison counters, the crown and the initiative.
 *
 * **Each of the three arranges everything it asserts.** Where a scenario needs the opponent to have a
 * permanent, that permanent is a land, because a deck of sixty basics plays one every turn whatever
 * else it does. Waiting on the AI to cast a creature makes the assertion depend on an AI decision,
 * which is how a live test becomes a flaky one.
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
         * the deck: half `Mountain` (M21 #269), half `Lightning Bolt` (M10 #146) — the
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

        /** The spell the live check casts, by the name the server sends back. */
        const val BOLT = "Lightning Bolt"

        /** The land that pays for it. */
        const val MOUNTAIN = "Mountain"

        /** A cap for the loop: several turns' worth of priority prompts, not a single turn's. */
        const val MAX_ANSWERS_TO_CAST = 400

        /**
         * The deck for the attachment scenario. `Sea's Claim` (`9ED` #97) costs `{U}` and takes
         * upstream's `TargetLandPermanent` — **any** land, not "a land you control" — which is what
         * makes an Aura on a permanent the opponent controls reachable.
         *
         * A **land** is the host rather than a creature because the opponent always has one: it plays
         * a land every turn from sixty basics, whatever else it decides to do. Enchanting a creature
         * would have meant waiting for the AI to cast one, which is its choice, not this test's.
         */
        val AURA_DECK =
            DeckList(
                name = "it_seasclaim",
                author = "game-relay-it",
                cards =
                    listOf(
                        DeckListCard(cardName = "Island", setCode = "M21", collectorNumber = "263", amount = 40),
                        DeckListCard(cardName = "Sea's Claim", setCode = "9ED", collectorNumber = "97", amount = 20),
                    ),
                sideboard = emptyList(),
            )

        const val SEAS_CLAIM = "Sea's Claim"
        const val ISLAND = "Island"
        const val FOREST = "Forest"
        const val PLAINS = "Plains"

        /** The attachment scenario needs several turns of real play; the loop's 180s is tight. */
        const val AURA_PLAY_TIMEOUT_MS = 240_000L

        /**
         * The deck for the per-player-state scenario. Mono-white, and every objective is reached by
         * *playing* a card rather than by activating one:
         * - `Mox Poison` (`MB2` #608) costs `{0}`; tapping it for mana gives its controller two poison
         *   counters, which is the cheapest poison in the game and needs no combat.
         * - `Palace Sentinels` (`CN2` #19) costs `{3}{W}` and makes you the monarch **when it enters**.
         * - `Dungeoneer's Pack` (`CLB` #312) costs `{3}`; `{2}`, `{T}`, sacrifice takes the initiative.
         *
         * A monarch source that triggers on entry is what makes this reliable. Activating one instead
         * costs a second payment out of an already-tapped board, and an activation the loop starts and
         * cannot fund is the shape of every hang this class has had.
         */
        val PLAYER_STATE_DECK =
            DeckList(
                name = "it_vitals",
                author = "game-relay-it",
                cards =
                    listOf(
                        DeckListCard(cardName = "Plains", setCode = "M21", collectorNumber = "260", amount = 26),
                        DeckListCard(cardName = "Mox Poison", setCode = "MB2", collectorNumber = "608", amount = 10),
                        DeckListCard(cardName = "Palace Sentinels", setCode = "CN2", collectorNumber = "19", amount = 12),
                        DeckListCard(cardName = "Dungeoneer's Pack", setCode = "CLB", collectorNumber = "312", amount = 12),
                    ),
                sideboard = emptyList(),
            )

        /**
         * A commander game needs no play at all: a commander sits in the command zone from the first
         * snapshot. `Freeform Unlimited Commander` is the variant that makes it cheap — its deck
         * validator's `validate()` returns `true` unconditionally and its minimum deck size is zero, so
         * the sideboard's single legendary creature becomes the commander without a hundred-card
         * singleton list to satisfy.
         */
        const val COMMANDER_GAME_TYPE = "Freeform Unlimited Commander"
        const val COMMANDER_DECK_TYPE = "Variant Magic - Freeform Unlimited Commander"
        const val COMMANDER_NAME = "Atraxa, Praetors' Voice"

        val COMMANDER_DECK =
            DeckList(
                name = "it_commander",
                author = "game-relay-it",
                cards = listOf(DeckListCard(cardName = "Forest", setCode = "M21", collectorNumber = "272", amount = 60)),
                sideboard = listOf(DeckListCard(cardName = COMMANDER_NAME, setCode = "C16", collectorNumber = "28", amount = 1)),
            )

        /**
         * The deck for the zone-contents scenario. `Tome Scour` (`M10` #76) costs `{U}` and mills the
         * target player five cards — one card that fills **both** graveyards at once, on turn one: the
         * opponent's with the five it milled, ours with the Tome Scour itself once it resolves.
         *
         * It targets a *player*, so nothing has to be on the battlefield first, and the opponent's
         * graveyard cannot depend on what the AI chooses to do.
         */
        val MILL_DECK =
            DeckList(
                name = "it_mill",
                author = "game-relay-it",
                cards =
                    listOf(
                        DeckListCard(cardName = "Island", setCode = "M21", collectorNumber = "263", amount = 30),
                        DeckListCard(cardName = "Tome Scour", setCode = "M10", collectorNumber = "76", amount = 30),
                    ),
                sideboard = emptyList(),
            )

        const val TOME_SCOUR = "Tome Scour"

        const val MOX = "Mox Poison"
        const val SENTINELS = "Palace Sentinels"
        const val PACK = "Dungeoneer's Pack"
        const val POISON = "poison"
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
     * **The live check.** The hermetic mapper tests craft a `CardView` with a `targets` list
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
    fun `a spell on a live stack carries the target the server was told to point it at`() =
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
                    "GameRelayIT[targets]: stackEntry=${bolt.name} targets=${bolt.targets} " +
                        "answered=${observed.answered} callbacks=${callbackSummary()}",
                )
            } finally {
                pump?.cancel()
                events.close()
                teardown(session, roomId, tableId, gameId)
            }
        }

    /** What the cast-and-observe loop saw. */
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
     * Attachments in a real game, in both directions, on a permanent we control and on one the
     * opponent controls. A crafted `PermanentView` can be given any combination of attachment fields,
     * so a fixture proves only that the mapper reads them; this proves the server fills them on the
     * path the bridge reads, and it reaches the case no single-player fixture can produce — **your
     * Aura on their permanent**.
     *
     * **Both directions are asserted against each other.** For every attachment found, the host named
     * by `attachedTo` must list it back in `attachments`. That invariant is the cheapest check that the
     * two fields were read off the same snapshot rather than drifting apart, and in the
     * differing-controller case it holds *across* two players' battlefields — the Aura sits on ours,
     * the host on theirs.
     *
     * The opponent seat holds [DECK], sixty basic lands: it plays a land every turn and does nothing
     * else, so the permanent this test enchants is always there and no assertion depends on an AI
     * decision.
     */
    @Test
    fun `a live aura is carried in both directions, on our permanent and on theirs`() =
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

                assertJoined(join(session, roomId, tableId, AI_SEAT_NAME, SeatPlayerTypeCode.COMPUTER_MAD, DECK))
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
                    requireNotNull(observed.ownPermanent) {
                        "never saw a Sea's Claim on a land we control; answered=${observed.answered} " +
                            "callbacks=${callbackSummary()} last=${observed.last}"
                    }
                assertTrue(ours.aura.attachedToPermanent, "the host is a land, so upstream must say the host is a permanent")
                assertFalse(ours.aura.attachedControllerDiffers, "we control both the Aura and the land")
                assertEquals(ours.host.card.id, ours.aura.attachedTo)
                assertTrue(
                    ours.aura.card.id in ours.host.attachments,
                    "the host must list the Aura back: attachedTo=${ours.aura.attachedTo} attachments=${ours.host.attachments}",
                )
                assertTrue(ours.hostControlledByViewer, "our own land sits on our own battlefield")

                val theirs =
                    requireNotNull(observed.opponentPermanent) {
                        "never saw a Sea's Claim on a land the opponent controls; " +
                            "answered=${observed.answered} callbacks=${callbackSummary()} last=${observed.last}"
                    }
                assertTrue(theirs.aura.attachedToPermanent)
                assertTrue(theirs.aura.attachedControllerDiffers, "our Aura on their permanent is exactly what this flag is for")
                assertEquals(theirs.host.card.id, theirs.aura.attachedTo)
                assertTrue(
                    theirs.aura.card.id in theirs.host.attachments,
                    "the round trip must hold across two battlefields too: attachments=${theirs.host.attachments}",
                )
                assertFalse(theirs.hostControlledByViewer, "the host is the opponent's land, on the opponent's battlefield")

                println(
                    "GameRelayIT[aura]: ourAura=${ours.aura.card.id}->${ours.aura.attachedTo} differs=false; " +
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

    /** What the loop saw. */
    private class AuraObserved {
        var ownPermanent: Attachment? = null
        var opponentPermanent: Attachment? = null
        var last: GameStateView? = null
        var answered: Int = 0
    }

    /**
     * Plays until a `Sea's Claim` sits on a land **we** control and another sits on a land **they**
     * control.
     *
     * Every decision comes from the server's own `canPlayObjects` — cast what it says is castable, play
     * the land it says is playable, otherwise pass — so a slow draw costs a turn rather than the run,
     * and a refused reply cannot become an unbounded prompt loop. Action is confined to our own
     * precombat main phase: outside it a `GAME_SELECT` is a combat declaration, and answering one with
     * an object id would declare an attacker or a blocker rather than cast anything.
     *
     * Nothing here waits on the opponent to decide anything. It holds sixty basic lands, so it has a
     * land on the battlefield from its first turn onwards and never does anything else.
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
                attachmentOf(state, differingController = false)?.let { observed.ownPermanent = it }
                attachmentOf(state, differingController = true)?.let { observed.opponentPermanent = it }
            }
            if (observed.ownPermanent != null && observed.opponentPermanent != null) break
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
                    val island = state.hand.firstOrNull { it.name == ISLAND && it.id in playable }
                    val aura = state.hand.firstOrNull { it.name == SEAS_CLAIM && it.id in playable }
                    val ourBareLand = ourBoard.firstOrNull { it.card.name == ISLAND && it.attachments.isEmpty() }
                    val theirLand = theirBoard.firstOrNull { it.attachments.isEmpty() }
                    when {
                        island != null -> sendUuid(session, gameId, island.id)
                        aura != null && observed.ownPermanent == null && ourBareLand != null -> {
                            pendingTarget = ourBareLand.card.id
                            sendUuid(session, gameId, aura.id)
                        }
                        aura != null && observed.opponentPermanent == null && theirLand != null -> {
                            pendingTarget = theirLand.card.id
                            sendUuid(session, gameId, aura.id)
                        }
                        else -> pass(session, gameId)
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
                        ourBoard.firstOrNull { it.card.name == ISLAND && !it.tapped && it.card.id !in tappedForThisPayment }
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
                        "own=${observed.ownPermanent != null} opponent=${observed.opponentPermanent != null} " +
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

    /**
     * Per-player state that decides games without ever being on the battlefield: a **poison counter**,
     * the **crown**, and the **initiative**, each produced by a real game against the reference server
     * and read back off the snapshot the bridge maps.
     *
     * A crafted `PlayerView` can be given any of these, so the hermetic tests prove only that the
     * mapper reads them. This proves the server fills them on the path the bridge reads.
     *
     * The opponent seat holds [DECK] — sixty basic lands, no creatures and no spells — so it never
     * attacks, never blocks and never interacts. Every state change in this game is one this test
     * caused, which is what lets the assertions be about specific values rather than about whatever a
     * game happened to produce.
     *
     * `designationNames` is **not** covered here. The only designation a player can ever hold is
     * City's Blessing, which needs Ascend and ten permanents; the Monarch and Initiative designations
     * are registered on the game state, not on the player, so they never appear in that list however
     * the game goes.
     */
    @Test
    fun `live poison counters, the crown and the initiative all reach the snapshot`() =
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
                        TableRelay.createTable(session, roomId, options(name = "it_vit_$username"))
                    }
                assertTrue(created is TableCreated, "the real server should accept the mapped MatchOptions; got $created")
                tableId = UUID.fromString((created as TableCreated).table.tableId)

                assertJoined(join(session, roomId, tableId, AI_SEAT_NAME, SeatPlayerTypeCode.COMPUTER_MAD, DECK))
                assertJoined(join(session, roomId, tableId, username, SeatPlayerTypeCode.HUMAN, PLAYER_STATE_DECK))

                val start = withContext(Dispatchers.IO) { TableRelay.startMatch(session, roomId, tableId) }
                assertEquals(TableActionResult(action = TableActionCode.START_MATCH, ok = true), start)

                val matchStarting = events.awaitOfType<MatchStarting>(MATCH_START_TIMEOUT_MS, "a START_GAME push")
                gameId = UUID.fromString(matchStarting.gameId)

                val joined = withContext(Dispatchers.IO) { GameRelay.joinGame(session, gameId) }
                assertTrue(joined.ok, "the real server should accept joinGame for a match we are seated in; got $joined")

                events.awaitOfType<GameStarted>(GAME_INIT_TIMEOUT_MS, "a GAME_INIT push")

                val observed = playUntilPoisonCrownAndInitiative(session, gameId, events)

                val poisoned =
                    requireNotNull(observed.poisoned) {
                        "no snapshot ever carried a poison counter; answered=${observed.answered} " +
                            "callbacks=${callbackSummary()} ${observed.describeOurBoard()}"
                    }
                // Each tap of the Mox adds two, and it is tapped again on any turn its mana is needed,
                // so the count is a multiple of two rather than exactly two.
                val poison = poisoned.counters.single { it.name == POISON }.count
                assertTrue(poison >= 2 && poison % 2 == 0, "poison arrives two at a time, got ${poisoned.counters}")
                assertTrue(poisoned.viewer, "the Mox poisons its own controller, which is us")

                val monarch =
                    requireNotNull(observed.monarch) {
                        "never became the monarch; answered=${observed.answered} callbacks=${callbackSummary()}"
                    }
                assertTrue(monarch.monarch)
                assertTrue(monarch.viewer, "we are the seat that took the crown")

                val initiative =
                    requireNotNull(observed.initiative) {
                        "never took the initiative; answered=${observed.answered} callbacks=${callbackSummary()}"
                    }
                assertTrue(initiative.initiative)
                assertTrue(initiative.viewer)

                val last = observed.last!!
                assertTrue(
                    last.players.none { it.designationNames.isNotEmpty() },
                    "the Monarch and Initiative designations live on the game state, never on a player: ${last.players}",
                )

                println(
                    "GameRelayIT[vitals]: poison=${poisoned.counters} monarch=${monarch.name} " +
                        "initiative=${initiative.name} answered=${observed.answered} callbacks=${callbackSummary()}",
                )
            } finally {
                pump?.cancel()
                events.close()
                teardown(session, roomId, tableId, gameId)
            }
        }

    /**
     * The command zone in a real game. A crafted `PlayerView` can be given any `CommandObjectView`,
     * so the hermetic tests prove only that the mapper reads the four implementations; this proves the
     * server fills `commandList` on the path the bridge reads, and that the branch on the concrete
     * type produces the right kind for a real `CommanderView`.
     *
     * **It needs no play whatsoever.** A commander is in the command zone from the first snapshot, so
     * this starts the game and reads.
     *
     * **What this does not reach live, and why.** Only the commander kind. An emblem needs a
     * planeswalker's ultimate — many turns of loyalty, and an AI decision at that. A dungeon needs a
     * venture card and two turns of setup. A plane needs a Planechase game type. All three are covered
     * hermetically against real upstream view types; none is covered here, and that is stated rather
     * than papered over.
     */
    @Test
    fun `a live commander game fills the command zone for both seats`() =
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
                        TableRelay.createTable(
                            session,
                            roomId,
                            options(name = "it_cmd_$username", gameType = COMMANDER_GAME_TYPE, deckType = COMMANDER_DECK_TYPE),
                        )
                    }
                assertTrue(created is TableCreated, "the real server should accept a commander table; got $created")
                tableId = UUID.fromString((created as TableCreated).table.tableId)

                assertJoined(join(session, roomId, tableId, AI_SEAT_NAME, SeatPlayerTypeCode.COMPUTER_MAD, COMMANDER_DECK))
                assertJoined(join(session, roomId, tableId, username, SeatPlayerTypeCode.HUMAN, COMMANDER_DECK))

                val start = withContext(Dispatchers.IO) { TableRelay.startMatch(session, roomId, tableId) }
                assertEquals(TableActionResult(action = TableActionCode.START_MATCH, ok = true), start)

                val matchStarting = events.awaitOfType<MatchStarting>(MATCH_START_TIMEOUT_MS, "a START_GAME push")
                gameId = UUID.fromString(matchStarting.gameId)

                val joined = withContext(Dispatchers.IO) { GameRelay.joinGame(session, gameId) }
                assertTrue(joined.ok, "the real server should accept joinGame for a match we are seated in; got $joined")

                val observed = awaitCommandZone(session, gameId, events)

                val state =
                    requireNotNull(observed) {
                        "no snapshot ever carried a command object; callbacks=${callbackSummary()}"
                    }
                val everyCommandObject = state.players.flatMap { it.commandList }
                assertEquals(
                    2,
                    everyCommandObject.size,
                    "each seat gets its own commander, filtered by controller: $everyCommandObject",
                )
                assertTrue(
                    everyCommandObject.all { it.kind == CommandObjectKind.COMMANDER },
                    "a commander game's command zone holds commanders: $everyCommandObject",
                )
                assertTrue(
                    everyCommandObject.all { it.name == COMMANDER_NAME },
                    "both decks name the same commander: ${everyCommandObject.map { it.name }}",
                )
                assertEquals(
                    listOf("C16", "C16"),
                    everyCommandObject.map { it.setCode },
                    "a commander extends CardView, so it carries the printing the deck named",
                )
                assertEquals(listOf("28", "28"), everyCommandObject.map { it.collectorNumber })
                assertTrue(
                    everyCommandObject.all { it.rules.isNotEmpty() },
                    "the interface's rules text is what a vitals overlay would render: $everyCommandObject",
                )

                println(
                    "GameRelayIT[command]: ${everyCommandObject.map { "${it.kind}:${it.name} ${it.setCode}#${it.collectorNumber}" }} " +
                        "callbacks=${callbackSummary()}",
                )
            } finally {
                pump?.cancel()
                events.close()
                teardown(session, roomId, tableId, gameId)
            }
        }

    /**
     * Consumes pushes until one carries a command object, answering any prompt with the least
     * interesting legal reply so a mulligan question cannot stall the game before the assertion.
     */
    private suspend fun awaitCommandZone(
        session: SessionImpl,
        gameId: UUID,
        events: Channel<ServerMessage>,
    ): GameStateView? {
        val deadline = System.currentTimeMillis() + PLAY_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val remaining = minOf(PUSH_TIMEOUT_MS, deadline - System.currentTimeMillis())
            val event = withTimeoutOrNull(remaining) { events.receive() } ?: break

            stateOf(event)?.let { state ->
                if (state.players.any { it.commandList.isNotEmpty() }) return state
            }
            if (event is GameOver) break
            if (event is GamePrompted) answer(session, gameId, event.prompt)
        }
        return null
    }

    /**
     * Graveyards in a real game, on **both** seats — the case a single-player fixture cannot produce,
     * and the one that matters, because a graveyard is public information and the board shows the
     * opponent's too.
     *
     * It also **measures** what carrying the cards costs. A snapshot goes out on every state change, so
     * the size of one is a real design input rather than a curiosity: the test serializes the live
     * snapshot with the zones as they arrived, then again with them emptied — the same state as before
     * the cards were carried — and prints both, so the delta is measured on real data rather than
     * estimated from a card count.
     */
    @Test
    fun `a live graveyard arrives for both seats, and the snapshot cost is measured`() =
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
                        TableRelay.createTable(session, roomId, options(name = "it_mill_$username"))
                    }
                assertTrue(created is TableCreated, "the real server should accept the mapped MatchOptions; got $created")
                tableId = UUID.fromString((created as TableCreated).table.tableId)

                assertJoined(join(session, roomId, tableId, AI_SEAT_NAME, SeatPlayerTypeCode.COMPUTER_MAD, DECK))
                assertJoined(join(session, roomId, tableId, username, SeatPlayerTypeCode.HUMAN, MILL_DECK))

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

                val state =
                    requireNotNull(millBothGraveyards(session, gameId, events, opponentId)) {
                        "no snapshot ever carried a graveyard on both seats; callbacks=${callbackSummary()}"
                    }

                val ours = state.players.single { it.viewer }
                val theirs = state.players.single { !it.viewer }
                assertTrue(ours.graveyard.isNotEmpty(), "the Tome Scour goes to our own graveyard when it resolves")
                assertTrue(
                    ours.graveyard.any { it.name == TOME_SCOUR },
                    "our graveyard should hold the spell we cast, got ${ours.graveyard.map { it.name }}",
                )
                assertTrue(
                    theirs.graveyard.size >= 5,
                    "milling five puts five cards in the opponent's graveyard, got ${theirs.graveyard.size}",
                )
                assertEquals(
                    ours.graveyardCount,
                    ours.graveyard.size,
                    "the count and the list are the same data measured",
                )
                assertEquals(theirs.graveyardCount, theirs.graveyard.size)
                assertTrue(
                    theirs.graveyard.all { it.name.isNotBlank() },
                    "an opponent's graveyard is public, so the cards arrive named: ${theirs.graveyard}",
                )

                val withZones = ProtocolJson.json.encodeToString(state).length
                val withoutZones =
                    ProtocolJson.json
                        .encodeToString(
                            state.copy(players = state.players.map { it.copy(graveyard = emptyList(), exile = emptyList()) }),
                        ).length
                println(
                    "GameRelayIT[zones]: ourGraveyard=${ours.graveyard.size} theirGraveyard=${theirs.graveyard.size} " +
                        "snapshotBytes=$withZones withoutZoneCards=$withoutZones delta=${withZones - withoutZones} " +
                        "callbacks=${callbackSummary()}",
                )
            } finally {
                pump?.cancel()
                events.close()
                teardown(session, roomId, tableId, gameId)
            }
        }

    /**
     * Casts `Tome Scour` at the opponent and returns the first snapshot in which **both** seats have a
     * non-empty graveyard. Decisions come from the server's own `canPlayObjects`, and action is
     * confined to our own precombat main phase.
     */
    private suspend fun millBothGraveyards(
        session: SessionImpl,
        gameId: UUID,
        events: Channel<ServerMessage>,
        opponentId: String,
    ): GameStateView? {
        val deadline = System.currentTimeMillis() + PLAY_TIMEOUT_MS
        val tappedForThisPayment = mutableSetOf<String>()
        var castSent = false
        var answered = 0

        while (System.currentTimeMillis() < deadline) {
            val remaining = minOf(PUSH_TIMEOUT_MS, deadline - System.currentTimeMillis())
            val event = withTimeoutOrNull(remaining) { events.receive() } ?: break

            stateOf(event)?.let { state ->
                if (state.players.size == 2 && state.players.all { it.graveyard.isNotEmpty() }) return state
            }
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
            val ourMainPhase =
                state.viewerHasPriority &&
                    state.viewerPlayerId != null &&
                    state.viewerPlayerId == state.activePlayerId &&
                    state.phase == TurnPhaseCode.PRECOMBAT_MAIN &&
                    state.step == PhaseStepCode.PRECOMBAT_MAIN

            when {
                prompt is SelectPrompt && ourMainPhase -> {
                    tappedForThisPayment.clear()
                    val island = state.hand.firstOrNull { it.name == ISLAND && it.id in playable }
                    val scour = state.hand.firstOrNull { it.name == TOME_SCOUR && it.id in playable }
                    when {
                        island != null -> sendUuid(session, gameId, island.id)
                        scour != null -> {
                            castSent = true
                            sendUuid(session, gameId, scour.id)
                        }
                        else -> pass(session, gameId)
                    }
                }
                prompt is TargetPrompt && castSent && opponentId in prompt.targetIds -> {
                    sendUuid(session, gameId, opponentId)
                    castSent = false
                }
                prompt is PlayManaPrompt -> {
                    val land = ourBoard.firstOrNull { it.card.name == ISLAND && !it.tapped && it.card.id !in tappedForThisPayment }
                    if (land != null) {
                        tappedForThisPayment += land.card.id
                        sendUuid(session, gameId, land.card.id)
                    } else {
                        pass(session, gameId)
                    }
                }
                else -> answer(session, gameId, prompt)
            }
            answered++
            if (answered > MAX_ANSWERS_TO_CAST) {
                throw AssertionError(
                    "answered $MAX_ANSWERS_TO_CAST prompts without filling both graveyards. " +
                        "last prompt: ${event.prompt}. Callbacks: ${callbackSummary()}",
                )
            }
        }
        return null
    }

    /** The seats, at the moment each piece of per-player state first appeared. */
    private class PlayerStateObserved {
        var poisoned: GamePlayerView? = null
        var monarch: GamePlayerView? = null
        var initiative: GamePlayerView? = null
        var last: GameStateView? = null
        var answered: Int = 0

        /**
         * What our own board and hand looked like when the loop gave up. "Never saw poison" has two
         * very different causes — the source was never cast, or it was cast and never tapped — and the
         * message has to say which, or the next reader is guessing the way the last one did.
         */
        fun describeOurBoard(): String {
            val viewer = last?.players?.singleOrNull { it.viewer } ?: return "no viewer seat in the last snapshot"
            val board = viewer.battlefield.map { "${it.card.name}${if (it.tapped) "(T)" else ""}" }
            return "turn=${last?.turn} board=$board hand=${last?.hand?.map { it.name }} counters=${viewer.counters}"
        }
    }

    /**
     * Plays a land, taps `Mox Poison` for the poison, casts a `Palace Sentinels` for the crown, and
     * sacrifices a `Dungeoneer's Pack` for the initiative.
     *
     * Every decision comes from the server's own `canPlayObjects`: play or activate what it says is
     * available, otherwise pass. Action is confined to our own precombat main phase, where a
     * `GAME_SELECT` means "you have priority" rather than "declare attackers".
     *
     * Two prompts need answers this test's other loops do not:
     * - a `ChooseAbilityPrompt`, for a permanent whose object id alone does not say which of its
     *   abilities is wanted;
     * - the "you still have mana in your mana pool… pass anyway?" question, answered **yes**. Answering
     *   no sends `PASS_PRIORITY_CANCEL_ALL_ACTIONS` and the server immediately asks again, which is an
     *   unbounded loop rather than a failure.
     */
    private suspend fun playUntilPoisonCrownAndInitiative(
        session: SessionImpl,
        gameId: UUID,
        events: Channel<ServerMessage>,
    ): PlayerStateObserved {
        val observed = PlayerStateObserved()
        val deadline = System.currentTimeMillis() + AURA_PLAY_TIMEOUT_MS
        val tappedForThisPayment = mutableSetOf<String>()
        // An object whose cost could not be paid this turn. Without this the loop cancels the payment,
        // gets priority back with the game in exactly the state it was in, picks the same object
        // again, and never advances -- the "refused reply, re-asked" loop that every loop here exists
        // to avoid. Cleared on the next turn, when a fresh untapped board can pay.
        val unaffordableThisTurn = mutableSetOf<String>()
        var turnOfBlockedSet = -1
        var pendingActivation: String? = null

        while (System.currentTimeMillis() < deadline) {
            val remaining = minOf(PUSH_TIMEOUT_MS, deadline - System.currentTimeMillis())
            val event = withTimeoutOrNull(remaining) { events.receive() } ?: break

            stateOf(event)?.let { state ->
                observed.last = state
                state.players.firstOrNull { it.counters.any { counter -> counter.name == POISON } }?.let { observed.poisoned = it }
                state.players.firstOrNull { it.monarch }?.let { observed.monarch = it }
                state.players.firstOrNull { it.initiative }?.let { observed.initiative = it }
            }
            if (observed.poisoned != null && observed.monarch != null && observed.initiative != null) break
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
            val ourMainPhase =
                state.viewerHasPriority &&
                    state.viewerPlayerId != null &&
                    state.viewerPlayerId == state.activePlayerId &&
                    state.phase == TurnPhaseCode.PRECOMBAT_MAIN &&
                    state.step == PhaseStepCode.PRECOMBAT_MAIN

            if (state.turn != turnOfBlockedSet) {
                unaffordableThisTurn.clear()
                turnOfBlockedSet = state.turn
            }

            when {
                prompt is SelectPrompt && ourMainPhase -> {
                    tappedForThisPayment.clear()
                    val next = nextPlayerStateAction(state, ourBoard, playable, observed, unaffordableThisTurn)
                    pendingActivation = next
                    if (next != null) sendUuid(session, gameId, next) else pass(session, gameId)
                }
                prompt is ChooseAbilityPrompt -> {
                    val wanted =
                        prompt.choices.firstOrNull { it.text.contains("monarch", ignoreCase = true) }
                            ?: prompt.choices.firstOrNull { it.text.contains("initiative", ignoreCase = true) }
                            ?: prompt.choices.firstOrNull()
                    if (wanted != null) sendUuid(session, gameId, wanted.abilityId) else pass(session, gameId)
                }
                prompt is AskPrompt && prompt.message.contains("mana pool", ignoreCase = true) ->
                    withContext(Dispatchers.IO) { GameRelay.sendPlayerBoolean(session, gameId, true) }
                prompt is PlayManaPrompt -> {
                    val source = manaSource(ourBoard, tappedForThisPayment, needPoison = observed.poisoned == null)
                    if (source != null) {
                        tappedForThisPayment += source
                        sendUuid(session, gameId, source)
                    } else {
                        // Nothing left to tap. Cancel, and do not offer this object again until the
                        // lands untap, or the very next select prompt picks it straight back.
                        pendingActivation?.let { unaffordableThisTurn += it }
                        pendingActivation = null
                        pass(session, gameId)
                    }
                }
                else -> answer(session, gameId, prompt)
            }
            observed.answered++
            if (observed.answered > MAX_ANSWERS_TO_CAST) {
                throw AssertionError(
                    "answered $MAX_ANSWERS_TO_CAST prompts without seeing all three. poison=${observed.poisoned != null} " +
                        "monarch=${observed.monarch != null} initiative=${observed.initiative != null} " +
                        "last prompt: ${event.prompt}. Callbacks: ${callbackSummary()}",
                )
            }
        }
        return observed
    }

    /**
     * The object to play or activate next, or `null` to pass. Ordered so each objective is reached as
     * early as the server allows: land first (more mana), then the free `Mox`, then the `Pack` and the
     * `Throne` — casting each from hand before there is one on the battlefield to activate.
     *
     * [unaffordable] holds what a payment could not be funded for this turn. The server lists an
     * activated ability in `canPlayObjects` on the strength of the mana it *could* produce, which
     * counts sources this loop declines to tap — so "playable" is not on its own a guarantee that this
     * loop can pay, and an object that failed once must not be offered again until the lands untap.
     */
    private fun nextPlayerStateAction(
        state: GameStateView,
        ourBoard: List<GamePermanentView>,
        playable: Set<String>,
        observed: PlayerStateObserved,
        unaffordable: Set<String>,
    ): String? {
        fun inHand(name: String) = state.hand.firstOrNull { it.name == name && it.id in playable && it.id !in unaffordable }

        fun onBoard(name: String) =
            ourBoard.firstOrNull {
                it.card.name == name && it.card.id in playable && !it.tapped && it.card.id !in unaffordable
            }

        // The Mox comes down before anything else, including the land drop. It costs {0}, so it
        // competes with nothing -- and poison only arrives when a payment taps it, so it has to be on
        // the battlefield before the first payment. A run that cast it a few turns in ended with the
        // Mox untapped and no counters at all: by then the board had enough lands and Treasures that
        // the server never asked again.
        return when {
            inHand(MOX) != null && ourBoard.none { it.card.name == MOX } -> inHand(MOX)!!.id
            // Then a land every turn: it is what everything else waits on, so a turn that skips the
            // drop costs every later turn a mana.
            inHand(PLAINS) != null -> inHand(PLAINS)!!.id
            // Sentinels is cast for the crown, and then kept being cast while poison is missing. The
            // Mox only poisons its controller when something taps it for mana, and on a board with
            // enough lands the server stops asking who pays: across one failing run of 54 turns there
            // were nine mana prompts in total, all of them before the Mox was down. A spell that must
            // be paid for is the forcing function.
            (observed.monarch == null || observed.poisoned == null) && inHand(SENTINELS) != null -> inHand(SENTINELS)!!.id
            observed.initiative == null && onBoard(PACK) != null -> onBoard(PACK)!!.card.id
            observed.initiative == null && inHand(PACK) != null && ourBoard.none { it.card.name == PACK } -> inHand(PACK)!!.id
            else -> null
        }
    }

    /**
     * What to tap for one point of the outstanding cost. `Mox Poison` goes first while poison is still
     * missing, because tapping it is what produces the counters; after that any untapped land will do.
     */
    private fun manaSource(
        ourBoard: List<GamePermanentView>,
        alreadyTapped: Set<String>,
        needPoison: Boolean,
    ): String? {
        val available = ourBoard.filter { !it.tapped && it.card.id !in alreadyTapped }
        val mox = available.firstOrNull { it.card.name == MOX }
        val land = available.firstOrNull { it.card.name == PLAINS }
        return when {
            needPoison && mox != null -> mox.card.id
            land != null -> land.card.id
            else -> mox?.card?.id
        }
    }

    /** Passes priority / declines the outstanding question. */
    private suspend fun pass(
        session: SessionImpl,
        gameId: UUID,
    ) = withContext(Dispatchers.IO) { GameRelay.sendPlayerBoolean(session, gameId, false) }

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
     * not about playing well. Each is sent through [GameRelay], so the reply half of this is under
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
    private fun options(
        name: String,
        gameType: String = GAME_TYPE,
        deckType: String = DECK_TYPE,
    ): CreateTableOptions =
        CreateTableOptions(
            name = name,
            gameType = gameType,
            deckType = deckType,
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
