package magefree.network.table

import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import magefree.model.ConnectionState
import magefree.network.fake.FakeBridgeClient
import magefree.protocol.ClientMessage
import magefree.protocol.ConstructPrompt
import magefree.protocol.GetTable
import magefree.protocol.SeatPlayerTypeCode
import magefree.protocol.SeatUpdated
import magefree.protocol.ServerMessage
import magefree.protocol.SkillLevelCode
import magefree.protocol.TableDetail
import magefree.protocol.TableNotFound
import magefree.protocol.TableSeatSummary
import magefree.protocol.TableStateCode
import magefree.protocol.TableSummary
import magefree.protocol.TableUpdated
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import magefree.protocol.MatchStarting as MatchStartingMessage

/**
 * Hermetic Turbine coverage of [DefaultTableClient.observeTable]: it seeds current state, folds the 0036
 * events pushed through the [FakeBridgeClient]'s server-push side-channel into successive [TableState]s
 * (join → seat update → construct → match-starting), and re-emits the held state on a 0023 resume (a
 * return to [ConnectionState.Connected]) so a reconnect does not strand the seat. No socket.
 *
 * Story 0040 adds the other half: the **seat read**. `observeTable` issues a `GetTable` on open, on each
 * table-lifecycle push for this table, and after a resume — and on nothing else, so an observed room
 * never polls in the background. Those tests script `TableDetail` replies through the same fake's
 * request/response seam.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ObserveTableTest {
    private val seed = TableState(tableId = "t-1", optionsSummary = "Two Player Duel")

    /** A scripted `GetTable` responder that records every request it answers. */
    private class Reads(
        private val replies: List<ServerMessage>,
    ) {
        val requests: MutableList<GetTable> = mutableListOf()

        fun responder(message: ClientMessage): ServerMessage {
            val request = message as GetTable
            requests += request
            // Replay the last scripted reply once the script is exhausted (a re-read of a settled table).
            return replies[minOf(requests.size - 1, replies.size - 1)]
        }
    }

    private fun summary(
        state: TableStateCode,
        filled: Int,
    ) = TableSummary(
        tableId = "t-1",
        name = "Duel Night",
        controllerName = "pete",
        gameType = "Two Player Duel",
        deckType = "Constructed",
        state = state,
        seatsFilled = filled,
        seatsTotal = 2,
        isTournament = false,
        isRated = false,
        isPassworded = false,
        isLimited = false,
        skillLevel = SkillLevelCode.CASUAL,
        createdAtEpochMs = 0L,
    )

    /** A two-seat table with the host seated and the AI seat still open. */
    private fun waitingDetail() =
        TableDetail(
            table = summary(TableStateCode.WAITING, filled = 1),
            seats =
                listOf(
                    TableSeatSummary(index = 0, playerName = "pete", playerType = SeatPlayerTypeCode.HUMAN, occupied = true),
                    TableSeatSummary(index = 1, playerName = null, playerType = SeatPlayerTypeCode.COMPUTER_MAD, occupied = false),
                ),
        )

    /** The same table once both seats are filled — the server's own READY_TO_START. */
    private fun readyDetail() =
        TableDetail(
            table = summary(TableStateCode.READY_TO_START, filled = 2),
            seats =
                listOf(
                    TableSeatSummary(index = 0, playerName = "pete", playerType = SeatPlayerTypeCode.HUMAN, occupied = true),
                    TableSeatSummary(index = 1, playerName = "Computer", playerType = SeatPlayerTypeCode.COMPUTER_MAD, occupied = true),
                ),
        )

    /**
     * A table whose match is already under way (story 0069) — the shape a late-opening client's very
     * first `GetTable` read sees. Carries [activeGameId] the way `TableView.getGames()` does; carries no
     * `MatchStarting`, because that push already fired for whichever client caught the live transition.
     */
    private fun duelingDetail(activeGameId: String) =
        TableDetail(
            table = summary(TableStateCode.DUELING, filled = 2),
            seats =
                listOf(
                    TableSeatSummary(index = 0, playerName = "pete", playerType = SeatPlayerTypeCode.HUMAN, occupied = true),
                    TableSeatSummary(index = 1, playerName = "Computer", playerType = SeatPlayerTypeCode.COMPUTER_MAD, occupied = true),
                ),
            activeGameId = activeGameId,
        )

    private fun clientOver(
        reads: Reads,
        connection: MutableStateFlow<ConnectionState> = MutableStateFlow(ConnectionState.Connected),
    ): Pair<DefaultTableClient, FakeBridgeClient> {
        val fake = FakeBridgeClient(responder = reads::responder)
        return DefaultTableClient(
            bridgeClient = fake,
            pushSource = fake,
            connectionState = connection,
        ) to fake
    }

    @Test
    fun observeTableReadsTheTableOnOpenSoSeatsAppearWithNoPushAtAll() =
        runTest {
            // The regression this story exists for: with *no* server push of any kind, the room must
            // still learn its seats — because XMage never pushes them.
            val reads = Reads(listOf(waitingDetail()))
            val (client, _) = clientOver(reads)

            client.observeTable("t-1", seed).test {
                assertEquals(seed, awaitItem())

                val read = awaitItem()
                assertEquals(listOf("pete", null), read.seats.map { it.name })
                assertEquals(listOf(0, 1), read.seats.map { it.index })
                assertEquals(listOf(true, false), read.seats.map { it.isOccupied })
                assertEquals(SeatPlayerType.ComputerMad, read.seats[1].playerType)
                assertEquals(TableServerState.Waiting, read.serverState)
                assertFalse("a half-filled table is not ready to start", read.isReadyToStart)

                cancelAndIgnoreRemainingEvents()
            }

            assertEquals(listOf("t-1"), reads.requests.map { it.tableId })
        }

    @Test
    fun aClientThatOpensAnAlreadyStartedTableLearnsTheGameIdFromTheOpenTimeReadAlone() =
        runTest {
            // Story 0069's defect, reproduced: this client never receives MatchStarting at all — it
            // opens the room *after* the game already started (back from the lobby, or a relaunch), so
            // the one-shot push already fired for someone else. A test built only on the MatchStarting
            // path would pass against the unfixed code; this one does not touch that path at all.
            val reads = Reads(listOf(duelingDetail(activeGameId = "g-9")))
            val (client, _) = clientOver(reads)

            client.observeTable("t-1", seed).test {
                assertEquals(seed, awaitItem())
                assertEquals("resumableGameId must come from activeGameId with no push at all", "g-9", awaitItem().resumableGameId)

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun aLateActiveGameIdReadDoesNotOverwriteAnAlreadyKnownMatchStarting() =
        runTest {
            // The live push, when it does arrive, is authoritative; a later read reporting null (its own
            // scripted reply has not caught up yet) must not blank a resumableGameId already known.
            val reads = Reads(listOf(waitingDetail()))
            val (client, fake) = clientOver(reads)

            client.observeTable("t-1", seed).test {
                assertEquals(seed, awaitItem())
                assertEquals(null, awaitItem().resumableGameId)

                fake.emitPush(MatchStartingMessage(gameId = "g-9", tableId = "t-1", playerId = "p-1"))
                assertEquals("g-9", awaitItem().resumableGameId)

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun aTableLifecyclePushReReadsTheTableSoASeatChangeBecomesVisible() =
        runTest {
            // No per-seat push exists upstream, so a seat taken by someone else surfaces on the next
            // lifecycle push: the room re-reads the table and the server's own READY_TO_START lands.
            val reads = Reads(listOf(waitingDetail(), readyDetail()))
            val (client, fake) = clientOver(reads)

            client.observeTable("t-1", seed).test {
                assertEquals(seed, awaitItem())
                assertFalse(awaitItem().isReadyToStart)

                fake.emitPush(TableUpdated(tableId = "t-1", isOwner = true))
                assertTrue("the push itself folds first", awaitItem().isOwner)

                val reread = awaitItem()
                assertEquals(listOf("pete", "Computer"), reread.seats.map { it.name })
                assertTrue("the server now reports every seat filled", reread.isReadyToStart)

                cancelAndIgnoreRemainingEvents()
            }

            assertEquals(2, reads.requests.size)
        }

    @Test
    fun anUnobservedTableIssuesNoReadsAtAll() =
        runTest {
            // The poll lives inside the flow, so it only exists while someone collects: building the
            // client, and letting any amount of time pass, must touch the bridge zero times.
            val reads = Reads(listOf(waitingDetail()))
            clientOver(reads)

            testScheduler.advanceTimeBy(10 * 60_000L)
            testScheduler.runCurrent()

            assertEquals("an unobserved table must never be read", 0, reads.requests.size)
        }

    @Test
    fun anObservedTableIsReReadPeriodicallySoAHumanJoinWithNoPushStillSurfaces() =
        runTest {
            // Upstream tells a seated player *nothing* when another human takes a seat. With no push of
            // any kind, only the while-observed poll can reveal it — so this drives the whole change
            // through the clock, changing nothing but the bridge's reply between reads.
            val reads = Reads(listOf(waitingDetail(), readyDetail()))
            val (client, _) = clientOver(reads)

            client.observeTable("t-1", seed).test {
                assertEquals(seed, awaitItem())
                assertFalse(awaitItem().isReadyToStart)
                assertEquals("only the room-open read so far", 1, reads.requests.size)

                // Not yet due: the interval has not elapsed, so nothing is re-read.
                testScheduler.advanceTimeBy(DefaultTableClient.SEAT_REFRESH_INTERVAL_MS / 2)
                testScheduler.runCurrent()
                assertEquals(1, reads.requests.size)
                expectNoEvents()

                // One interval later the table is re-read and the opponent's seat appears.
                testScheduler.advanceTimeBy(DefaultTableClient.SEAT_REFRESH_INTERVAL_MS)
                testScheduler.runCurrent()
                assertEquals(2, reads.requests.size)

                val polled = awaitItem()
                assertEquals(listOf("pete", "Computer"), polled.seats.map { it.name })
                assertTrue("the poll must surface the server's own readiness", polled.isReadyToStart)

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun thePollStopsOnceTheTableHasLeftSeating() =
        runTest {
            val reads = Reads(listOf(readyDetail()))
            val (client, fake) = clientOver(reads)

            client.observeTable("t-1", seed).test {
                assertEquals(seed, awaitItem())
                assertTrue(awaitItem().isReadyToStart)

                // The match starts: a table that has become a game cannot gain a player, so re-reading
                // it is pointless — the poll must stop for good, not merely slow down.
                fake.emitPush(MatchStartingMessage(gameId = "g-9", tableId = "t-1", playerId = "p-1"))
                val starting = awaitItem()
                assertEquals(TablePhase.Starting, starting.phase)
                assertTrue(starting.isPastSeating)

                // The match-start push itself triggers one last read; nothing after it.
                testScheduler.runCurrent()
                val readsAtStart = reads.requests.size

                testScheduler.advanceTimeBy(10 * DefaultTableClient.SEAT_REFRESH_INTERVAL_MS)
                testScheduler.runCurrent()

                assertEquals("no read may follow match-start", readsAtStart, reads.requests.size)

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun thePollStopsWhenTheRoomLeavesTheScreen() =
        runTest {
            // "No background polling when the room is off screen": cancelling the collection (the room
            // closing) must end the polling, not leave a timer running against the bridge.
            val reads = Reads(listOf(waitingDetail()))
            val (client, _) = clientOver(reads)

            client.observeTable("t-1", seed).test {
                assertEquals(seed, awaitItem())
                awaitItem()
                testScheduler.advanceTimeBy(2 * DefaultTableClient.SEAT_REFRESH_INTERVAL_MS)
                testScheduler.runCurrent()
                cancelAndIgnoreRemainingEvents()
            }
            val readsWhileObserved = reads.requests.size
            assertTrue("the poll should have run while observed", readsWhileObserved > 1)

            testScheduler.advanceTimeBy(20 * DefaultTableClient.SEAT_REFRESH_INTERVAL_MS)
            testScheduler.runCurrent()

            assertEquals("no read may follow the room closing", readsWhileObserved, reads.requests.size)
        }

    @Test
    fun aResumeReReadsTheTableSoSeatsThatMovedWhileOfflineAreNotStale() =
        runTest {
            val reads = Reads(listOf(waitingDetail(), readyDetail()))
            val connection = MutableStateFlow(ConnectionState.Connected)
            val (client, _) = clientOver(reads, connection)

            client.observeTable("t-1", seed).test {
                assertEquals(seed, awaitItem())
                assertFalse(awaitItem().isReadyToStart)

                connection.value = ConnectionState.Reconnecting
                testScheduler.runCurrent()
                connection.value = ConnectionState.Connected
                testScheduler.runCurrent()

                // The held state is re-emitted first (the 0037 non-destructive re-sync)...
                assertFalse(awaitItem().isReadyToStart)
                // ...then the fresh read lands, so a seat filled while the socket was down shows up.
                assertTrue(awaitItem().isReadyToStart)

                cancelAndIgnoreRemainingEvents()
            }

            assertEquals(2, reads.requests.size)
        }

    @Test
    fun aTableTheServerNoLongerListsLeavesTheLastKnownSeatsInPlace() =
        runTest {
            // A not-found must not blank the room: the last known seats are better than none, and the
            // failure is surfaced as a failed refreshTable Result, not as a silent empty state.
            val reads = Reads(listOf(readyDetail(), TableNotFound(tableId = "t-1", reason = "gone")))
            val (client, fake) = clientOver(reads)

            client.observeTable("t-1", seed).test {
                assertEquals(seed, awaitItem())
                assertTrue(awaitItem().isReadyToStart)

                fake.emitPush(TableUpdated(tableId = "t-1", isOwner = true))
                val folded = awaitItem()
                assertTrue(folded.isOwner)
                // The re-read missed; no further emission, and the seats survive.
                expectNoEvents()
                assertEquals(2, folded.seats.size)

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun observeTableSeedsThenFoldsAScriptedLifecycleIntoStateTransitions() =
        runTest {
            val fake = FakeBridgeClient()
            val client =
                DefaultTableClient(
                    bridgeClient = fake,
                    pushSource = fake,
                    connectionState = MutableStateFlow(ConnectionState.Connected),
                )

            client.observeTable("t-1", seed).test {
                assertEquals(seed, awaitItem())

                fake.emitPush(TableUpdated(tableId = "t-1", isOwner = true))
                assertTrue(awaitItem().isOwner)

                fake.emitPush(SeatUpdated(tableId = "t-1", playerId = "p-2"))
                assertEquals(listOf("p-2"), awaitItem().seats.map { it.playerId })

                fake.emitPush(ConstructPrompt(tableId = "t-1"))
                assertEquals(TablePhase.Constructing, awaitItem().phase)

                fake.emitPush(MatchStartingMessage(gameId = "g-9", tableId = "t-1", playerId = "p-1"))
                val starting = awaitItem()
                assertEquals(TablePhase.Starting, starting.phase)
                assertEquals("g-9", starting.matchStarting?.gameId)

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun anEventForAnotherTableDoesNotEmit() =
        runTest {
            val fake = FakeBridgeClient()
            val client =
                DefaultTableClient(
                    bridgeClient = fake,
                    pushSource = fake,
                    connectionState = MutableStateFlow(ConnectionState.Connected),
                )

            client.observeTable("t-1", seed).test {
                assertEquals(seed, awaitItem())
                fake.emitPush(ConstructPrompt(tableId = "other"))
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun aResumeReEmitsTheCurrentStateSoTheSeatIsNotStranded() =
        runTest {
            val fake = FakeBridgeClient()
            val connection = MutableStateFlow(ConnectionState.Connected)
            val scheduler = testScheduler
            val client =
                DefaultTableClient(
                    bridgeClient = fake,
                    pushSource = fake,
                    connectionState = connection,
                )

            client.observeTable("t-1", seed).test {
                assertEquals(seed, awaitItem())

                // Advance the table into Constructing.
                fake.emitPush(ConstructPrompt(tableId = "t-1"))
                val constructing = awaitItem()
                assertEquals(TablePhase.Constructing, constructing.phase)

                // Simulate a drop and a 0023 resume; step through Reconnecting so the StateFlow does not
                // conflate the intermediate away, then return to Connected.
                connection.value = ConnectionState.Reconnecting
                scheduler.runCurrent()
                connection.value = ConnectionState.Connected
                scheduler.runCurrent()

                // The held state is re-emitted — the seat re-syncs rather than stranding at reconnect.
                assertEquals(constructing, awaitItem())

                cancelAndIgnoreRemainingEvents()
            }
        }
}
