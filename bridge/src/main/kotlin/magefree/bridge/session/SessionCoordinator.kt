package magefree.bridge.session

import io.ktor.server.websocket.WebSocketServerSession
import io.ktor.server.websocket.receiveDeserialized
import io.ktor.server.websocket.sendSerialized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import magefree.protocol.ClientMessage
import magefree.protocol.GetServerInfo
import magefree.protocol.Login
import magefree.protocol.Logout
import magefree.protocol.Ping
import magefree.protocol.Pong
import magefree.protocol.ProtocolError
import magefree.protocol.ProtocolErrorCode
import magefree.protocol.Resume
import magefree.protocol.ResumeRejected
import magefree.protocol.ServerInfo
import magefree.protocol.ServerMessage
import magefree.protocol.SessionResumable
import magefree.protocol.SessionStateCode
import magefree.protocol.SessionStatus
import org.slf4j.LoggerFactory

/**
 * Per-socket orchestration between the app-facing WebSocket and one upstream XMage session (stories
 * 0005 + 0023). It assumes the 0004 handshake already completed (a `ServerHello` was sent) and then
 * runs the post-handshake message loop:
 *
 * - `Ping` → `Pong` (liveness continues to work alongside a session).
 * - The first `Login` creates a [LiveSession] via the [registry] (its outbound pump runs on the
 *   registry's bridge-level scope, **not** this socket coroutine) and starts a **forwarder** that
 *   streams every `SessionStatus`/relayed push to the socket, stamping the login's `requestId` onto
 *   the first status. On the first `CONNECTED` it registers the session and emits a
 *   [SessionResumable] carrying the bridge-issued **resume id**.
 * - `Resume(resumeId)` (in place of `Login`, on a fresh handshaken socket) looks the id up: on a hit
 *   it acks with [SessionResumable] and **re-binds** the outbound stream to this socket — no second
 *   upstream connect/login; on a miss it replies [ResumeRejected].
 * - `Logout` evicts (and cleanly `disconnect()`s) the session immediately.
 * - `GetServerInfo` replies with `ServerInfo` correlated by `requestId` (story 0006).
 * - A second `Login`/`Resume` while a session is bound is ignored (documented choice) with a log line.
 * - Any other/malformed frame → a non-terminal `ProtocolError(UNKNOWN_MESSAGE_TYPE)`.
 *
 * **Teardown (changed in 0023).** On socket close *without* a `Logout`, a live registered session is
 * **parked** (`registry.park`) rather than disconnected, so a transient app-network drop no longer
 * loses the game; a `Logout` (handled inline) still disconnects immediately, and a login that never
 * reached `CONNECTED` is simply disconnected. The chosen teardown path runs under [NonCancellable].
 */
