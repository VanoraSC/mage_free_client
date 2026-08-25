package magefree.network.ktor

import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import magefree.model.ConnectionState
import magefree.model.Credentials
import magefree.model.ServerTarget
import magefree.model.SessionEvent
import magefree.network.reconnect.ReconnectPolicy
import magefree.protocol.ClientHello
import magefree.protocol.ClientMessage
import magefree.protocol.Login
import magefree.protocol.ProtocolJson
import magefree.protocol.ProtocolVersion
import magefree.protocol.Resume
import magefree.protocol.ServerHello
import magefree.protocol.ServerMessage
import magefree.protocol.SessionResumable
import magefree.protocol.SessionStateCode
import magefree.protocol.SessionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Story 0050, **both directions on real frames** — the pin that stops the fix for a zombie session from
 * destroying reconnect (0046's pattern, one layer up).
 *
 * The two failures look identical from inside the app: in each case the session it thought it had is no
 * longer usable. They must be answered in opposite ways, and only the *wire* can tell them apart:
 *
 * - **A dead upstream** — the bridge relays a terminal `SessionStatus(DISCONNECTED)` because its
 *   keepalive found the XMage session gone (story 0050 defect A). There is nothing to resume: the app
 *   must stop, land on a disconnected state carrying the bridge's reason, and **not** loop trying to
 *   `Resume` a session the server has already thrown away.
 * - **A transport drop** — the socket simply dies with nothing on it (a network hand-off; see the
 *   ~33 s finding in [KtorBridgeClient]'s KDoc). The session upstream is fine and parked, so the app
 *   must reconnect and `Resume` it — stories 0023/0024, which this story must not regress.
 *
 * The double is the *bridge*: a throwaway in-process Ktor endpoint that records the frame sequence per
 * socket, so "did the app try to resume, or not?" is answered by what actually reached the server.
 */
class KtorBridgeClientSessionLossTest {
    @Test
    fun `a relayed upstream death ends the session with its reason and never tries to resume`() =
        runBlocking {
            withBridge(Script.DIE_AFTER_CONNECT) { bridge, target ->
                val client = KtorBridgeClient(reconnectPolicy = FAST_RETRY)
                val events = CopyOnWriteArrayList<SessionEvent>()
                val ended = CompletableDeferred<Unit>()
                val collecting =
                    launch {
                        client.connect(target, Credentials(USERNAME)).collect { events += it }
                        ended.complete(Unit)
                    }

                // Terminal: the flow completes on its own. If the app treated a dead upstream as a
                // transport drop this would never finish — it would sit in the reconnect loop.
                withTimeout(SETTLE_TIMEOUT_MS) { ended.await() }

                val disconnected = events.filterIsInstance<SessionEvent.Disconnected>().lastOrNull()
                assertEquals(
                    "the bridge's reason must survive to the app, so the user is told they are signed " +
                        "out rather than shown a bare failure; events were $events",
                    UPSTREAM_LOST,
                    disconnected?.message,
                )
                assertEquals(
                    "a dead upstream must leave the app disconnected, never Connected",
                    ConnectionState.Disconnected,
                    client.connectionState.value,
                )
                assertFalse(
                    "there is nothing to resume once the server has dropped the session; the bridge " +
                        "saw ${bridge.log}",
                    RESUME in bridge.log,
                )
                assertEquals(
                    "exactly one socket, one login, no retry loop; the bridge saw ${bridge.log}",
                    listOf(HELLO, LOGIN, CLOSED),
                    bridge.log,
                )
                collecting.cancelAndJoin()
            }
        }

    @Test
    fun `a bare transport drop still reconnects and resumes the parked session`() =
        runBlocking {
            withBridge(Script.DROP_AFTER_CONNECT) { bridge, target ->
                val client = KtorBridgeClient(reconnectPolicy = FAST_RETRY)
                val events = CopyOnWriteArrayList<SessionEvent>()
                val resumed = CompletableDeferred<Unit>()
                val collecting =
                    launch {
                        client.connect(target, Credentials(USERNAME)).collect { event ->
                            events += event
                            // The *second* Connected is the resume completing (the first came from the
                            // Login on the socket the bridge then dropped).
                            if (events.count { it is SessionEvent.Connected } == 2) resumed.complete(Unit)
                        }
                    }

                withTimeout(SETTLE_TIMEOUT_MS) { resumed.await() }

                assertTrue(
                    "0023/0024 must survive this story: a bare drop reconnects and presents the held " +
                        "resume handle instead of re-authenticating; the bridge saw ${bridge.log}",
                    bridge.log.containsAll(listOf(HELLO, LOGIN, CLOSED, HELLO, RESUME)),
                )
                assertEquals(
                    "a resume must not re-login; the bridge saw ${bridge.log}",
                    1,
                    bridge.log.count { it == LOGIN },
                )
                assertTrue(
                    "the recovery must surface as Restoring, not as a bare Disconnected; events were $events",
                    events.contains(SessionEvent.Restoring),
                )
                assertEquals(
                    "the resumed session is connected again",
                    ConnectionState.Connected,
                    client.connectionState.value,
                )
                collecting.cancelAndJoin()
            }
        }

    // --- harness -------------------------------------------------------------------------------------

    /** What the fake bridge does once a session has reached `CONNECTED`. */
    private enum class Script {
        /** Relay a terminal `DISCONNECTED` — the bridge's keepalive found the upstream gone. */
        DIE_AFTER_CONNECT,

        /** Close the socket with nothing on it — a network drop. Later sockets stay up. */
        DROP_AFTER_CONNECT,
    }

    private suspend fun withBridge(
        script: Script,
        body: suspend CoroutineScope.(RecordingBridge, ServerTarget) -> Unit,
    ) = coroutineScope {
        val bridge = RecordingBridge(script)
        val server = bridge.start()
        try {
            body(bridge, ServerTarget(host = "127.0.0.1", port = bridge.port, secure = false))
        } finally {
            server.stop(gracePeriodMillis = 0, timeoutMillis = 2_000)
        }
    }

    /**
     * A minimal stand-in for `:bridge`'s session endpoint. It completes the 0004 handshake, answers a
     * `Login` with `CONNECTED` + a resume id, acks a `Resume`, and then plays [script]. Every client
     * frame and every socket close is appended to [log], across sockets, so a test can assert what the
     * app did after the failure rather than what it says it did.
     */
    private class RecordingBridge(
        private val script: Script,
    ) {
        val log: MutableList<String> = CopyOnWriteArrayList()

        @Volatile
        var port: Int = 0
            private set

        /** Only the first session is dropped/killed; a resumed one is left alone. */
        @Volatile
        private var scriptPlayed = false

        fun start(): EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration> {
            val server =
                embeddedServer(Netty, port = 0) {
                    install(WebSockets)
                    routing {
                        webSocket("/${ProtocolVersion.PATH_SEGMENT}/session") { serve() }
                    }
                }
            server.start(wait = false)
            port =
                runBlocking {
                    server.engine
                        .resolvedConnectors()
                        .first()
                        .port
                }
            return server
        }

        private suspend fun DefaultWebSocketSession.serve() {
            try {
                for (frame in incoming) {
                    if (frame !is Frame.Text) continue
                    when (val message = ProtocolJson.json.decodeFromString<ClientMessage>(frame.readText())) {
                        is ClientHello -> {
                            log += HELLO
                            send(ServerHello(ProtocolVersion.MAJOR, ProtocolVersion.MINOR, BRIDGE_VERSION))
                        }

                        is Login -> {
                            log += LOGIN
                            send(SessionStatus(SessionStateCode.CONNECTED, requestId = message.requestId))
                            send(SessionResumable(resumeId = RESUME_ID))
                            if (!scriptPlayed) {
                                scriptPlayed = true
                                when (script) {
                                    Script.DIE_AFTER_CONNECT -> {
                                        send(SessionStatus(SessionStateCode.DISCONNECTED, message = UPSTREAM_LOST))
                                    }

                                    Script.DROP_AFTER_CONNECT ->
                                        close(CloseReason(CloseReason.Codes.INTERNAL_ERROR, "network"))
                                }
                            }
                        }

                        is Resume -> {
                            log += RESUME
                            send(SessionResumable(resumeId = message.resumeId, requestId = message.requestId))
                        }

                        else -> log += message::class.simpleName.orEmpty()
                    }
                }
            } finally {
                log += CLOSED
                runCatching { close(CloseReason(CloseReason.Codes.NORMAL, "bye")) }
            }
        }

        private suspend fun DefaultWebSocketSession.send(message: ServerMessage) {
            send(Frame.Text(ProtocolJson.json.encodeToString<ServerMessage>(message)))
        }
    }

    private companion object {
        const val USERNAME = "pete"
        const val BRIDGE_VERSION = "test-bridge"
        const val RESUME_ID = "resume-0050"

        /** The bridge's own wording for a session the upstream server has dropped (`SessionRegistry`). */
        const val UPSTREAM_LOST = "your session ended on the server; sign in again"

        const val HELLO = "hello"
        const val LOGIN = "login"
        const val RESUME = "resume"
        const val CLOSED = "closed"

        const val SETTLE_TIMEOUT_MS = 20_000L

        /** No point sleeping out a real back-off in a hermetic test; the *behaviour* is what is pinned. */
        val FAST_RETRY = ReconnectPolicy(initialDelayMillis = 10L, maxDelayMillis = 50L, jitterRatio = 0.0)
    }
}
