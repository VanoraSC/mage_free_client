package magefree.bridge.session

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.receiveDeserialized
import io.ktor.client.plugins.websocket.sendSerialized
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.serialization.kotlinx.KotlinxWebsocketSerializationConverter
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import magefree.bridge.module
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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.InputStream
import java.net.Socket
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.server.websocket.WebSockets as ServerWebSockets

/**
 * **a network excursion must not leak an upstream session.**
 *
 * When a phone's radio goes away there is no FIN — the bridge's socket simply stops producing bytes,
 * for ever. Nothing in the bridge used to notice: Ktor's server pinger is disabled by default, the
 * coordinator's read never returned, so the entry stayed **bound**, its resume TTL never started, and it
 * was never evicted. The smoke's log has one such entry bound for 7.9 hours. And because the registry
 * only resumes a *parked* entry, the app coming back was refused its `Resume` and fell back to `Login` —
 * a **second** XMage login for the same username, which is what the smoke observed on every excursion.
 *
 * Two independent guards, one per half of that:
 * - the bridge hangs up on a peer that has stopped answering, so the session parks and can be reclaimed;
 * - presenting a resume handle for a still-bound entry reaps it, so the fallback `Login` replaces the
 *   session instead of duplicating it.
 */
class AppSocketLeakTest {
    /**
     * A socket that completes the WebSocket upgrade and then goes completely silent — never answering a
     * ping, never sending a close, never closing the TCP connection. That is what a dead radio leaves
     * behind, and it is the one thing a Ktor/OkHttp client cannot simulate (both auto-pong), so this
     * speaks the framing by hand.
     */
    @Test
    fun `the bridge hangs up on an app socket that has stopped answering`() {
        val server =
            embeddedServer(Netty, port = 0) {
                module(wsPingPeriod = PING_PERIOD)
            }
        server.start(wait = false)
        try {
            val port =
                runBlocking {
                    server.engine
                        .resolvedConnectors()
                        .first()
                        .port
                }
            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = REAP_BUDGET_MS.toInt()
                socket.getOutputStream().apply {
                    write(upgradeRequest(port).toByteArray(Charsets.ISO_8859_1))
                    flush()
                }
                val input = socket.getInputStream()
                assertTrue(
                    readUpgradeResponse(input).startsWith("HTTP/1.1 101"),
                    "the handshake must succeed before the silence means anything",
                )

                // Now: read frames but answer nothing. The server must ping, get no pong, and hang up.
                var pings = 0
                var hungUp = false
                val deadline = System.currentTimeMillis() + REAP_BUDGET_MS
                while (System.currentTimeMillis() < deadline) {
                    val opcode = readFrame(input)
                    if (opcode == null) {
                        hungUp = true // EOF: the connection was closed
                        break
                    }
                    if (opcode == OPCODE_PING) pings++
                    if (opcode == OPCODE_CLOSE) {
                        hungUp = true
                        break
                    }
                }

                assertTrue(pings > 0, "the bridge must actually probe an app socket; it sent $pings pings")
                assertTrue(
                    hungUp,
                    "a socket that stops answering must be hung up on, so the coordinator's teardown runs " +
                        "and the session is parked instead of leaking bound for ever",
                )
            }
        } finally {
            server.stop(gracePeriodMillis = 0, timeoutMillis = 2_000)
        }
    }

    /**
     * The other half, and the one the user actually feels: the app comes back and presents its resume
     * handle while the bridge still believes the *old* socket is holding the session. The handle is the
     * app's own, so its arrival on a new socket is proof the old one is gone.
     */
    @Test
    fun `a resume for a still-bound session reaps it instead of leaving a duplicate login`() {
        val fake = FakeUpstreamSession(connectedScript())
        testApplicationTimed {
            val registry = SessionRegistry(ResumeConfig(ttl = 60.seconds, keepaliveInterval = 30.seconds))
            try {
                application {
                    install(ServerWebSockets) {
                        contentConverter = KotlinxWebsocketSerializationConverter(ProtocolJson.json)
                    }
                    routing { sessionWebSocket(registry = registry, newUpstream = { fake }) }
                }
                val wsClient =
                    createClient {
                        install(ClientWebSockets) {
                            contentConverter = KotlinxWebsocketSerializationConverter(ProtocolJson.json)
                        }
                    }

                coroutineScope {
                    // Socket #1 logs in and is deliberately **held open** for the whole test: this is
                    // the socket whose network died without telling anyone, so the bridge still thinks
                    // it is live.
                    val resumeId = CompletableDeferred<String>()
                    val release = CompletableDeferred<Unit>()
                    val stale =
                        launch {
                            wsClient.session {
                                resumeId.complete(loginAndCaptureResumeId("excursion"))
                                release.await()
                            }
                        }
                    val held = resumeId.await()
                    assertTrue(registry.isRegistered(held), "the first session is registered and bound")

                    // Socket #2: the app is back on a new network and offers the handle it still holds.
                    wsClient.session {
                        handshake()
                        sendSerialized<ClientMessage>(Resume(resumeId = held, requestId = "resume-1"))
                        val rejected =
                            assertInstanceOf(ResumeRejected::class.java, receiveDeserialized<ServerMessage>())
                        assertEquals("resume-1", rejected.requestId)
                        assertTrue(
                            rejected.reason.contains("stale"),
                            "the app must be told its held session was torn down, not that its handle was " +
                                "never valid; got '${rejected.reason}'",
                        )
                    }

                    // The stale upstream is gone — so the Login the app falls back to cannot be a
                    // *second* live login for the same username, which is the leak.
                    withContext(Dispatchers.Default) {
                        withTimeout(TEARDOWN_TIMEOUT_MS) { fake.awaitDisconnect() }
                    }
                    assertFalse(
                        registry.isRegistered(held),
                        "the stale entry must be reaped, not left bound to a socket that will never speak",
                    )

                    release.complete(Unit)
                    stale.join()
                }
            } finally {
                registry.shutdown()
            }
        }
    }

    // --- harness -------------------------------------------------------------------------------------

    private suspend fun HttpClient.session(block: suspend DefaultClientWebSocketSession.() -> Unit) =
        webSocket("/${ProtocolVersion.PATH_SEGMENT}/session") { block() }

    private suspend fun DefaultClientWebSocketSession.handshake() {
        sendSerialized<ClientMessage>(
            ClientHello(protocolMajor = ProtocolVersion.MAJOR, protocolMinor = ProtocolVersion.MINOR),
        )
        assertInstanceOf(ServerHello::class.java, receiveDeserialized<ServerMessage>())
    }

    private suspend fun DefaultClientWebSocketSession.loginAndCaptureResumeId(username: String): String {
        handshake()
        sendSerialized<ClientMessage>(Login(username = username, requestId = "login-1"))
        assertInstanceOf(SessionStatus::class.java, receiveDeserialized<ServerMessage>())
        assertInstanceOf(SessionStatus::class.java, receiveDeserialized<ServerMessage>())
        return assertInstanceOf(SessionResumable::class.java, receiveDeserialized<ServerMessage>()).resumeId
    }

    private fun connectedScript() =
        listOf(
            SessionStatus(SessionStateCode.CONNECTING),
            SessionStatus(SessionStateCode.CONNECTED),
        )

    private fun upgradeRequest(port: Int): String =
        "GET /${ProtocolVersion.PATH_SEGMENT}/session HTTP/1.1\r\n" +
            "Host: 127.0.0.1:$port\r\n" +
            "Upgrade: websocket\r\n" +
            "Connection: Upgrade\r\n" +
            "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" +
            "Sec-WebSocket-Version: 13\r\n\r\n"

    private fun readUpgradeResponse(input: InputStream): String {
        val response = StringBuilder()
        while (!response.endsWith("\r\n\r\n")) {
            val byte = input.read()
            if (byte < 0) break
            response.append(byte.toChar())
        }
        return response.toString()
    }

    /**
     * Reads one server→client frame and returns its opcode, or `null` at end of stream. Server frames
     * are unmasked; the payload is skipped because only the opcode matters here.
     */
    private fun readFrame(input: InputStream): Int? {
        val b0 = input.read()
        if (b0 < 0) return null
        val b1 = input.read()
        if (b1 < 0) return null
        var length = (b1 and 0x7F).toLong()
        when (length) {
            126L -> {
                length = 0
                repeat(2) { length = (length shl 8) or input.read().toLong() }
            }

            127L -> {
                length = 0
                repeat(8) { length = (length shl 8) or input.read().toLong() }
            }
        }
        if (b1 and 0x80 != 0) repeat(4) { input.read() }
        repeat(length.toInt()) { if (input.read() < 0) return null }
        return b0 and 0x0F
    }

    private companion object {
        val PING_PERIOD = 300.milliseconds

        /** Comfortably above ping period + pong timeout, so a pass is the behaviour and not a fluke. */
        const val REAP_BUDGET_MS = 10_000L
        const val TEARDOWN_TIMEOUT_MS = 10_000L

        const val OPCODE_CLOSE = 0x8
        const val OPCODE_PING = 0x9
    }
}
