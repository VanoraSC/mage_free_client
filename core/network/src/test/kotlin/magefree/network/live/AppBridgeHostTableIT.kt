package magefree.network.live

import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import magefree.decks.model.Deck
import magefree.decks.model.DeckEntry
import magefree.decks.model.DeckId
import magefree.model.SkillLevel
import magefree.network.live.LiveBridge.Companion.awaitValue
import magefree.network.live.LiveBridge.Companion.requireTarget
import magefree.network.live.LiveBridge.Companion.uniqueUsername
import magefree.network.table.CreateTableOptions
import magefree.network.table.SeatPlayerType
import magefree.network.table.TableClient
import magefree.network.table.TableRef
import magefree.network.table.TableServerState
import magefree.network.table.TableState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import magefree.model.TableState as LobbyTableState

/**
 * The live host flow, driven end to end by the app's **real** [TableClient] over a real bridge and the
 * reference XMage server (story 0045): create → seat an AI → seat ourselves → the table becomes
 * **ready** → start → the `MatchStarting` signal.
 *
 * This is the app-side counterpart of `:bridge`'s `TableRelayIT`. That test proves the *bridge* speaks
 * XMage correctly; this one proves the layer the user actually sits behind —
 * [magefree.network.ktor.KtorBridgeClient] → `PendingRequests` → `DefaultTableClient` →
 * `TableEventFold`/`OptionsMapper`/`DeckListMapper` — turns real server data into the right
 * **app-schema** [TableState]. Stories 0037/0040/0041 were all previously verified only against
 * `FakeBridgeClient`/`FakeTableClient`, i.e. against the app's own idea of what the server would say.
 *
 * **Why these particular scenario values** (each is what the real server actually accepts; see
 * `:bridge`'s `TableRelayIT` for the full reasoning):
 * - `"Two Player Duel"` — a two-seat, non-tournament game type from the server's own `config.xml`.
 * - `"Constructed - Freeform Unlimited"` — `mage.deck.FreeformUnlimited`: `validate()` always true,
 *   minimum deck size 0, so the test stays about the client's contract and not about deck legality.
 * - 60× `Forest` (`M21` #272) — `Deck.load` resolves a card by **(setCode, cardNumber)**, so the
 *   printing must exist; basic lands are unlimited in every format.
 * - seats `[Human, ComputerMad]`, joined **AI first** — the desktop's solo-vs-AI flow (story 0041).
 *   `Table.getNextAvailableSeat` matches a join to a seat *by player type*, so the AI join must name
 *   [SeatPlayerType.ComputerMad] and our own join [SeatPlayerType.Human].
 *
 * **Teardown.** The reference server is long-lived, so a leaked table would poison later runs (and
 * `:bridge`'s `LobbyRelayIT`, which asserts the room has no open tables). The table is removed in a
 * `finally`, including after a failed assertion, so the suite is repeatable. `removeTable` — not
 * `leaveTable` — because upstream refuses to leave a table that has already started.
 *
 * **Resume/reconnect is deliberately not covered here** — see the closing note at the end of this class.
 *
 * **Env-gated** on `BRIDGE_URL`; skipped (not failed) without it.
 *
 * ```
 * ./scripts/dev up bridge
 * BRIDGE_URL=localhost:8080 ./gradlew :core:network:testDebugUnitTest --tests '*live*' --rerun-tasks
 * ```
 */
