package magefree.bridge.session

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.receiveDeserialized
import io.ktor.client.plugins.websocket.sendSerialized
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.serialization.kotlinx.KotlinxWebsocketSerializationConverter
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.withTimeout
import magefree.bridge.ws.sessionWebSocket
import magefree.protocol.ClientHello
import magefree.protocol.ClientMessage
import magefree.protocol.Login
import magefree.protocol.Logout
import magefree.protocol.Ping
import magefree.protocol.Pong
import magefree.protocol.ProtocolJson
import magefree.protocol.ProtocolVersion
import magefree.protocol.ServerHello
import magefree.protocol.ServerMessage
import magefree.protocol.SessionStateCode
import magefree.protocol.SessionStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.server.websocket.WebSockets as ServerWebSockets

/**
 * Hermetic end-to-end coverage of the session bridge: a real Ktor WebSocket client drives the real
 * [sessionWebSocket] route + [SessionCoordinator], with a [FakeUpstreamSession] scripting each
 * `SessionStateCode` path. No XMage server involved.
 */
class SessionCoordinatorTest {
    /** Boots the route with [fake] as the per-socket upstream and hands the test a WS-capable client. */
    private fun scenario(
        fake: FakeUpstreamSession,
        block: suspend (client: HttpClient) -> Unit,
    ) = testApplication {
        application { sessionModule { fake } }
        val wsClient =
            createClient {
                install(ClientWebSockets) {
                    contentConverter = KotlinxWebsocketSerializationConverter(ProtocolJson.json)
                }
            }
        block(wsClient)
    }

    private fun Application.sessionModule(newUpstream: () -> UpstreamSession) {
        install(ServerWebSockets) {
            contentConverter = KotlinxWebsocketSerializationConverter(ProtocolJson.json)
        }
        routing { sessionWebSocket(newUpstream = newUpstream) }
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

    private fun status(
        state: SessionStateCode,
        message: String? = null,
    ) = SessionStatus(state = state, message = message)

    @Test
    fun `login yields CONNECTING then CONNECTED, requestId only on the first, and close disconnects`() {
        val fake = FakeUpstreamSession(listOf(status(SessionStateCode.CONNECTING), status(SessionStateCode.CONNECTED)))
        scenario(fake) { client ->
            client.session {
                handshake()
                sendSerialized<ClientMessage>(Login(username = "alice", password = "pw", requestId = "req-1"))

                val connecting = nextStatus()
                assertEquals(SessionStateCode.CONNECTING, connecting.state)
                assertEquals("req-1", connecting.requestId)

                val connected = nextStatus()
                assertEquals(SessionStateCode.CONNECTED, connected.state)
                assertNull(connected.requestId)
            }
            withTimeout(5_000) { fake.awaitDisconnect() }
            assertEquals(Credentials("alice", "pw"), fake.lastCredentials)
        }
    }

    @Test
    fun `bad credentials yield AUTH_FAILED`() {
        val fake = FakeUpstreamSession(listOf(status(SessionStateCode.CONNECTING), status(SessionStateCode.AUTH_FAILED)))
        scenario(fake) { client ->
            client.session {
                handshake()
                sendSerialized<ClientMessage>(Login(username = "bob"))
                assertEquals(SessionStateCode.CONNECTING, nextStatus().state)
                assertEquals(SessionStateCode.AUTH_FAILED, nextStatus().state)
            }
        }
    }

    @Test
    fun `a version gap yields VERSION_UNSUPPORTED carrying both versions`() {
        val fake =
            FakeUpstreamSession(
                listOf(
                    status(SessionStateCode.CONNECTING),
                    status(SessionStateCode.VERSION_UNSUPPORTED, message = "server=1.4.61 bridge=1.4.60"),
                ),
            )
        scenario(fake) { client ->
            client.session {
                handshake()
                sendSerialized<ClientMessage>(Login(username = "carol"))
                assertEquals(SessionStateCode.CONNECTING, nextStatus().state)

                val versionGap = nextStatus()
                assertEquals(SessionStateCode.VERSION_UNSUPPORTED, versionGap.state)
                assertTrue(versionGap.message!!.contains("server=1.4.61"), "message: ${versionGap.message}")
                assertTrue(versionGap.message!!.contains("bridge=1.4.60"), "message: ${versionGap.message}")
            }
        }
    }

    @Test
    fun `an upstream drop surfaces RECONNECTING then DISCONNECTED`() {
        val fake =
            FakeUpstreamSession(
                listOf(
                    status(SessionStateCode.CONNECTING),
                    status(SessionStateCode.CONNECTED),
                    status(SessionStateCode.RECONNECTING),
                    status(SessionStateCode.DISCONNECTED),
                ),
            )
        scenario(fake) { client ->
            client.session {
                handshake()
                sendSerialized<ClientMessage>(Login(username = "dave"))
                assertEquals(SessionStateCode.CONNECTING, nextStatus().state)
                assertEquals(SessionStateCode.CONNECTED, nextStatus().state)
                assertEquals(SessionStateCode.RECONNECTING, nextStatus().state)
                assertEquals(SessionStateCode.DISCONNECTED, nextStatus().state)
            }
        }
    }

    @Test
    fun `Logout disconnects the upstream`() {
        val fake = FakeUpstreamSession(listOf(status(SessionStateCode.CONNECTING), status(SessionStateCode.CONNECTED)))
        scenario(fake) { client ->
            client.session {
                handshake()
                sendSerialized<ClientMessage>(Login(username = "erin"))
                assertEquals(SessionStateCode.CONNECTING, nextStatus().state)
                assertEquals(SessionStateCode.CONNECTED, nextStatus().state)
                sendSerialized<ClientMessage>(Logout())
                withTimeout(5_000) { fake.awaitDisconnect() }
            }
        }
    }

    @Test
    fun `Ping still yields Pong after the handshake`() {
        val fake = FakeUpstreamSession(emptyList())
        scenario(fake) { client ->
            client.session {
                handshake()
                sendSerialized<ClientMessage>(Ping(nonce = "n", requestId = "p-1"))
                val reply = assertInstanceOf(Pong::class.java, receiveDeserialized<ServerMessage>())
                assertEquals("n", reply.nonce)
                assertEquals("p-1", reply.requestId)
            }
        }
    }
}