public class SessionCoordinator(
    private val registry: SessionRegistry,
    private val newUpstream: () -> UpstreamSession,
) {
    private val logger = LoggerFactory.getLogger(SessionCoordinator::class.java)

    /** The session currently bound to this socket, plus the fields the forwarder/teardown share. */
    private class Bound(
        val live: LiveSession,
    ) {
        /** The resume id, set once the session registers on its first `CONNECTED`. */
        @Volatile var resumeId: String? = null

        /** Set by the forwarder when the outbound channel closes — the upstream session ended. */
        @Volatile var ended: Boolean = false

        @Volatile var forwarder: Job? = null
    }

    /** Drives the post-handshake loop for [ws] until the socket closes or the coroutine is cancelled. */
    public suspend fun run(ws: WebSocketServerSession) {
        coroutineScope {
            var bound: Bound? = null
            try {
                while (true) {
                    val message =
                        try {
                            ws.receiveDeserialized<ClientMessage>()
                        } catch (closed: ClosedReceiveChannelException) {
                            break
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (failure: Exception) {
                            ws.sendSerialized<ServerMessage>(
                                ProtocolError(
                                    ProtocolErrorCode.UNKNOWN_MESSAGE_TYPE,
                                    "Unrecognised or malformed message.",
                                ),
                            )
                            continue
                        }

                    when (message) {
                        is Ping ->
                            ws.sendSerialized<ServerMessage>(Pong(nonce = message.nonce, requestId = message.requestId))

                        is Login ->
                            if (bound != null) {
                                logger.warn("Ignoring Login while a session is already bound to this socket.")
                            } else {
                                val live =
                                    registry.createSession(
                                        newUpstream(),
                                        Credentials(message.username, message.password),
                                    )
                                val newBound = Bound(live)
                                newBound.forwarder =
                                    startForwarder(ws, newBound, firstRequestId = message.requestId, alreadyRegistered = false)
                                bound = newBound
                            }

                        is Resume ->
                            if (bound != null) {
                                logger.warn("Ignoring Resume while a session is already bound to this socket.")
                                ws.sendSerialized<ServerMessage>(
                                    ResumeRejected(
                                        reason = "a session is already active on this socket",
                                        requestId = message.requestId,
                                    ),
                                )
                            } else {
                                val live = registry.resume(message.resumeId)
                                if (live == null) {
                                    ws.sendSerialized<ServerMessage>(
                                        ResumeRejected(
                                            reason = "unknown or expired resume handle",
                                            requestId = message.requestId,
                                        ),
                                    )
                                } else {
                                    val newBound = Bound(live)
                                    newBound.resumeId = message.resumeId
                                    // Positive ack, then re-bind the still-live outbound stream to this socket.
                                    ws.sendSerialized<ServerMessage>(
                                        SessionResumable(resumeId = message.resumeId, requestId = message.requestId),
                                    )
                                    newBound.forwarder =
                                        startForwarder(ws, newBound, firstRequestId = null, alreadyRegistered = true)
                                    bound = newBound
                                }
                            }

                        is Logout -> {
                            val closing = bound
                            bound = null
                            closing?.forwarder?.cancelAndJoin()
                            val id = closing?.resumeId
                            if (id != null) {
                                registry.evict(id)
                            } else {
                                closing?.live?.close()
                            }
                        }

                        is GetServerInfo -> {
                            val info =
                                bound?.live?.serverInfo()
                                    ?: ServerInfo(serverVersion = "unavailable", mainRoomId = null)
                            ws.sendSerialized<ServerMessage>(info.copy(requestId = message.requestId))
                        }

                        else ->
                            ws.sendSerialized<ServerMessage>(
                                ProtocolError(
                                    ProtocolErrorCode.UNKNOWN_MESSAGE_TYPE,
                                    "Unhandled message after handshake.",
                                ),
                            )
                    }
                }
            } finally {
                // Park (keep alive) on an unexpected close; disconnect only an unrecoverable/dead one.
                // A Logout is handled inline and leaves `bound` null, so it never reaches this branch.
                withContext(NonCancellable) {
                    val closing = bound
                    if (closing != null) {
                        closing.forwarder?.cancelAndJoin()
                        val id = closing.resumeId
                        when {
                            id == null -> closing.live.close() // never reached CONNECTED → not resumable
                            closing.ended -> registry.evict(id) // upstream ended → drop the entry
                            else -> registry.park(id) // healthy live session, socket dropped → PARK
                        }
                    }
                }
            }
        }
    }

    /**
     * Launches the outbound forwarder: consumes the [Bound.live] durable channel and relays each
     * frame to [ws]. For a fresh login it stamps [firstRequestId] onto the first status and, on the
     * first `CONNECTED`, registers the session and emits its [SessionResumable]. When the channel
     * closes (upstream ended) it flags [Bound.ended]; a socket-write failure ends it quietly (the read
     * loop's break drives teardown). Cancellation (socket drop) leaves the channel open so the session
     * can be parked and later re-bound.
     */
    private fun CoroutineScope.startForwarder(
        ws: WebSocketServerSession,
        bound: Bound,
        firstRequestId: String?,
        alreadyRegistered: Boolean,
    ): Job =
        launch {
            var first = !alreadyRegistered
            try {
                for (message in bound.live.messages) {
                    val framed =
                        if (first && message is SessionStatus) {
                            message.copy(requestId = firstRequestId)
                        } else {
                            message
                        }
                    first = false
                    ws.sendSerialized<ServerMessage>(framed)

                    if (bound.resumeId == null &&
                        message is SessionStatus &&
                        message.state == SessionStateCode.CONNECTED
                    ) {
                        val id = registry.register(bound.live)
                        bound.resumeId = id
                        ws.sendSerialized<ServerMessage>(SessionResumable(resumeId = id))
                    }
                }
                // Channel closed by the pump → the upstream session ended (clean drop / death).
                bound.ended = true
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                // Socket write failed (peer gone). Stop forwarding; teardown will park/evict.
                logger.debug("Outbound forwarder stopped: {}", failure.message)
            }
        }
}
