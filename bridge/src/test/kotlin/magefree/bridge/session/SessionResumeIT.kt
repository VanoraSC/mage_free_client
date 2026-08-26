package magefree.bridge.session

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.receiveDeserialized
import io.ktor.client.plugins.websocket.sendSerialized
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.serialization.kotlinx.KotlinxWebsocketSerializationConverter
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import magefree.bridge.ws.sessionWebSocket
import magefree.protocol.ClientHello
import magefree.protocol.ClientMessage
import magefree.protocol.Login
import magefree.protocol.Logout
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
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.server.websocket.WebSockets as ServerWebSockets

/**
 * Live end-to-end proof against a running XMage server: a parked upstream session
 * **survives** an app-socket drop and a `Resume` re-attaches to the **same** session without
 * re-authenticating, plus a TTL-expiry case that rejects.
 *
 * **Env-gated:** enabled only when `XMAGE_SERVER` is set (like [SessionBridgeIT]); otherwise JUnit
 * reports it *skipped*, keeping `./scripts/dev gradle check` hermetic. The upstream target itself
 * comes from `XMAGE_UPSTREAM` (default `xmage-server:17171`), per the pinned-server posture.
 *
 * Grey-box: it drives the real [sessionWebSocket] route over a real embedded Netty server + CIO
 * WebSocket client, but injects a **recording** upstream factory and shares the [SessionRegistry] so
 * it can assert (a) exactly one upstream was ever built (no second connect/login on resume) and (b)
 * the upstream `SessionImpl.getSessionId()` is unchanged across the drop+resume — i.e. never
 * reconnected/re-authed.
 *
 * Run it with:
 * ```
 * ./scripts/dev up xmage-server
 * XMAGE_SERVER=xmage-server:17171 ./scripts/dev gradle :bridge:test --tests '*ResumeIT' --rerun-tasks
 * ```
 */
@EnabledIfEnvironmentVariable(named = "XMAGE_SERVER", matches = ".+")
class SessionResumeIT {
    private fun newClient(): HttpClient =
        HttpClient(CIO) {
            install(ClientWebSockets) {
                contentConverter = KotlinxWebsocketSerializationConverter(ProtocolJson.json)
            }
        }

    private fun username(): String = "it_${UUID.randomUUID().toString().replace("-", "").substring(0, 8)}"

    @Test
    fun `a parked session survives an app-socket drop and Resume re-attaches without re-auth`() =
        runBlocking {
            val created = CopyOnWriteArrayList<XMageUpstreamSession>()
            val registry = SessionRegistry(ResumeConfig(ttl = 60.seconds, keepaliveInterval = 10.seconds))
            val server =
                embeddedServer(Netty, port = 0) {
                    install(ServerWebSockets) {
                        contentConverter = KotlinxWebsocketSerializationConverter(ProtocolJson.json)
                    }
                    routing {
                        sessionWebSocket(
                            registry = registry,
                            newUpstream = {
                                XMageUpstreamSession(UpstreamTarget.fromEnv()).also { created.add(it) }
                            },
                        )
                    }
                }.also { it.start(wait = false) }
            val port =
                server.engine
                    .resolvedConnectors()
                    .first()
                    .port
            val client = newClient()

            try {
                var resumeId = ""
                // Socket #1: log in, capture the resume handle, then drop the socket WITHOUT a Logout.
                client.webSocket(host = "localhost", port = port, path = "/${ProtocolVersion.PATH_SEGMENT}/session") {
                    sendSerialized<ClientMessage>(
                        ClientHello(protocolMajor = ProtocolVersion.MAJOR, protocolMinor = ProtocolVersion.MINOR),
                    )
                    assertInstanceOf(ServerHello::class.java, receiveDeserialized<ServerMessage>())
                    sendSerialized<ClientMessage>(Login(username = username(), requestId = "it-1"))
                    withTimeout(30_000) {
                        assertEquals(SessionStateCode.CONNECTING, statusOf(receiveDeserialized()))
                        assertEquals(
                            SessionStateCode.CONNECTED,
                            statusOf(receiveDeserialized()),
                            "expected CONNECTED against the reference server",
                        )
                        resumeId =
                            assertInstanceOf(SessionResumable::class.java, receiveDeserialized<ServerMessage>()).resumeId
                    }
                }

                // The same upstream must be alive and connected while parked; capture its server session id.
                assertEquals(1, created.size, "exactly one upstream should have been built")
                val sessionIdBefore = created.first().sessionId()
                assertNotNull(sessionIdBefore, "upstream SessionImpl.getSessionId() should be set once CONNECTED")

                // Socket #2: fresh handshake, then Resume the parked session.
                client.webSocket(host = "localhost", port = port, path = "/${ProtocolVersion.PATH_SEGMENT}/session") {
                    sendSerialized<ClientMessage>(
                        ClientHello(protocolMajor = ProtocolVersion.MAJOR, protocolMinor = ProtocolVersion.MINOR),
                    )
                    assertInstanceOf(ServerHello::class.java, receiveDeserialized<ServerMessage>())
                    sendSerialized<ClientMessage>(Resume(resumeId = resumeId, requestId = "it-resume"))
                    withTimeout(30_000) {
                        val ack = assertInstanceOf(SessionResumable::class.java, receiveDeserialized<ServerMessage>())
                        assertEquals(resumeId, ack.resumeId)
                    }
                    // Clean teardown of the still-live session.
                    sendSerialized<ClientMessage>(Logout())
                }

                // Proof: no second upstream built, and the SAME server-side session id — never re-authed.
                assertEquals(1, created.size, "resume must not build a second upstream (no re-login)")
                assertEquals(
                    sessionIdBefore,
                    created.first().sessionId(),
                    "the upstream session id must be unchanged across drop+resume",
                )
            } finally {
                client.close()
                registry.shutdown()
                server.stop(1_000, 2_000)
            }
        }

