package magefree.network.reconnect

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import magefree.model.Credentials
import magefree.model.ServerTarget
import magefree.model.SessionEvent
import magefree.protocol.ServerMessage
import magefree.protocol.SessionStateCode
import magefree.protocol.SessionStatus
import magefree.protocol.SkillLevelCode
import magefree.protocol.TableActionCode
import magefree.protocol.TableActionResult
import magefree.protocol.TableCreated
import magefree.protocol.TableDetail
import magefree.protocol.TableNotFound
import magefree.protocol.TableSeatSummary
import magefree.protocol.TableStateCode
import magefree.protocol.TableSummary
import magefree.protocol.TableUpdated
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hermetic coverage of the two relay seams, driven through [SessionRelay.run] with no socket:
 *
 * - **(a) Correlation** — a 0036 [TableActionResult]/[TableCreated] carrying a `requestId` that matches an
 *   outstanding [PendingRequests] waiter is routed to that waiter (not emitted as a `SessionEvent`).
 * - **(b) Push side-channel** — a spontaneous 0036 [TableUpdated] event (no correlation, not a lifecycle
 *   frame) is forwarded to `featurePush` rather than dropped, and still never reaches the session-event
 *   stream.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TableRelaySeamsTest {
    private val target = ServerTarget(host = "bridge.local", port = 9000)
    private val credentials = Credentials(username = "pete", password = "secret")

    private class Captured {
        val events = mutableListOf<SessionEvent>()
        val pushes = mutableListOf<ServerMessage>()
    }

    private suspend fun relay(
        script: List<ServerMessage>,
        pending: PendingRequests,
    ): Captured {
        val captured = Captured()
        val iterator = script.iterator()
        SessionRelay.run(
            server = target,
            credentials = credentials,
            bridgeVersion = "fake-bridge",
            handle = ResumeHandle(),
            receive = { if (iterator.hasNext()) iterator.next() else null },
            send = { },
            emit = { captured.events += it },
            isDisconnectRequested = { false },
            pending = pending,
            featurePush = { captured.pushes += it },
        )
        return captured
    }

    @Test
    fun aTableActionResultIsCorrelatedToItsWaiterNotEmittedOrPushed() =
        runTest {
            val pending = PendingRequests()
            val waiter = pending.register("req-1")
            val reply = TableActionResult(action = TableActionCode.JOIN, ok = true, requestId = "req-1")

            val captured = relay(listOf(SessionStatus(SessionStateCode.CONNECTED), reply), pending)

            assertTrue("waiter must be completed by the correlated reply", waiter.isCompleted)
            assertSame(reply, waiter.getCompleted())
            // Only the lifecycle Connected reached the session stream; the reply was never pushed.
            assertEquals(1, captured.events.size)
            assertTrue(captured.pushes.isEmpty())
        }

    @Test
    fun aTableCreatedIsCorrelatedToItsWaiter() =
        runTest {
            val pending = PendingRequests()
            val waiter = pending.register("req-2")
            val reply =
                TableCreated(
                    table =
                        TableSummary(
                            tableId = "t-1",
                            name = "n",
                            controllerName = "pete",
                            gameType = "Two Player Duel",
                            deckType = "Constructed",
                            state = TableStateCode.WAITING,
                            seatsFilled = 1,
                            seatsTotal = 2,
                            isTournament = false,
                            isRated = false,
                            isPassworded = false,
                            isLimited = false,
                            skillLevel = SkillLevelCode.CASUAL,
                            createdAtEpochMs = 0L,
                        ),
                    requestId = "req-2",
                )

            relay(listOf(reply), pending)

            assertTrue(waiter.isCompleted)
            assertSame(reply, waiter.getCompleted())
        }

    @Test
    fun aTableDetailAndATableNotFoundAreCorrelatedToTheirWaiters() =
        runTest {
            // the targeted read rides the same correlation seam: without registering these two
            // reply types the app's `refreshTable` would hang until timeout and the room would never
            // learn its seats — so this pins the registration, not just the transport.
            val pending = PendingRequests()
            val detailWaiter = pending.register("req-3")
            val missWaiter = pending.register("req-4")
            val detail =
                TableDetail(
                    table =
                        TableSummary(
                            tableId = "t-1",
                            name = "n",
                            controllerName = "pete",
                            gameType = "Two Player Duel",
                            deckType = "Constructed",
                            state = TableStateCode.READY_TO_START,
                            seatsFilled = 2,
                            seatsTotal = 2,
                            isTournament = false,
                            isRated = false,
                            isPassworded = false,
                            isLimited = false,
                            skillLevel = SkillLevelCode.CASUAL,
                            createdAtEpochMs = 0L,
                        ),
                    seats =
                        listOf(
                            TableSeatSummary(index = 0, playerName = "pete", occupied = true),
                            TableSeatSummary(index = 1, playerName = "Computer", occupied = true),
                        ),
                    requestId = "req-3",
                )
            val miss = TableNotFound(tableId = "t-1", reason = "gone", requestId = "req-4")

            val captured = relay(listOf(SessionStatus(SessionStateCode.CONNECTED), detail, miss), pending)

            assertTrue("the detail reply must reach its waiter", detailWaiter.isCompleted)
            assertSame(detail, detailWaiter.getCompleted())
            assertTrue("the not-found reply must reach its waiter", missWaiter.isCompleted)
            assertSame(miss, missWaiter.getCompleted())
            // Neither is a lifecycle frame nor a push: only the Connected reached the session stream.
            assertEquals(1, captured.events.size)
            assertTrue(captured.pushes.isEmpty())
        }

    @Test
    fun aSpontaneousTableEventIsForwardedToTheFeaturePushNotTheSessionStream() =
        runTest {
            val push = TableUpdated(tableId = "t-1", isOwner = true)

            val captured = relay(listOf(SessionStatus(SessionStateCode.CONNECTED), push), PendingRequests())

            // Routed to the feature side-channel...
            assertEquals(listOf<ServerMessage>(push), captured.pushes)
            // ...and never mapped into the session-event stream (only the lifecycle Connected there).
            assertEquals(1, captured.events.size)
            assertTrue(captured.events.single() is SessionEvent.Connected)
        }
}
