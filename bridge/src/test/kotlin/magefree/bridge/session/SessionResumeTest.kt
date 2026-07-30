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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import magefree.bridge.testApplicationTimed
import magefree.bridge.ws.sessionWebSocket
import magefree.protocol.ClientHello
import magefree.protocol.ClientMessage
import magefree.protocol.GetServerInfo
import magefree.protocol.Login
import magefree.protocol.Logout
import magefree.protocol.ProtocolJson
import magefree.protocol.ProtocolVersion
import magefree.protocol.Resume
import magefree.protocol.ResumeRejected
import magefree.protocol.ServerHello
import magefree.protocol.ServerInfo
import magefree.protocol.ServerMessage
import magefree.protocol.SessionResumable
import magefree.protocol.SessionStateCode
import magefree.protocol.SessionStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.server.websocket.WebSockets as ServerWebSockets

/**
 * Hermetic coverage of story 0023's park / resume / evict flow through the real WebSocket +
 * [SessionCoordinator] + [SessionRegistry] plumbing, driven by a [FakeUpstreamSession]. Every branch
 * is exercised: park→resume (same session, no second upstream connect), TTL eviction→reject,
 * logout→evict→later-reject, and an unknown handle→reject. No XMage server involved.
 */
class SessionResumeTest {
    /**
     * Boots the route with a **shared** [SessionRegistry] and a per-login upstream factory that always
     * returns [fake] (so a wrongful second connect is observable). Hands the test the client and the
     * factory-invocation counter.
     */
    private fun resumeScenario(
        fake: FakeUpstreamSession,
        config: ResumeConfig = ResumeConfig(ttl = 60.seconds, keepaliveInterval = 5.seconds),
        block: suspend (client: HttpClient, factoryCalls: AtomicInteger) -> Unit,
    ) = testApplicationTimed {
        val registry = SessionRegistry(config)
        val factoryCalls = AtomicInteger(0)
        try {
            application {
                install(ServerWebSockets) {
                    contentConverter = KotlinxWebsocketSerializationConverter(ProtocolJson.json)
                }
                routing {
                    sessionWebSocket(
                        registry = registry,
                        newUpstream = {
                            factoryCalls.incrementAndGet()
                            fake
                        },
                    )
                }
            }
            val wsClient =
                createClient {
                    install(ClientWebSockets) {
                        contentConverter = KotlinxWebsocketSerializationConverter(ProtocolJson.json)
                    }
                }
            block(wsClient, factoryCalls)
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

    /** Handshake + Login, read CONNECTING/CONNECTED, and return the delivered resume handle. */
    private suspend fun DefaultClientWebSocketSession.loginAndCaptureResumeId(username: String): String {
        handshake()
        sendSerialized<ClientMessage>(Login(username = username, requestId = "login-1"))
        assertEquals(SessionStateCode.CONNECTING, nextStatus().state)
        assertEquals(SessionStateCode.CONNECTED, nextStatus().state)
        val resumable = assertInstanceOf(SessionResumable::class.java, receiveDeserialized<ServerMessage>())
        assertNotNull(resumable.resumeId)
        return resumable.resumeId
    }

    private fun connectedScript() =
        listOf(
            SessionStatus(SessionStateCode.CONNECTING),
            SessionStatus(SessionStateCode.CONNECTED),
        )

    @Test
    fun `an app-socket drop parks the session and Resume re-attaches the same session with no second connect`() {
        val fake =
            FakeUpstreamSession(
                connectedScript(),
                scriptedServerInfo = ServerInfo(serverVersion = "1.4.60", mainRoomId = "room-1"),
            )
        resumeScenario(fake) { client, factoryCalls ->
            var resumeId = ""
            // Socket #1: log in, capture the resume handle, then drop the socket WITHOUT a Logout.
            client.session { resumeId = loginAndCaptureResumeId("resumer") }

            // The upstream was NOT disconnected — it is parked, awaiting resume.
            assertFalse(fake.disconnectCalled, "an unexpected socket drop must park, not disconnect")

            // Socket #2: fresh handshake, then Resume the parked session.
            client.session {
                handshake()
                sendSerialized<ClientMessage>(Resume(resumeId = resumeId, requestId = "resume-1"))
                val ack = assertInstanceOf(SessionResumable::class.java, receiveDeserialized<ServerMessage>())
                assertEquals(resumeId, ack.resumeId)
                assertEquals("resume-1", ack.requestId)

                // The resumed session is fully live on this socket: a request/response still works.
                sendSerialized<ClientMessage>(GetServerInfo(requestId = "gsi-1"))
                val info = assertInstanceOf(ServerInfo::class.java, receiveDeserialized<ServerMessage>())
                assertEquals("1.4.60", info.serverVersion)
                assertEquals("gsi-1", info.requestId)
            }

            // Proof it is the SAME upstream session: the upstream was connected exactly once and the
            // factory built exactly one upstream — no re-Login/re-auth on resume.
            assertEquals(1, fake.connectCount.get(), "resume must not open a second upstream connection")
            assertEquals(1, factoryCalls.get(), "resume must not build a second upstream")
        }
    }

    @Test
    fun `an expired resume handle is rejected and the parked session is evicted`() {
        val fake = FakeUpstreamSession(connectedScript())
        resumeScenario(fake, config = ResumeConfig(ttl = 300.milliseconds, keepaliveInterval = 100.milliseconds)) { client, _ ->
            var resumeId = ""
            client.session { resumeId = loginAndCaptureResumeId("expirer") }

            // TTL elapses on the registry's real-time scope → the parked session is evicted + disconnected.
            withContext(Dispatchers.Default) {
                withTimeout(10_000) { fake.awaitDisconnect() }
            }

            // A Resume for the now-expired handle is rejected.
            client.session {
                handshake()
                sendSerialized<ClientMessage>(Resume(resumeId = resumeId, requestId = "resume-2"))
                val rejected = assertInstanceOf(ResumeRejected::class.java, receiveDeserialized<ServerMessage>())
                assertEquals("resume-2", rejected.requestId)
            }
        }
    }

    @Test
    fun `Logout evicts the session so a later Resume is rejected`() {
        val fake = FakeUpstreamSession(connectedScript())
        resumeScenario(fake) { client, _ ->
            var resumeId = ""
            client.session {
                resumeId = loginAndCaptureResumeId("logouter")
                sendSerialized<ClientMessage>(Logout())
                withTimeout(5_000) { fake.awaitDisconnect() }
            }

            client.session {
                handshake()
                sendSerialized<ClientMessage>(Resume(resumeId = resumeId))
                assertInstanceOf(ResumeRejected::class.java, receiveDeserialized<ServerMessage>())
            }
        }
    }

    @Test
    fun `an unknown resume handle is rejected`() {
        val fake = FakeUpstreamSession(connectedScript())
        resumeScenario(fake) { client, factoryCalls ->
            client.session {
                handshake()
                sendSerialized<ClientMessage>(Resume(resumeId = "never-issued", requestId = "resume-x"))
                val rejected = assertInstanceOf(ResumeRejected::class.java, receiveDeserialized<ServerMessage>())
                assertEquals("resume-x", rejected.requestId)
            }
            // A miss never touches the upstream.
            assertEquals(0, factoryCalls.get())
            assertEquals(0, fake.connectCount.get())
        }
    }
}
