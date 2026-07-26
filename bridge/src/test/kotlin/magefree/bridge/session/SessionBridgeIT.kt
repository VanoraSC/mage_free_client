package magefree.bridge.session

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.receiveDeserialized
import io.ktor.client.plugins.websocket.sendSerialized
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.serialization.kotlinx.KotlinxWebsocketSerializationConverter
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import magefree.bridge.module
import magefree.protocol.ClientHello
import magefree.protocol.ClientMessage
import magefree.protocol.Login
import magefree.protocol.Logout
import magefree.protocol.ProtocolJson
import magefree.protocol.ProtocolVersion
import magefree.protocol.ServerHello
import magefree.protocol.ServerMessage
import magefree.protocol.SessionStateCode
import magefree.protocol.SessionStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.util.UUID

/**
 * Live end-to-end: an app WebSocket client logs in through the **production** bridge wiring
 * ([module], which uses the real [XMageUpstreamSession]) to a running XMage server, and observes
 * `CONNECTING` → `CONNECTED`, then tears down cleanly.
 *
 * **Env-gated:** enabled only when `XMAGE_SERVER` is set (mirroring 0003's `ConnectAuthenticateIT`);
 * otherwise JUnit reports it *skipped*, keeping `./scripts/dev gradle check` hermetic. The gate var
 * only signals intent — the connection target itself comes from `XMAGE_UPSTREAM` (default
 * `xmage-server:17171`), consistent with the pinned-server posture (the app never sends host/port).
 *
 * Uses a real embedded Netty server + a real CIO WebSocket client (not `testApplication`) so that the
 * live XMage remoting I/O runs on real dispatchers/threads without tripping the test scheduler.
 *
 * Run it with:
 * ```
 * ./scripts/dev up xmage-server
 * XMAGE_SERVER=xmage-server:17171 ./scripts/dev gradle :bridge:test --tests '*SessionBridgeIT' --rerun-tasks
 * ```
 */
@EnabledIfEnvironmentVariable(named = "XMAGE_SERVER", matches = ".+")
class SessionBridgeIT {
    @Test
    fun `login over the bridge yields CONNECTING then CONNECTED against the pinned server`() =
        runBlocking {
            val server =
                embeddedServer(Netty, port = 0) { module() }.also { it.start(wait = false) }
            val port =
                server.engine
                    .resolvedConnectors()
                    .first()
                    .port

            val client =
                HttpClient(CIO) {
                    install(WebSockets) {
                        contentConverter = KotlinxWebsocketSerializationConverter(ProtocolJson.json)
                    }
                }

            // XMage validates usernames: [a-z0-9_], length 3..14. "it_" + 8 hex = 11 chars.
            val username = "it_${UUID.randomUUID().toString().replace("-", "").substring(0, 8)}"

            try {
                client.webSocket(
                    host = "localhost",
                    port = port,
                    path = "/${ProtocolVersion.PATH_SEGMENT}/session",
                ) {
                    sendSerialized<ClientMessage>(
                        ClientHello(protocolMajor = ProtocolVersion.MAJOR, protocolMinor = ProtocolVersion.MINOR),
                    )
                    assertInstanceOf(ServerHello::class.java, receiveDeserialized<ServerMessage>())

                    sendSerialized<ClientMessage>(Login(username = username, requestId = "it-1"))

                    withTimeout(30_000) {
                        val connecting =
                            assertInstanceOf(SessionStatus::class.java, receiveDeserialized<ServerMessage>())
                        assertEquals(SessionStateCode.CONNECTING, connecting.state)
                        assertEquals("it-1", connecting.requestId)

                        val connected =
                            assertInstanceOf(SessionStatus::class.java, receiveDeserialized<ServerMessage>())
                        assertEquals(
                            SessionStateCode.CONNECTED,
                            connected.state,
                            "expected CONNECTED against the reference server; got $connected",
                        )
                    }

                    // Clean teardown: Logout disconnects the upstream; closing the socket also would.
                    sendSerialized<ClientMessage>(Logout())
                }
            } finally {
                client.close()
                server.stop(1_000, 2_000)
            }
        }
}
