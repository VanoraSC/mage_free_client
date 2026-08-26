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
import magefree.protocol.TableList
import magefree.protocol.TableStateCode
import magefree.protocol.TableSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hermetic coverage of the  request/response multiplexing added to the frame relay: a
 * correlated reply is routed to its waiting requester (via [PendingRequests]) and is *not* mapped to a
 * `SessionEvent`, so the session-event stream is undisturbed; and an ended session
 * fails every outstanding waiter so a blocked requester surfaces the failure rather than hanging.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RequestResponseMultiplexingTest {
    private val target = ServerTarget(host = "bridge.local", port = 9000)
    private val credentials = Credentials(username = "pete", password = "secret")

    private fun tableReply(requestId: String?) =
        TableList(
            tables =
                listOf(
                    TableSummary(
                        tableId = "t-1",
                        name = "n",
                        controllerName = "pete",
                        gameType = "Two Player Duel",
                        deckType = "Constructed",
                        state = TableStateCode.WAITING,
                        seatsFilled = 0,
                        seatsTotal = 2,
                        isTournament = false,
                        isRated = false,
                        isPassworded = false,
                        isLimited = false,
                        skillLevel = SkillLevelCode.CASUAL,
                        createdAtEpochMs = 0L,
                    ),
                ),
            requestId = requestId,
        )

    private suspend fun relay(
        script: List<ServerMessage>,
        pending: PendingRequests,
    ): List<SessionEvent> {
        val events = mutableListOf<SessionEvent>()
        val iterator = script.iterator()
        SessionRelay.run(
            server = target,
            credentials = credentials,
            bridgeVersion = "fake-bridge",
            handle = ResumeHandle(),
            receive = { if (iterator.hasNext()) iterator.next() else null },
            send = { },
            emit = { events += it },
            isDisconnectRequested = { false },
            pending = pending,
        )
        return events
    }

    @Test
    fun aCorrelatedReplyIsRoutedToTheRequesterAndNotEmittedAsASessionEvent() =
        runTest {
            val pending = PendingRequests()
            val waiter = pending.register("req-1")
            val reply = tableReply(requestId = "req-1")

            val events =
                relay(
                    script = listOf(SessionStatus(SessionStateCode.CONNECTED), reply),
                    pending = pending,
                )

            // Routed to the waiting requester...
            assertTrue("waiter must be completed by the correlated reply", waiter.isCompleted)
            assertSame(reply, waiter.getCompleted())
            // ...and the session-event stream saw only the lifecycle Connected — never the reply.
            assertEquals(1, events.size)
            assertTrue(events.single() is SessionEvent.Connected)
        }

    @Test
    fun aReplyWithNoWaiterIsIgnoredAndDoesNotDisturbTheSession() =
        runTest {
            // Empty registry: an uncorrelated/late lobby reply is dropped, no event, no crash.
            val events =
                relay(
                    script = listOf(SessionStatus(SessionStateCode.CONNECTED), tableReply(requestId = "orphan")),
                    pending = PendingRequests(),
                )

            assertEquals(1, events.size)
            assertTrue(events.single() is SessionEvent.Connected)
        }

    @Test
    fun failAllFailsEveryOutstandingWaiter() =
        runTest {
            val pending = PendingRequests()
            val a = pending.register("a")
            val b = pending.register("b")

            pending.failAll(IllegalStateException("session ended"))

            assertTrue(a.isCancelled || a.isCompleted)
            assertNull(runCatching { a.getCompleted() }.getOrNull())
            assertTrue(b.isCompleted)
            // A subsequent reply for a failed id is not routed (the waiter is gone).
            assertFalse(pending.tryComplete(tableReply(requestId = "a")))
        }
}