class AppBridgeHostTableIT {
    @Test
    fun `hosts a table, seats an AI and itself, reaches ready, starts and observes MatchStarting`() {
        val target = requireTarget()
        runBlocking {
            val username = uniqueUsername()
            val tableName = "it_table_$username"
            val bridge = LiveBridge(this, target)
            bridge.connect(username)

            var tableId: String? = null
            var observer: Job? = null
            try {
                // 1. Create. The app-schema options are mapped by `OptionsMapper` onto the wire shape the
                //    bridge turns into upstream MatchOptions — the real server's verdict on that mapping
                //    is this call's success (a fake would accept anything).
                val created =
                    bridge.tables
                        .createTable(options(tableName))
                        .expect("the real server should accept the mapped create options")
                tableId = created.tableId
                assertCreated(created, tableName)

                // 1a. The same table read back through the *lobby* client proves LobbyMapper against real
                //     server data — the app's browse view of a table whose every field we chose.
                assertLobbyListing(bridge, created.tableId, tableName, username)

                // 2. Observe from now on, so no push (least of all MatchStarting) can be missed. The seed
                //    is the create's own TableRef, exactly as `:feature:tables` seeds a hosted room.
                val states = CopyOnWriteArrayList<TableState>()
                observer =
                    launch {
                        bridge.tables.observeTable(created.tableId, created.toSeed()).collect { states += it }
                    }

                // 3. Seat the AI, then ourselves — both submit the deck at join, so this also proves the
                //    `DeckListMapper` output is accepted by the real server.
                bridge.tables
                    .joinTable(created.tableId, AI_SEAT_NAME, deck, playerType = SeatPlayerType.ComputerMad)
                    .expect("the server should seat a ComputerMad player with the mapped deck")
                assertAiSeated(states)

                bridge.tables
                    .joinTable(created.tableId, username, deck, playerType = SeatPlayerType.Human)
                    .expect("the server should seat the host with the mapped deck")
                val ready = assertReady(states, username)

                // 4. Start. Upstream accepts this only in READY_TO_START, so `isReadyToStart` above is
                //    not a client opinion — step 4 succeeding is the server agreeing with it.
                assertTrue("the host's start gate should be open before starting", ready.isReadyToStart)
                bridge.tables.startMatch(created.tableId).expect("the server should start a fully seated match")

                // 5. The pushed game-start signal, folded into the observed state. This is the Epic-11
                //    boundary: the game id is asserted to be real, nothing is played.
                val starting =
                    awaitValue(
                        what = "the observed table to receive MatchStarting",
                        timeoutMs = MATCH_START_TIMEOUT_MS,
                        read = { states.lastOrNull() },
                        condition = { it?.matchStarting != null },
                    )!!
                val signal = starting.matchStarting!!
                assertEquals("MatchStarting should identify the table we started", created.tableId, signal.tableId)
                assertTrue(
                    "MatchStarting should carry the new game's id, got '${signal.gameId}'",
                    signal.gameId.isNotBlank() && runCatching { UUID.fromString(signal.gameId) }.isSuccess,
                )
                assertNotNull("MatchStarting should identify our own seat", signal.playerId)
                assertTrue("a table that is starting is past seating", starting.isPastSeating)
            } finally {
                observer?.let { runCatching { it.cancelAndJoin() } }
                // Teardown *before* the socket closes: removeTable rides the same session.
                tableId?.let { runCatching { bridge.tables.removeTable(it) } }
                bridge.close()
            }
        }
    }

    // --- assertions ----------------------------------------------------------------------------------

    private fun assertCreated(
        created: TableRef,
        tableName: String,
    ) {
        assertTrue(
            "the created table should carry a real server id, got '${created.tableId}'",
            runCatching { UUID.fromString(created.tableId) }.isSuccess,
        )
        assertEquals("the created table should keep the name we asked for", tableName, created.name)
        assertEquals("the created table should carry the requested game type", DUEL, created.gameType)
        assertEquals("the created table should carry the requested deck type", FREEFORM, created.deckType)
        assertEquals("two seat player types should yield a two-seat table", 2, created.seatsTotal)
        assertEquals("a freshly created table has nobody seated yet", 0, created.seatsFilled)
    }

    /** The lobby client's mapped view of the very table we just hosted — LobbyMapper against real data. */
    private suspend fun assertLobbyListing(
        bridge: LiveBridge,
        tableId: String,
        tableName: String,
        username: String,
    ) {
        val listed =
            awaitValue(
                what = "the lobby table list to include the table we just created",
                read = { bridge.lobby.tables().firstOrNull { it.id == tableId } },
                condition = { it != null },
            )!!
        assertEquals("the listed table should keep our name", tableName, listed.name)
        assertEquals("we host the table we created", username, listed.host)
        assertEquals("the listed table should carry the requested game type", DUEL, listed.gameType)
        assertEquals("the listed table should carry the requested deck type", FREEFORM, listed.deckType)
        assertEquals("the listed table should report both seats", 2, listed.seatsTotal)
        assertEquals("a table with an open seat is Waiting", LobbyTableState.Waiting, listed.state)
        assertEquals("we asked for a Casual table", SkillLevel.Casual, listed.skillLevel)
        assertFalse("a two-player match is not a tournament", listed.isTournament)
        assertFalse("we asked for an unrated table", listed.isRated)
        assertFalse("we asked for an open (passwordless) table", listed.isPassworded)
        assertFalse("a constructed table is not limited", listed.isLimited)
        assertTrue("a real table carries a create time, got ${listed.createdAtEpochMs}", listed.createdAtEpochMs > 0L)
    }

