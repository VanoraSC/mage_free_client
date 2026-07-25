package magefree.bridge.ws

import io.ktor.server.routing.Route
import io.ktor.server.websocket.receiveDeserialized
import io.ktor.server.websocket.sendSerialized
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import magefree.protocol.ClientHello
import magefree.protocol.ClientMessage
import magefree.protocol.Ping
import magefree.protocol.Pong
import magefree.protocol.ProtocolError
import magefree.protocol.ProtocolErrorCode
import magefree.protocol.ProtocolVersion
import magefree.protocol.ServerHello
import magefree.protocol.ServerMessage

/** Bridge build string reported in the [ServerHello]. Domain-agnostic; bumped as the bridge ships. */
public const val BRIDGE_VERSION: String = "0.1.0"

/**
 * Registers the `/v1/session` WebSocket — the app-facing session transport (protocol major = the
 * `v1` path segment). This story implements framing only: the version handshake, app-level
 * ping/pong liveness, and the protocol-error/close behaviour. XMage/session wiring arrives in 0005.
 *
 * Handshake and message handling follow the rules documented in the `:protocol` module.
 */
public fun Route.sessionWebSocket(bridgeVersion: String = BRIDGE_VERSION) {
    webSocket("/${ProtocolVersion.PATH_SEGMENT}/session") {
        // 1. First frame must be a ClientHello. Any deserialization failure is a malformed envelope.
        val hello =
            try {
                receiveDeserialized<ClientMessage>()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                sendSerialized<ServerMessage>(
                    ProtocolError(ProtocolErrorCode.MALFORMED_MESSAGE, "Expected a client_hello frame."),
                )
                close(CloseReason(CloseReason.Codes.PROTOCOL_ERROR, "malformed handshake"))
                return@webSocket
            }

        if (hello !is ClientHello) {
            sendSerialized<ServerMessage>(
                ProtocolError(ProtocolErrorCode.MALFORMED_MESSAGE, "First frame must be a client_hello."),
            )
            close(CloseReason(CloseReason.Codes.PROTOCOL_ERROR, "malformed handshake"))
            return@webSocket
        }

        // 2. Major version must match exactly; a mismatch is fatal. Minor differences are tolerated.
        if (hello.protocolMajor != ProtocolVersion.MAJOR) {
            sendSerialized<ServerMessage>(
                ProtocolError(
                    ProtocolErrorCode.PROTOCOL_VERSION_UNSUPPORTED,
                    "Unsupported protocol major ${hello.protocolMajor}; bridge speaks ${ProtocolVersion.MAJOR}.",
                ),
            )
            close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "protocol version unsupported"))
            return@webSocket
        }

        // 3. Compatible: reply with the bridge's version.
        sendSerialized<ServerMessage>(
            ServerHello(
                protocolMajor = ProtocolVersion.MAJOR,
                protocolMinor = ProtocolVersion.MINOR,
                bridgeVersion = bridgeVersion,
            ),
        )

        // 4. Post-handshake message loop: Ping -> Pong; anything else -> UNKNOWN_MESSAGE_TYPE (no close).
        while (true) {
            val message =
                try {
                    receiveDeserialized<ClientMessage>()
                } catch (closed: ClosedReceiveChannelException) {
                    // The peer closed the connection; end the loop cleanly.
                    break
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    sendSerialized<ServerMessage>(
                        ProtocolError(ProtocolErrorCode.UNKNOWN_MESSAGE_TYPE, "Unrecognised or malformed message."),
                    )
                    continue
                }

            when (message) {
                is Ping ->
                    sendSerialized<ServerMessage>(Pong(nonce = message.nonce, requestId = message.requestId))
                else ->
                    sendSerialized<ServerMessage>(
                        ProtocolError(ProtocolErrorCode.UNKNOWN_MESSAGE_TYPE, "Unhandled message after handshake."),
                    )
            }
        }
    }
}
