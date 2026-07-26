package magefree.bridge.session

import io.ktor.server.websocket.WebSocketServerSession
import io.ktor.server.websocket.receiveDeserialized
import io.ktor.server.websocket.sendSerialized
import kotlinx.coroutines.CancellationException
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
import magefree.protocol.ServerInfo
import magefree.protocol.ServerMessage
import magefree.protocol.SessionStatus
import org.slf4j.LoggerFactory

/**
 * Per-socket orchestration between the app-facing WebSocket and one [upstream] XMage session (story
 * 0005). It assumes the 0004 handshake already completed (a `ServerHello` was sent) and then runs the
 * post-handshake message loop:
 *
 * - `Ping` → `Pong` (liveness continues to work alongside a session).
 * - The first `Login` launches `upstream.connect(...)` and forwards every `SessionStatus` to the
 *   socket, stamping the login's `requestId` onto the first emission.
 * - `Logout` (or socket close/cancel) disconnects the upstream and cancels the forwarding.
 * - `GetServerInfo` replies with a `ServerInfo` (upstream version + main room id) correlated by
 *   `requestId` — the generic client→server request/response shape (story 0006).
 * - Relayed server pushes (e.g. `ChatEvent`, story 0006) arrive on the same outbound stream as
 *   `SessionStatus` and are forwarded to the socket without a `requestId`.
 * - A second `Login` while a session is active is **ignored** (documented choice) with a log line —
 *   the app should await the in-flight status stream or `Logout` first. After a session ends
 *   (e.g. `AUTH_FAILED`), a fresh `Login` is accepted again.
 * - Any other/malformed frame → a non-terminal `ProtocolError(UNKNOWN_MESSAGE_TYPE)`.
 *
 * All work is tied to the WebSocket via structured concurrency; teardown disconnects the upstream
 * under [NonCancellable] so cleanup runs even when the socket coroutine is cancelled.
 */
public class SessionCoordinator(
    private val upstream: UpstreamSession,
) {
    private val logger = LoggerFactory.getLogger(SessionCoordinator::class.java)

    /** Drives the post-handshake loop for [ws] until the socket closes or the coroutine is cancelled. */
    public suspend fun run(ws: WebSocketServerSession) {
        coroutineScope {
            var loginJob: Job? = null
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
                            if (loginJob?.isActive == true) {
                                logger.warn("Ignoring Login while a session is already active.")
                            } else {
                                loginJob =
                                    launch {
                                        var first = true
                                        upstream
                                            .connect(Credentials(message.username, message.password))
                                            .collect { outbound ->
                                                // Stamp the login's requestId onto the first status only
                                                // (0005 behavior); relayed pushes (e.g. ChatEvent) and
                                                // later statuses pass through untouched.
                                                val framed =
                                                    if (first && outbound is SessionStatus) {
                                                        outbound.copy(requestId = message.requestId)
                                                    } else {
                                                        outbound
                                                    }
                                                first = false
                                                ws.sendSerialized<ServerMessage>(framed)
                                            }
                                    }
                            }

                        is Logout -> {
                            upstream.disconnect()
                            loginJob?.cancelAndJoin()
                            loginJob = null
                        }

                        is GetServerInfo -> {
                            // Client→server request/response, correlated by requestId. Sources the
                            // version + main room id from the upstream (getServerState().version /
                            // getMainRoomId()); replies with a best-effort placeholder if not connected.
                            val info =
                                upstream.serverInfo()
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
                loginJob?.cancel()
                // Tear the upstream down even if the socket coroutine is being cancelled.
                withContext(NonCancellable) { upstream.disconnect() }
            }
        }
    }
}
