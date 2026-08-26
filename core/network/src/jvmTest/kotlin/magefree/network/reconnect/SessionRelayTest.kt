package magefree.network.reconnect

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import magefree.model.Credentials
import magefree.model.ServerTarget
import magefree.model.SessionEvent
import magefree.protocol.ClientMessage
import magefree.protocol.Login
import magefree.protocol.Resume
import magefree.protocol.ResumeRejected
import magefree.protocol.ServerMessage
import magefree.protocol.SessionResumable
import magefree.protocol.SessionStateCode
import magefree.protocol.SessionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hermetic coverage of the  frame-level relay — the `Resume` vs `Login` decision, the
 * `SessionResumable` handle capture / resume-ack → `Connected`, and the `ResumeRejected` → `Login`
 * fallback — with no live socket: a scripted list of `:protocol` [ServerMessage]s is fed through
 * [SessionRelay.run] while every sent [ClientMessage] is captured for assertion.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionRelayTest {
    private val target = ServerTarget(host = "bridge.local", port = 9000)
    private val credentials = Credentials(username = "pete", password = "secret")

    private data class Result(
        val outcome: SessionOutcome,
        val sent: List<ClientMessage>,
        val events: List<SessionEvent>,
        val handle: ResumeHandle,
    )

    private suspend fun relay(
        script: List<ServerMessage>,
        handle: ResumeHandle = ResumeHandle(),
        disconnectRequested: Boolean = false,
    ): Result {
        val sent = mutableListOf<ClientMessage>()
        val events = mutableListOf<SessionEvent>()
        val iterator = script.iterator()
        val outcome =
            SessionRelay.run(
                server = target,
                credentials = credentials,
                bridgeVersion = "fake-bridge",
                handle = handle,
                receive = { if (iterator.hasNext()) iterator.next() else null },
                send = { sent += it },
                emit = { events += it },
                isDisconnectRequested = { disconnectRequested },
            )
        return Result(outcome, sent, events, handle)
    }

    @Test
    fun firstConnectSendsLoginAndCapturesTheResumeHandle() =
        runTest {
            val result =
                relay(
                    listOf(
                        SessionStatus(SessionStateCode.CONNECTED),
                        SessionResumable(resumeId = "resume-123"),
                    ),
                )

            // No handle held → Login (never Resume), carrying the retained credentials.
            assertEquals(listOf<ClientMessage>(Login("pete", "secret")), result.sent)
            assertTrue(result.events.first() is SessionEvent.Connected)
            // The SessionResumable handle is captured for a future reconnect...
            assertEquals("resume-123", result.handle.resumeId)
            // ...but the connect-time SessionResumable does not itself emit a second Connected.
            assertEquals(1, result.events.count { it is SessionEvent.Connected })
            assertEquals(SessionOutcome.RETRY, result.outcome)
        }

    @Test
    fun reconnectWithAHeldHandleSendsResumeAndRestoresBeforeConnected() =
        runTest {
            val handle = ResumeHandle().apply { resumeId = "resume-123" }
            val result =
                relay(
                    script = listOf(SessionResumable(resumeId = "resume-123", requestId = "resume-123")),
                    handle = handle,
                )

            // A held handle → Resume (no re-auth). While the ack is awaited the relay surfaces
            // Restoring (socket back, re-attaching the parked session), then the ack turns it into
            // Connected (recovery is over) — the distinct restore signal.
            assertEquals(listOf<ClientMessage>(Resume("resume-123")), result.sent)
            assertEquals(2, result.events.size)
            assertEquals(SessionEvent.Restoring, result.events.first())
            assertTrue(result.events.last() is SessionEvent.Connected)
            assertEquals("resume-123", result.handle.resumeId)
        }

    @Test
    fun resumeRejectedClearsTheHandleAndFallsBackToLogin() =
        runTest {
            val handle = ResumeHandle().apply { resumeId = "expired" }
            val result =
                relay(
                    script =
                        listOf(
                            ResumeRejected(reason = "ttl expired"),
                            SessionStatus(SessionStateCode.CONNECTED),
                        ),
                    handle = handle,
                )

            // Resume first, then — on rejection — a fresh Login on the SAME socket.
            assertEquals(listOf<ClientMessage>(Resume("expired"), Login("pete", "secret")), result.sent)
            assertNull("handle must be cleared after ResumeRejected", result.handle.resumeId)
            assertTrue(result.events.any { it is SessionEvent.Connected })
        }

    @Test
    fun authFailedIsTerminal() =
        runTest {
            val result =
                relay(
                    listOf(
                        SessionStatus(SessionStateCode.AUTH_FAILED, message = "bad creds"),
                        SessionStatus(SessionStateCode.CONNECTED), // must never be reached
                    ),
                )
            assertEquals(SessionOutcome.TERMINAL, result.outcome)
            assertEquals(SessionEvent.AuthFailed("bad creds"), result.events.last())
        }

    @Test
    fun versionUnsupportedIsTerminal() =
        runTest {
            val result =
                relay(listOf(SessionStatus(SessionStateCode.VERSION_UNSUPPORTED, message = "server=1.4.61 bridge=1.4.60")))
            assertEquals(SessionOutcome.TERMINAL, result.outcome)
            assertTrue(result.events.last() is SessionEvent.VersionUnsupported)
        }

    @Test
    fun explicitDisconnectIsACleanClose() =
        runTest {
            val result =
                relay(
                    script = listOf(SessionStatus(SessionStateCode.CONNECTED)),
                    disconnectRequested = true,
                )
            assertEquals(SessionOutcome.CLEAN_CLOSE, result.outcome)
        }

    @Test
    fun anUnexpectedDropRequestsRetry() =
        runTest {
            val result = relay(script = listOf(SessionStatus(SessionStateCode.CONNECTED)))
            // Script exhausted (socket closed) without a terminal status and no disconnect requested.
            assertEquals(SessionOutcome.RETRY, result.outcome)
        }
}