    /**
     * After the AI join: the observed state shows **two** seats, one filled by the AI with its mapped
     * player type and one still open for a human, and the server is not ready yet.
     *
     * Seats are the assertion story 0040 exists for: XMage pushes no per-seat event before match-start,
     * so they arrive only through the `GetTable` read `observeTable` issues. If that read ever stops
     * carrying seats, this fails — which is precisely what no fake-backed test could notice.
     */
    private suspend fun assertAiSeated(states: List<TableState>) {
        val seated =
            awaitValue(
                what = "the observed table to show the AI seated",
                read = { states.lastOrNull() },
                condition = { it != null && it.seats.size == 2 && it.seats.count { seat -> seat.isOccupied } == 1 },
            )!!
        assertEquals("seats should be reported in server slot order", listOf(0, 1), seated.seats.map { it.index })
        val ai = seated.seats.single { it.isOccupied }
        assertEquals("the filled seat should name the AI we seated", AI_SEAT_NAME, ai.name)
        assertEquals("the AI seat's upstream player type should map back", SeatPlayerType.ComputerMad, ai.playerType)
        val open = seated.seats.single { !it.isOccupied }
        assertNull("an empty seat has no player name", open.name)
        assertEquals("the still-open seat is the human one the host will take", SeatPlayerType.Human, open.playerType)
        assertEquals("one seat still open, so the server is Waiting", TableServerState.Waiting, seated.serverState)
        assertFalse("a half-filled table is not ready to start", seated.isReadyToStart)
        assertFalse("a table that is still seating is not past seating", seated.isPastSeating)
        assertEquals("the seed's options summary should survive the fold", DUEL, seated.optionsSummary)
    }

    /** After our own join: every seat filled and the **server itself** reporting READY_TO_START. */
    private suspend fun assertReady(
        states: List<TableState>,
        username: String,
    ): TableState {
        val ready =
            awaitValue(
                what = "the observed table to become ready to start",
                read = { states.lastOrNull() },
                condition = { it != null && it.isReadyToStart },
            )!!
        assertEquals("readiness comes from the server's own state", TableServerState.ReadyToStart, ready.serverState)
        assertEquals("a ready two-seat table still has two seats", 2, ready.seats.size)
        assertTrue("every seat should be occupied once ready, got ${ready.seats}", ready.seats.all { it.isOccupied })
        assertTrue("our own seat should carry our name, got ${ready.seats}", ready.seats.any { it.name == username })
        assertTrue("the AI's seat should still be there, got ${ready.seats}", ready.seats.any { it.name == AI_SEAT_NAME })
        return ready
    }

    /*
     * Why there is no live resume/reconnect test here (story 0045 explicitly allows skipping it if it
     * cannot be made deterministic).
     *
     * A resume needs the *socket* to drop while the app still wants the session. KtorBridgeClient
     * exposes no seam for that: `disconnect()` sets `disconnectRequested`, which makes the outcome
     * terminal — precisely *not* a reconnect — and every other path to a drop is owned by the transport.
     * The only ways to force one from outside are to restart the bridge container (which also discards
     * the parked session the resume is supposed to find, so it would exercise the `ResumeRejected`
     * fallback at best) or to interpose a proxy we can sever. Both are timing-dependent against a
     * long-lived server, so a test built on either would be flaky — and a flaky live test is worse than
     * none, because it teaches people to re-run instead of read.
     *
     * The resume path stays covered hermetically by SessionResumeIT/SessionRelayTest on the bridge side
     * and ReconnectingSessionTest/SessionRelayTest on the app side. A deterministic live version needs a
     * severable transport seam, which would be a production change and is therefore out of scope here.
     */

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
            author = "app-bridge-it",
            main = listOf(DeckEntry(cardName = "Forest", setCode = "M21", collectorNumber = "272", quantity = 60)),
        )

    /**
     * Unwraps a [Result] from a [TableClient] verb, failing with the server's own reason. The client
     * never throws — a decline is a typed failure — so an unchecked `getOrThrow` would hide *why*.
     */
    private fun <T> Result<T>.expect(what: String): T =
        getOrElse { failure ->
            throw AssertionError(
                "$what; the client returned ${failure::class.simpleName}: ${failure.message}",
                failure,
            )
        }

    private companion object {
        const val DUEL = "Two Player Duel"
        const val FREEFORM = "Constructed - Freeform Unlimited"

        /** The name given to the AI seat's player; upstream does not constrain it. */
        const val AI_SEAT_NAME = "Computer"

        /** How long the server may take to push START_GAME after a start is accepted. */
        const val MATCH_START_TIMEOUT_MS = 120_000L
    }
}
