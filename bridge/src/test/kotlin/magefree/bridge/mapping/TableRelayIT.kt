package magefree.bridge.mapping

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mage.remote.SessionImpl
import magefree.bridge.xmage.BridgeMageClient
import magefree.bridge.xmage.XMageConnection
import magefree.bridge.xmage.XMageServerTarget
import magefree.protocol.CreateTableOptions
import magefree.protocol.DeckList
import magefree.protocol.DeckListCard
import magefree.protocol.MatchStarting
import magefree.protocol.SeatPlayerTypeCode
import magefree.protocol.ServerMessage
import magefree.protocol.SkillLevelCode
import magefree.protocol.TableActionCode
import magefree.protocol.TableActionResult
import magefree.protocol.TableCreated
import magefree.protocol.TableDetail
import magefree.protocol.TableNotFound
import magefree.protocol.TableStateCode
import magefree.protocol.TableUpdated
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Live end-to-end proof of the table-action relay: drive a **real** [SessionImpl] against
 * the reference server through the whole host → seat → start round trip —
 * `TableRelay.createTable`/`joinTable`/`startMatch` — and observe the server's own pushed
 * lifecycle callbacks mapped by [CallbackMapper].
 *
 * The hermetic `TableRelayTest` can only assert the `mage.*` arguments the relay *builds*, because its
 * `SessionImpl` is a fake that accepts anything. This test asserts the **server's verdict** on those
 * arguments instead: that the real server accepts the [MatchOptionsMapper]-built `MatchOptions`, the
 * [DeckListMapper]-built `DeckCardLists`, the mapped `PlayerType` and the skill int; that a real
 * `TableView` comes back and maps to a [TableCreated] with a usable table id; and that `START_GAME`
 * really reaches the app schema as a [MatchStarting]. It stops at the game-start signal — gameplay past
 * it is the game layer.
 *
 * **Seat state.** It also reads the table back through [TableRelay.tableDetail] at each
 * stage: after the AI is seated the read shows one filled seat carrying the AI's name and mapped player
 * type (and one still-open human seat), and after the host joins the server itself reports
 * `READY_TO_START`. That is the regression guard the original defect lacked — the room's seats and the
 * host's start gate both come from this read, so if it ever stops carrying seats this test fails.
 *
 * **The scenario, and why these particular choices.** The server validates every one of these, so each
 * is the least restrictive value that the real server actually accepts:
 * - **Game type `"Two Player Duel"`** — a two-seat, non-tournament match from the server's own
 *   `config.xml` game types (the ones `LobbyRelayIT` proves are offered).
 * - **Deck type `"Constructed - Freeform Unlimited"`** — upstream `mage.deck.FreeformUnlimited`, whose
 *   `validate()` always returns true and whose minimum deck size is 0. Every other constructed type
 *   imposes a format/banlist/size check that `TableController.joinTable` would decline (and which, for
 *   the *owner's* join, also removes the table server-side), so this is the deck type that keeps the
 *   test about the **relay's** contract rather than about deck legality.
 * - **The deck itself** — 60× `Forest`, `M21` #272. `Deck.load` resolves each card by
 *   **(setCode, cardNumber)** only (the name is not used for the lookup), so the pair must exist in the
 *   server's card database; basic lands are unlimited in every format, so a mono-basic list is legal for
 *   any deck type and stays comfortably above the 40-card minimum other formats would want.
 * - **Seats `[HUMAN, COMPUTER_MAD]`, joined AI-first** — exactly the desktop's solo-vs-AI flow
 *   (`NewTableDialog` joins the non-human seats, then itself), so a single client can fill the table and
 *   reach match-start without a second human session. `Table.getNextAvailableSeat` matches a join to a
 *   seat **by player type**, so the AI join needs `PlayerType.COMPUTER_MAD` and the host's own join
 *   `HUMAN`.
 *
 * **Teardown.** The reference server is long-lived, so a leaked table would poison later runs (and
 * `LobbyRelayIT`, which asserts the room has no open tables). Every test removes its table and
 * disconnects in a `finally`, including after a failed assertion. `leaveTable` is deliberately *not*
 * used for cleanup: upstream refuses to leave a table that has already started
 * (`roomLeaveTableOrTournament` returns false unless the table is WAITING/READY_TO_START), whereas the
 * owner's `removeTable` tears it down in any state.
 *
 * **Env-gated:** enabled only when `XMAGE_SERVER` is set (mirroring the `ConnectAuthenticateIT`);
 * otherwise JUnit reports it *skipped*, keeping `./scripts/dev gradle check` hermetic.
 *
 * ```
 * ./scripts/dev up xmage-server
 * XMAGE_SERVER=xmage-server:17171 ./scripts/dev gradle :bridge:test --tests '*TableRelayIT' --rerun-tasks
 * ```
 */
@EnabledIfEnvironmentVariable(named = XMageServerTarget.ENV_VAR, matches = ".+")
class TableRelayIT {
    private companion object {
        /** The two-seat, non-tournament game type the reference server offers (see `config.xml`). */
        const val GAME_TYPE = "Two Player Duel"

        /** `mage.deck.FreeformUnlimited`: `validate()` always true, minimum deck size 0. */
        const val DECK_TYPE = "Constructed - Freeform Unlimited"

        /** The name of the AI seat's player; upstream does not constrain it. */
        const val AI_SEAT_NAME = "Computer"

        /** How long to wait for the server to push `START_GAME` after `startMatch` was accepted. */
        const val MATCH_START_TIMEOUT_MS = 120_000L

        /** How long a `GetTable` read may take to reflect a seat change (the lobby list refreshes every 2s). */
        const val DETAIL_TIMEOUT_MS = 30_000L

        /** How often to re-read while waiting for the lobby snapshot to catch up. */
        const val DETAIL_POLL_MS = 500L

        /**
         * A deck the real server accepts for [DECK_TYPE]: 60 basic Forests. `Deck.load` looks each card
         * up by **(setCode, cardNumber)**, so the printing must exist — `M21` #272 is a `Forest` in the
         * server's card database. Basic lands are unlimited in every format.
         */
        val DECK =
            DeckList(
                name = "it_forests",
                author = "table-relay-it",
                cards = listOf(DeckListCard(cardName = "Forest", setCode = "M21", collectorNumber = "272", amount = 60)),
                sideboard = emptyList(),
            )
    }

    @Test
    fun `hosts a table, seats an AI, starts the match and observes the mapped MatchStarting`() =
        runBlocking {
            // XMage validates usernames: [a-z0-9_], length 3..14. "it_" + 8 hex = 11 chars.
            val username = uniqueUsername()
            val client = BridgeMageClient()
            val session = connect(client, username)
            val roomId = requireNotNull(session.mainRoomId) { "the main room id should be resolvable once connected" }

            var tableId: UUID? = null
            var pump: Job? = null
            try {
                // Subscribe to the raw callbacks *before* any table action, so the pushed JOINED_TABLE
                // and START_GAME cannot be missed; onSubscription makes the hand-off race-free.
                val mapped = CopyOnWriteArrayList<ServerMessage>()
                val matchStarting = CompletableDeferred<MatchStarting>()
                pump = startCallbackPump(client, mapped, matchStarting)

                // 1. Create — the server's own TableView, mapped to a TableCreated (not a failed result).
                val created =
                    withContext(Dispatchers.IO) {
                        TableRelay.createTable(session, roomId, options(name = "it_table_$username"))
                    }
                assertTrue(
                    created is TableCreated,
                    "the real server should accept the mapped MatchOptions; got $created",
                )
                val table = (created as TableCreated).table
                tableId = UUID.fromString(table.tableId)
                assertEquals(GAME_TYPE, table.gameType, "the created table should carry the requested game type")
                assertEquals(DECK_TYPE, table.deckType, "the created table should carry the requested deck type")
                assertEquals(username, table.controllerName, "the creating user should control the new table")
                assertEquals(2, table.seatsTotal, "the two seat player types should yield a two-seat table")
                assertEquals(0, table.seatsFilled, "a freshly created table has no one seated yet")
                assertEquals(TableStateCode.WAITING, table.state, "a freshly created table waits for players")

                // 2. Join — the AI seat first (as the desktop does), then our own human seat. Both
                //    submit the deck at join, so this also proves the mapped DeckCardLists is accepted.
                val aiJoin = join(session, roomId, tableId, AI_SEAT_NAME, SeatPlayerTypeCode.COMPUTER_MAD)
                assertEquals(
                    TableActionResult(action = TableActionCode.JOIN, ok = true),
                    aiJoin,
                    "the server should seat a COMPUTER_MAD player with the mapped deck",
                )
                // 2a. Read the table back: the AI is really in a seat, with its name and
                //     mapped player type, and the human seat is still open. This is the assertion the
                //     original defect could never have satisfied — the room had no way to see a seat.
                val seated =
                    awaitDetail(session, roomId, tableId, "the AI seat to be filled") { it.table.seatsFilled == 1 }
                assertEquals(2, seated.seats.size, "a two-seat table should report two seats")
                assertEquals(2, seated.table.seatsTotal, "the summary's seat total should agree with the seat list")
                assertEquals(
                    listOf(0, 1),
                    seated.seats.map { it.index },
                    "seats should be reported in slot order",
                )
                val aiSeat = seated.seats.single { it.occupied }
                assertEquals(AI_SEAT_NAME, aiSeat.playerName, "the filled seat should name the AI we seated")
                assertEquals(
                    SeatPlayerTypeCode.COMPUTER_MAD,
                    aiSeat.playerType,
                    "the AI seat's upstream PlayerType should map back to COMPUTER_MAD",
                )
                val openSeat = seated.seats.single { !it.occupied }
                assertNull(openSeat.playerName, "an empty seat has no player name")
                assertEquals(
                    SeatPlayerTypeCode.HUMAN,
                    openSeat.playerType,
                    "the still-open seat is the human one the host will take",
                )
                assertEquals(
                    TableStateCode.WAITING,
                    seated.table.state,
                    "one seat still open, so the server is not ready to start",
                )

                val hostJoin = join(session, roomId, tableId, username, SeatPlayerTypeCode.HUMAN)
                assertEquals(
                    TableActionResult(action = TableActionCode.JOIN, ok = true),
                    hostJoin,
                    "the server should seat the host with the mapped deck",
                )

                // 2b. Both seats filled → the server itself reports READY_TO_START. That state, not any
                //     client-invented per-seat "ready" flag, is what the host's start control is gated
                //     on — and step 3 proves the server accepts a start in exactly this state.
                val ready =
                    awaitDetail(session, roomId, tableId, "the table to become READY_TO_START") {
                        it.table.state == TableStateCode.READY_TO_START
                    }
                assertEquals(2, ready.table.seatsFilled, "READY_TO_START means every seat is filled")
                assertTrue(
                    ready.seats.all { it.occupied },
                    "every mapped seat should be occupied once the table is ready, got ${ready.seats}",
                )
                assertTrue(
                    ready.seats.any { it.playerName == username },
                    "the host's own seat should carry the host's name, got ${ready.seats}",
                )

                // 3. Start — accepted only once every seat is filled (upstream's READY_TO_START gate).
                val start = withContext(Dispatchers.IO) { TableRelay.startMatch(session, roomId, tableId) }
                assertEquals(
                    TableActionResult(action = TableActionCode.START_MATCH, ok = true),
                    start,
                    "the server should start the match once both seats are filled",
                )

                // 4. Observe — the pushed START_GAME, mapped by CallbackMapper to a MatchStarting. This
                //    is the game boundary: the game id is asserted to be real, nothing is played.
                val event = withTimeout(MATCH_START_TIMEOUT_MS) { matchStarting.await() }
                assertNotNull(event, "the server should push a START_GAME once the match starts")
                assertEquals(
                    tableId.toString(),
                    event.tableId,
                    "MatchStarting should identify the table we started",
                )
                assertTrue(
                    event.gameId.isNotBlank() && runCatching { UUID.fromString(event.gameId) }.isSuccess,
                    "MatchStarting should carry the new game's id, got '${event.gameId}'",
                )
                assertNotNull(event.playerId, "MatchStarting should identify our own seat")

                // 5. A *read* of the table after the match started must independently
                //    report the same game id `MatchStarting` pushed, proving `TableView.getGames()`
                //    really is populated at this point and not just in theory. This is what lets a
                //    client that missed the one-shot push (it opened the room afterwards) still learn
                //    the game id.
                val started =
                    awaitDetail(session, roomId, tableId, "the table read to report the started game") {
                        it.activeGameId != null
                    }
                assertEquals(
                    event.gameId,
                    started.activeGameId,
                    "a table read after match-start should report the same game id MatchStarting pushed",
                )

                // The join also pushed a JOINED_TABLE, which maps to a TableUpdated for our table —
                // the other half of the table-lifecycle callback path.
                assertTrue(
                    mapped.filterIsInstance<TableUpdated>().any { it.tableId == tableId.toString() },
                    "expected a mapped TableUpdated for our table, got $mapped",
                )
            } finally {
                pump?.cancel()
                teardown(session, roomId, tableId)
            }
        }

    @Test
    fun `a real server decline maps to a typed JOIN failure`() =
        runBlocking {
            val username = uniqueUsername()
            val session = connect(BridgeMageClient(), username)
            val roomId = requireNotNull(session.mainRoomId) { "the main room id should be resolvable once connected" }

            try {
                // No such table in the room: upstream's GamesRoomImpl.joinTable returns false for an
                // unknown table id. The relay must surface that as a *typed* failure, not drop it.
                val result = join(session, roomId, UUID.randomUUID(), username, SeatPlayerTypeCode.HUMAN)

                assertEquals(TableActionCode.JOIN, result.action, "the failure should answer the JOIN action")
                assertFalse(result.ok, "joining a table that does not exist must not report success")
                assertNotNull(result.reason, "a declined action should carry a reason")
            } finally {
                teardown(session, roomId, tableId = null)
            }
        }

    @Test
    fun `a GetTable for a table the room does not list maps to a typed not-found`() =
        runBlocking {
            val session = connect(BridgeMageClient(), uniqueUsername())
            val roomId = requireNotNull(session.mainRoomId) { "the main room id should be resolvable once connected" }

            try {
                // Upstream has no single-table read, so the relay filters the room's table list. A miss
                // must be a *typed* not-found — never an empty TableDetail, which the room would render
                // as a real table with no seats.
                val missing = UUID.randomUUID()
                val reply = withContext(Dispatchers.IO) { TableRelay.tableDetail(session, roomId, missing) }

                assertTrue(reply is TableNotFound, "an unknown table id should map to TableNotFound, got $reply")
                assertEquals(missing.toString(), (reply as TableNotFound).tableId)
                assertNotNull(reply.reason, "a not-found should carry a reason")
            } finally {
                teardown(session, roomId, tableId = null)
            }
        }

    /** A username satisfying XMage's `[a-z0-9_]`, length 3..14 rule: `it_` + 8 hex = 11 chars. */
    private fun uniqueUsername(): String = "it_${UUID.randomUUID().toString().replace("-", "").substring(0, 8)}"

    /**
     * Reads [tableId] until [condition] holds, or fails after [DETAIL_TIMEOUT_MS] describing [what].
     *
     * The poll is not test flakiness padding: `GamesRoomImpl` rebuilds the lobby's table list on a
     * **two-second** timer, so a table read straight after a join legitimately still shows the previous
     * snapshot. That is exactly the eventual consistency the app lives with (it re-reads on each table
     * push), so the test waits the same way rather than pretending the read is instantaneous.
     */
    private suspend fun awaitDetail(
        session: SessionImpl,
        roomId: UUID,
        tableId: UUID,
        what: String,
        condition: (TableDetail) -> Boolean,
    ): TableDetail {
        var last: ServerMessage? = null
        val deadline = System.currentTimeMillis() + DETAIL_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val reply = withContext(Dispatchers.IO) { TableRelay.tableDetail(session, roomId, tableId) }
            last = reply
            if (reply is TableDetail && condition(reply)) return reply
            delay(DETAIL_POLL_MS)
        }
        throw AssertionError("timed out after ${DETAIL_TIMEOUT_MS}ms waiting for $what; last read was $last")
    }

    /**
     * Connects and authenticates a real [SessionImpl] driven by [client] against the `XMAGE_SERVER`
     * target. The relay takes a raw `SessionImpl` (not the `XMageSession` wrapper), so the test owns
     * the session here — the same way `TableRelayTest` hands the relay its fake one.
     */
    private suspend fun connect(
        client: BridgeMageClient,
        username: String,
    ): SessionImpl {
        val target = requireNotNull(XMageServerTarget.fromEnv()) { "XMAGE_SERVER must be set for this test" }
        val session = SessionImpl(client)
        val connection =
            XMageConnection.build(
                host = target.host,
                port = target.port,
                username = username,
                password = "",
            )
        val connected = withContext(Dispatchers.IO) { session.connectStart(connection) }
        check(connected) { "connectStart should succeed against the reference server" }
        return session
    }

    /** The [CreateTableOptions] the real server accepts — see the class KDoc for why each value. */
    private fun options(name: String): CreateTableOptions =
        CreateTableOptions(
            name = name,
            gameType = GAME_TYPE,
            deckType = DECK_TYPE,
            players = listOf(SeatPlayerTypeCode.HUMAN, SeatPlayerTypeCode.COMPUTER_MAD),
            rated = false,
            winsNeeded = 1,
            freeMulligans = 0,
            skillLevel = SkillLevelCode.CASUAL,
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
     * Collects [client]'s raw callbacks, maps each through [CallbackMapper] (the same mapper the relay
     * uses), records the mapped events in [mapped] and completes [matchStarting] on the first
     * [MatchStarting]. Returns once the collector is *subscribed*, so a callback pushed by a later
     * action cannot be missed.
     */
    private suspend fun CoroutineScope.startCallbackPump(
        client: BridgeMageClient,
        mapped: MutableList<ServerMessage>,
        matchStarting: CompletableDeferred<MatchStarting>,
    ): Job {
        val subscribed = CompletableDeferred<Unit>()
        val job =
            launch(Dispatchers.Default) {
                client.callbacks
                    .onSubscription { subscribed.complete(Unit) }
                    .collect { raw ->
                        val event = CallbackMapper.map(raw) ?: return@collect
                        mapped += event
                        if (event is MatchStarting) matchStarting.complete(event)
                    }
            }
        subscribed.await()
        return job
    }

    /**
     * Removes [tableId] (if one was created) and disconnects, so a repeat run starts from a clean
     * server. Best-effort: a teardown failure must not mask the assertion that caused it.
     */
    private suspend fun teardown(
        session: SessionImpl,
        roomId: UUID,
        tableId: UUID?,
    ) {
        withContext(Dispatchers.IO) {
            if (tableId != null) {
                runCatching { TableRelay.removeTable(session, roomId, tableId) }
            }
            runCatching { session.connectStop(false, false) }
        }
    }
}