    @Test
    fun `an expired resume handle is rejected after the TTL grace window`() =
        runBlocking {
            val registry = SessionRegistry(ResumeConfig(ttl = 2.seconds, keepaliveInterval = 1.seconds))
            val server =
                embeddedServer(Netty, port = 0) {
                    install(ServerWebSockets) {
                        contentConverter = KotlinxWebsocketSerializationConverter(ProtocolJson.json)
                    }
                    routing {
                        sessionWebSocket(
                            registry = registry,
                            newUpstream = { XMageUpstreamSession(UpstreamTarget.fromEnv()) },
                        )
                    }
                }.also { it.start(wait = false) }
            val port =
                server.engine
                    .resolvedConnectors()
                    .first()
                    .port
            val client = newClient()

            try {
                var resumeId = ""
                client.webSocket(host = "localhost", port = port, path = "/${ProtocolVersion.PATH_SEGMENT}/session") {
                    sendSerialized<ClientMessage>(
                        ClientHello(protocolMajor = ProtocolVersion.MAJOR, protocolMinor = ProtocolVersion.MINOR),
                    )
                    assertInstanceOf(ServerHello::class.java, receiveDeserialized<ServerMessage>())
                    sendSerialized<ClientMessage>(Login(username = username(), requestId = "it-2"))
                    withTimeout(30_000) {
                        assertEquals(SessionStateCode.CONNECTING, statusOf(receiveDeserialized()))
                        assertEquals(SessionStateCode.CONNECTED, statusOf(receiveDeserialized()))
                        resumeId =
                            assertInstanceOf(SessionResumable::class.java, receiveDeserialized<ServerMessage>()).resumeId
                    }
                }

                // Wait for the grace window to lapse and the parked session to be evicted.
                withTimeout(20_000) {
                    while (registry.isRegistered(resumeId)) delay(100)
                }

                client.webSocket(host = "localhost", port = port, path = "/${ProtocolVersion.PATH_SEGMENT}/session") {
                    sendSerialized<ClientMessage>(
                        ClientHello(protocolMajor = ProtocolVersion.MAJOR, protocolMinor = ProtocolVersion.MINOR),
                    )
                    assertInstanceOf(ServerHello::class.java, receiveDeserialized<ServerMessage>())
                    sendSerialized<ClientMessage>(Resume(resumeId = resumeId, requestId = "it-expired"))
                    withTimeout(30_000) {
                        val rejected = assertInstanceOf(ResumeRejected::class.java, receiveDeserialized<ServerMessage>())
                        assertEquals("it-expired", rejected.requestId)
                    }
                }
            } finally {
                client.close()
                registry.shutdown()
                server.stop(1_000, 2_000)
            }
        }

    private fun statusOf(message: ServerMessage): SessionStateCode = assertInstanceOf(SessionStatus::class.java, message).state
}
