package magefree.network.reconnect

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import magefree.model.Credentials
import magefree.model.ServerTarget
import magefree.model.SessionEvent
import magefree.network.game.GameEventFold
import magefree.network.game.GameState
import magefree.protocol.AskPrompt
import magefree.protocol.GameActionCode
import magefree.protocol.GameActionResult
import magefree.protocol.GameInformed
import magefree.protocol.GameOver
import magefree.protocol.GamePrompted
import magefree.protocol.GameStarted
import magefree.protocol.GameStateSnapshot
import magefree.protocol.GameStateUnavailable
import magefree.protocol.GameStateUnavailableCode
import magefree.protocol.GameStateUpdated
import magefree.protocol.GameStateView
import magefree.protocol.ServerMessage
import magefree.protocol.SessionStateCode
import magefree.protocol.SessionStatus
import magefree.protocol.WatchingGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hermetic coverage of the two relay seams — the game-side twins of `TableRelaySeamsTest`,
 * driven through [SessionRelay.run] with no socket:
 *
 * - **(a) Correlation** — a [GameActionResult] carrying a `requestId` that matches an outstanding
 *   [PendingRequests] waiter is routed to that waiter (not emitted as a `SessionEvent`, not pushed).
 *   Without the registration this pins, every `GameClient` verb — join included — would wait out its
 *   timeout on a reply that had already arrived, which looks exactly like a bridge that never answers.
 * - **(b) Push side-channel** — every spontaneous game game *event* (no correlation, not a lifecycle
 *   frame) is forwarded to `featurePush` so `observeGame` can fold it, rather than dropped.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GameRelaySeamsTest {
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
    fun aGameActionResultIsCorrelatedToItsWaiterNotEmittedOrPushed() =
        runTest {
            val pending = PendingRequests()
            val waiter = pending.register("req-1")
            val reply = GameActionResult(action = GameActionCode.JOIN_GAME, ok = true, requestId = "req-1")

            val captured = relay(listOf(SessionStatus(SessionStateCode.CONNECTED), reply), pending)

            assertTrue("waiter must be completed by the correlated reply", waiter.isCompleted)
            assertSame(reply, waiter.getCompleted())
            // Only the lifecycle Connected reached the session stream; the reply was never pushed.
            assertEquals(1, captured.events.size)
            assertTrue(captured.pushes.isEmpty())
        }

    @Test
    fun aGameActionResultWithNoWaiterIsNeverASessionEventAndChangesNoGameState() =
        runTest {
            // A late or duplicate reply has no waiter left, so the relay offers it to the feature channel
            // exactly as it does an uncorrelated table result. What matters is that it stays inert: it is
            // not a lifecycle frame, and the game fold declines it, so no state change is manufactured
            // from a reply the client already gave up on.
            val reply = GameActionResult(action = GameActionCode.SEND_BOOLEAN, ok = true, requestId = "nobody")

            val captured = relay(listOf(SessionStatus(SessionStateCode.CONNECTED), reply), PendingRequests())

            assertEquals("it must not become a session lifecycle event", 1, captured.events.size)
            assertTrue(captured.events.single() is SessionEvent.Connected)
            assertNull(
                "the game fold must decline a correlated-reply type",
                GameEventFold.fold(GameState(gameId = "g-1"), reply),
            )
        }

    @Test
    fun bothArmsOfTheGameStateReadAreCorrelatedToTheirWaiters() =
        runTest {
            // The *miss* matters as much as the hit: an uncorrelated `GameStateUnavailable`
            // would leave the reconnecting board blocked on its waiter until the request timed out —
            // indistinguishable from a bridge that never answered, which is the exact failure the read
            // exists to remove.
            val pending = PendingRequests()
            val hitWaiter = pending.register("read-hit")
            val missWaiter = pending.register("read-miss")
            val hit = GameStateSnapshot(gameId = "g-1", state = GameStateView(turn = 4), capturedAtEpochMs = 1L, requestId = "read-hit")
            val miss =
                GameStateUnavailable(
                    gameId = "g-1",
                    reason = GameStateUnavailableCode.NO_STATE_YET,
                    requestId = "read-miss",
                )

            val captured = relay(listOf(SessionStatus(SessionStateCode.CONNECTED), hit, miss), pending)

            assertTrue("the snapshot must complete its waiter", hitWaiter.isCompleted)
            assertSame(hit, hitWaiter.getCompleted())
            assertTrue("the typed no-state must complete its waiter too", missWaiter.isCompleted)
            assertSame(miss, missWaiter.getCompleted())
            assertEquals(1, captured.events.size)
            assertTrue("a correlated read reply is never a push", captured.pushes.isEmpty())
        }

    @Test
    fun aGameStateSnapshotWithNoWaiterChangesNoGameState() =
        runTest {
            // A read reply that arrives after its caller gave up must stay inert: it is not a lifecycle
            // frame, and the *fold* must decline it — otherwise a late read would race the push stream
            // and could put an older board on screen.
            val late = GameStateSnapshot(gameId = "g-1", state = GameStateView(turn = 2), requestId = "nobody")

            val captured = relay(listOf(SessionStatus(SessionStateCode.CONNECTED), late), PendingRequests())

            assertEquals("it must not become a session lifecycle event", 1, captured.events.size)
            assertNull(
                "the game fold must decline a correlated-reply type",
                GameEventFold.fold(GameState(gameId = "g-1"), late),
            )
        }

    @Test
    fun everySpontaneousGameEventIsForwardedToTheFeaturePushNotTheSessionStream() =
        runTest {
            val view = GameStateView(turn = 1)
            val pushes =
                listOf<ServerMessage>(
                    GameStarted(gameId = "g-1", state = view),
                    GameStateUpdated(gameId = "g-1", state = view),
                    GameInformed(gameId = "g-1", message = "Turn 1", state = view),
                    GamePrompted(gameId = "g-1", state = view, prompt = AskPrompt("Mulligan?")),
                    GameOver(gameId = "g-1", message = "pete has won the game", state = view),
                    WatchingGame(gameId = "g-1", tableId = "t-1"),
                )

            val captured = relay(listOf(SessionStatus(SessionStateCode.CONNECTED)) + pushes, PendingRequests())

            assertEquals("every game push must reach the fold", pushes, captured.pushes)
            assertEquals(1, captured.events.size)
            assertTrue(captured.events.single() is SessionEvent.Connected)
        }
}
