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
import kotlinx.coroutines.Job
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
import magefree.protocol.ClientHello
import magefree.protocol.ClientMessage
import magefree.protocol.Login
import magefree.protocol.Logout
import magefree.protocol.ProtocolJson
import magefree.protocol.ProtocolVersion
import magefree.protocol.ServerHello
import magefree.protocol.ServerMessage
import magefree.protocol.SessionResumable
import magefree.protocol.SessionStateCode
import magefree.protocol.SessionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Story 0046, both directions, on real frames: a **deliberate sign-out** puts a `Logout` on the wire
 * before the socket closes; a **drop / lifecycle teardown** closes without one, which is what makes the
 * bridge park the session for story 0023's resume window (and story 0024's reconnect find it).
 *
 * The double here is the *bridge*, not the client: a throwaway in-process Ktor WebSocket server that
 * speaks the same `:protocol` handshake and records every [ClientMessage] it receives, interleaved with
 * the socket close. The client under test is the production [KtorBridgeClient], assembled as
 * `NetworkModule` assembles it. A `FakeBridgeClient`-level test could only have asserted that a method
 * was called; only a socket can answer "did a `Logout` actually reach the bridge, and in what order".
 *
 * This is the test that fails against the pre-0046 client, whose only teardown closed the socket.
 */
class KtorBridgeClientSignOutTest {
    @Test
    fun `sign-out sends Logout before closing the socket`() =
        runBlocking {
            withBridge { bridge, target ->
                val client = KtorBridgeClient()
                val collecting = collectUntilConnected(client, target)

                client.signOut()
                bridge.awaitClosed()

                assertEquals(
                    "a deliberate sign-out must signal intent (Logout) and only then close, so the bridge " +
                        "tears the upstream session down instead of parking it; the bridge saw ${bridge.log}",
                    listOf(HELLO, LOGIN, LOGOUT, CLOSED),
                    bridge.log,
                )
                collecting.cancelAndJoin()
            }
        }

    @Test
    fun `a drop or lifecycle teardown closes without a Logout`() =
        runBlocking {
            withBridge { bridge, target ->
                val client = KtorBridgeClient()
                val collecting = collectUntilConnected(client, target)

                // The intent-free teardown: exactly what the transport does when the connection is lost
                // and what a lifecycle pause amounts to on the wire.
                client.disconnect()
                bridge.awaitClosed()

                assertEquals(
                    "a socket close *without* a Logout is what tells the bridge to park the session " +
                        "(0023) so a reconnect can resume it (0024); the bridge saw ${bridge.log}",
                    listOf(HELLO, LOGIN, CLOSED),
                    bridge.log,
                )
                assertFalse("no Logout may be sent on a non-deliberate teardown", LOGOUT in bridge.log)
                collecting.cancelAndJoin()
            }
        }

    @Test
    fun `cancelling the session flow closes without a Logout`() =
        runBlocking {
            withBridge { bridge, target ->
                val client = KtorBridgeClient()
                val collecting = collectUntilConnected(client, target)

                // How the app actually stops observing: `ConnectionRepository` shares the session flow
                // `WhileSubscribed`, so the last collector going away cancels it. That is a park, not a
                // sign-out — nothing may be written to the socket on the way out.
                collecting.cancelAndJoin()
                bridge.awaitClosed()

                assertEquals(
                    "cancelling the session collection must not look like a sign-out; the bridge saw ${bridge.log}",
                    listOf(HELLO, LOGIN, CLOSED),
                    bridge.log,
                )
            }
        }

    @Test
    fun `sign-out completes promptly with no session at all`() =
        runBlocking {
            val client = KtorBridgeClient()
            // Never connected: there is no socket to write to. Best-effort means this is a no-op, not a
            // failure — signing out of nothing still leaves the caller signed out.
            withTimeout(BEST_EFFORT_TIMEOUT_MS) { client.signOut() }
        }

    @Test
    fun `sign-out completes promptly when the Logout cannot be sent`() =
        runBlocking {
            val bridge = RecordingBridge()
            val server = bridge.start()
            val target = ServerTarget(host = "127.0.0.1", port = bridge.port, secure = false)
            val client = KtorBridgeClient()
            val collecting = collectUntilConnected(client, target)

            // The socket dies under us (server gone) before the user signs out — the case where the
            // `Logout` write can only fail. Teardown must still complete, promptly and without throwing.
            server.stop(gracePeriodMillis = 0, timeoutMillis = 2_000)
            withTimeout(BEST_EFFORT_TIMEOUT_MS) { client.signOut() }

            assertEquals(
                "an unsendable Logout must not leave the client thinking it is still connected",
                ConnectionState.Disconnected,
                client.connectionState.value,
            )
            collecting.cancelAndJoin()
        }

    // --- harness -------------------------------------------------------------------------------------

    /** Collects the cold session flow (that is what holds the session open) until `Connected`. */
    private suspend fun CoroutineScope.collectUntilConnected(
        client: KtorBridgeClient,
        target: ServerTarget,
    ): Job {
        val connected = CompletableDeferred<Unit>()
        val job =
            launch {
                client.connect(target, Credentials(USERNAME)).collect { event ->
                    if (event is SessionEvent.Connected) connected.complete(Unit)
                }
            }
        withTimeout(CONNECT_TIMEOUT_MS) { connected.await() }
        return job
    }

    /** Runs [body] against a freshly-started in-process bridge, stopping it afterwards. */
    private suspend fun withBridge(body: suspend CoroutineScope.(RecordingBridge, ServerTarget) -> Unit) =
        coroutineScope {
            val bridge = RecordingBridge()
            val server = bridge.start()
            try {
                body(bridge, ServerTarget(host = "127.0.0.1", port = bridge.port, secure = false))
            } finally {
                server.stop(gracePeriodMillis = 0, timeoutMillis = 2_000)
            }
        }

    /**
     * A minimal stand-in for `:bridge`'s session endpoint: it completes the 0004 handshake, answers a
     * `Login` with `CONNECTED` + a resume id (so the client reaches `Connected` exactly as it does
     * against the real bridge), and records the *sequence* of client frames and the socket close.
     */
    private class RecordingBridge {
        /** Frame kinds received, in order, with [CLOSED] appended when the socket goes away. */
        val log: MutableList<String> = CopyOnWriteArrayList()

        private val closed = CompletableDeferred<Unit>()

        @Volatile
        var port: Int = 0
            private set

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

        suspend fun awaitClosed() {
            withTimeout(CLOSE_TIMEOUT_MS) { closed.await() }
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
                        }

                        is Logout -> {
                            log += LOGOUT
                            // The real bridge evicts and lets the socket close; nothing is replied.
                        }

                        else -> log += message::class.simpleName.orEmpty()
                    }
                }
            } finally {
                log += CLOSED
                closed.complete(Unit)
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
        const val RESUME_ID = "resume-0046"

        const val HELLO = "hello"
        const val LOGIN = "login"
        const val LOGOUT = "logout"
        const val CLOSED = "closed"

        const val CONNECT_TIMEOUT_MS = 15_000L
        const val CLOSE_TIMEOUT_MS = 10_000L

        /** "Promptly" for the best-effort paths: far below any human-visible sign-out delay. */
        const val BEST_EFFORT_TIMEOUT_MS = 5_000L
    }
}
