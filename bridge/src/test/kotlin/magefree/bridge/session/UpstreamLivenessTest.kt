package magefree.bridge.session

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.receiveDeserialized
import io.ktor.client.plugins.websocket.sendSerialized
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.serialization.kotlinx.KotlinxWebsocketSerializationConverter
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import magefree.bridge.testApplicationTimed
import magefree.bridge.ws.sessionWebSocket
import magefree.protocol.ClientHello
import magefree.protocol.ClientMessage
import magefree.protocol.Login
import magefree.protocol.ProtocolJson
import magefree.protocol.ProtocolVersion
import magefree.protocol.Resume
import magefree.protocol.ResumeRejected
import magefree.protocol.ServerHello
import magefree.protocol.ServerMessage
import magefree.protocol.SessionResumable
import magefree.protocol.SessionStateCode
import magefree.protocol.SessionStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.server.websocket.WebSockets as ServerWebSockets

/**
 * **a dead upstream must reach the app**, and a live one must be kept that way.
 *
 * The failure the on-device smoke found is one-sided and time-based, which is why every existing suite
 * missed it: the app socket stays perfectly healthy while the *XMage* session underneath it dies, so
 * nothing in the app or in 0023's park/resume machinery has any reason to change state. The bridge is
 * the only party positioned to notice — and without it it only probed a session while it was
 * **parked**, i.e. exactly when the app was not there to be told.
 *
 * These tests drive the real WebSocket + [SessionCoordinator] + [SessionRegistry] plumbing over a
 * [FakeUpstreamSession] whose `upstreamAlive` flag stands in for XMage dropping the user underneath it.
 * They assert both directions: a dead upstream is relayed as a terminal `DISCONNECTED` and evicted, and
 * a healthy bound session is pinged (so XMage's idle expiry never fires) and left alone.
 */
class UpstreamLivenessTest {
    private fun livenessScenario(
        fake: FakeUpstreamSession,
        config: ResumeConfig = ResumeConfig(ttl = 60.seconds, keepaliveInterval = 100.milliseconds),
        block: suspend (client: HttpClient, registry: SessionRegistry) -> Unit,
    ) = testApplicationTimed {
        val registry = SessionRegistry(config)
        try {
            application {
                install(ServerWebSockets) {
                    contentConverter = KotlinxWebsocketSerializationConverter(ProtocolJson.json)
                }
                routing {
                    sessionWebSocket(registry = registry, newUpstream = { fake })
                }
            }
            val wsClient =
                createClient {
                    install(ClientWebSockets) {
                        contentConverter = KotlinxWebsocketSerializationConverter(ProtocolJson.json)
                    }
                }
            block(wsClient, registry)
        } finally {
            registry.shutdown()
        }
    }

    private suspend fun HttpClient.session(block: suspend DefaultClientWebSocketSession.() -> Unit) =
        webSocket("/${ProtocolVersion.PATH_SEGMENT}/session") { block() }

    private suspend fun DefaultClientWebSocketSession.handshake() {
        sendSerialized<ClientMessage>(
            ClientHello(protocolMajor = ProtocolVersion.MAJOR, protocolMinor = ProtocolVersion.MINOR),
        )
        assertInstanceOf(ServerHello::class.java, receiveDeserialized<ServerMessage>())
    }

    private suspend fun DefaultClientWebSocketSession.nextStatus(): SessionStatus =
        assertInstanceOf(SessionStatus::class.java, receiveDeserialized<ServerMessage>())

    /** Handshake + Login, read CONNECTING/CONNECTED + the resume handle. */
    private suspend fun DefaultClientWebSocketSession.loginAndCaptureResumeId(username: String): String {
        handshake()
        sendSerialized<ClientMessage>(Login(username = username, requestId = "login-1"))
        assertEquals(SessionStateCode.CONNECTING, nextStatus().state)
        assertEquals(SessionStateCode.CONNECTED, nextStatus().state)
        return assertInstanceOf(SessionResumable::class.java, receiveDeserialized<ServerMessage>()).resumeId
    }

    private fun connectedScript() =
        listOf(
            SessionStatus(SessionStateCode.CONNECTING),
            SessionStatus(SessionStateCode.CONNECTED),
        )

    @Test
    fun `an upstream that dies under a live app socket is relayed as a terminal DISCONNECTED`() {
        val fake = FakeUpstreamSession(connectedScript())
        livenessScenario(fake) { client, registry ->
            var resumeId = ""
            client.session {
                resumeId = loginAndCaptureResumeId("idler")

                // XMage drops the user underneath the bridge (its ~3-minute idle expiry, a restart, a
                // partition). The app socket is untouched and the app is still displaying `Connected`;
                // nothing app-side has any reason to change, so the bridge has to say so.
                fake.upstreamAlive = false

                // On a real clock: the keepalive that detects this runs on the registry's own
                // (non-test) scope, so a virtual-time timeout would expire before it ever ticks.
                val lost =
                    withContext(Dispatchers.Default) {
                        withTimeout(FRAME_TIMEOUT_MS) { nextStatus() }
                    }
                assertEquals(
                    SessionStateCode.DISCONNECTED,
                    lost.state,
                    "a dead upstream must reach the app as a terminal DISCONNECTED, not as silence",
                )
                assertNotNull(lost.message, "the status must carry a reason the user can act on")
                // Spelled out rather than read from the production constant: the text is what the user
                // is told, and a test that quotes the implementation cannot notice it changing.
                assertEquals("your session ended on the server; sign in again", lost.message)
            }

            // …and the session is gone, not parked: there is nothing left to resume.
            withContext(Dispatchers.Default) { withTimeout(FRAME_TIMEOUT_MS) { fake.awaitDisconnect() } }
            assertFalse(
                registry.isRegistered(resumeId),
                "a dead upstream must be evicted, not held for the resume window",
            )
            client.session {
                handshake()
                sendSerialized<ClientMessage>(Resume(resumeId = resumeId, requestId = "resume-1"))
                assertInstanceOf(ResumeRejected::class.java, receiveDeserialized<ServerMessage>())
            }
        }
    }

    @Test
    fun `a bound session is pinged so an idle user is never expired upstream`() {
        val fake = FakeUpstreamSession(connectedScript())
        livenessScenario(fake) { client, registry ->
            client.session {
                val resumeId = loginAndCaptureResumeId("deckbuilder")

                // The socket never drops and the user never acts — the deck-building case. Before this
                // the keepalive would only run while *parked*, so this session was probed zero times and
                // XMage's idle expiry was free to kill it behind the app's back.
                withContext(Dispatchers.Default) {
                    withTimeout(FRAME_TIMEOUT_MS) {
                        while (fake.pingCount.get() < MIN_BOUND_PINGS) delay(POLL_MS)
                    }
                }

                assertTrue(
                    fake.pingCount.get() >= MIN_BOUND_PINGS,
                    "a healthy bound session must be kept alive upstream, not left silent until XMage " +
                        "expires it; pings=${fake.pingCount.get()}",
                )
                // …and being pinged must not disturb it: still connected, still resumable, no teardown.
                assertFalse(fake.disconnectCalled, "a healthy keepalive must never disconnect the upstream")
                assertTrue(registry.isRegistered(resumeId), "the session stays registered while it is healthy")
            }
        }
    }

    private companion object {
        const val FRAME_TIMEOUT_MS = 10_000L
        const val POLL_MS = 20L

        /** Enough probes to prove a *loop*, not a single opportunistic ping. */
        const val MIN_BOUND_PINGS = 3
    }
}
