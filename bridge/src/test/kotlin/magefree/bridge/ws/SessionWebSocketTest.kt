package magefree.bridge.ws

import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.receiveDeserialized
import io.ktor.client.plugins.websocket.sendSerialized
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.serialization.kotlinx.KotlinxWebsocketSerializationConverter
import io.ktor.websocket.Frame
import magefree.bridge.module
import magefree.bridge.testApplicationTimed
import magefree.protocol.ClientHello
import magefree.protocol.ClientMessage
import magefree.protocol.Ping
import magefree.protocol.Pong
import magefree.protocol.ProtocolError
import magefree.protocol.ProtocolErrorCode
import magefree.protocol.ProtocolJson
import magefree.protocol.ProtocolVersion
import magefree.protocol.ServerHello
import magefree.protocol.ServerMessage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class SessionWebSocketTest {
    private fun testWebSocket(block: suspend io.ktor.client.plugins.websocket.DefaultClientWebSocketSession.() -> Unit) =
        testApplicationTimed {
            application { module() }
            val wsClient =
                createClient {
                    install(WebSockets) {
                        contentConverter = KotlinxWebsocketSerializationConverter(ProtocolJson.json)
                    }
                }
            wsClient.webSocket("/${ProtocolVersion.PATH_SEGMENT}/session") { block() }
        }

    @Test
    fun `a valid ClientHello is answered with a ServerHello`() =
        testWebSocket {
            sendSerialized<ClientMessage>(
                ClientHello(protocolMajor = ProtocolVersion.MAJOR, protocolMinor = ProtocolVersion.MINOR),
            )

            val reply = receiveDeserialized<ServerMessage>()

            val serverHello = assertInstanceOf(ServerHello::class.java, reply)
            assertEquals(ProtocolVersion.MAJOR, serverHello.protocolMajor)
            assertEquals(ProtocolVersion.MINOR, serverHello.protocolMinor)
            assertEquals(BRIDGE_VERSION, serverHello.bridgeVersion)
        }

    @Test
    fun `a major-version mismatch yields PROTOCOL_VERSION_UNSUPPORTED`() =
        testWebSocket {
            sendSerialized<ClientMessage>(
                ClientHello(protocolMajor = ProtocolVersion.MAJOR + 1, protocolMinor = 0),
            )

            val reply = receiveDeserialized<ServerMessage>()

            val error = assertInstanceOf(ProtocolError::class.java, reply)
            assertEquals(ProtocolErrorCode.PROTOCOL_VERSION_UNSUPPORTED, error.code)
        }

    @Test
    fun `a Ping after the handshake yields a matching Pong`() =
        testWebSocket {
            sendSerialized<ClientMessage>(
                ClientHello(protocolMajor = ProtocolVersion.MAJOR, protocolMinor = ProtocolVersion.MINOR),
            )
            assertInstanceOf(ServerHello::class.java, receiveDeserialized<ServerMessage>())

            sendSerialized<ClientMessage>(Ping(nonce = "abc", requestId = "req-1"))

            val reply = receiveDeserialized<ServerMessage>()
            val pong = assertInstanceOf(Pong::class.java, reply)
            assertEquals("abc", pong.nonce)
            assertEquals("req-1", pong.requestId)
        }

    @Test
    fun `a malformed first frame yields MALFORMED_MESSAGE`() =
        testWebSocket {
            // Valid JSON, but an unknown discriminator: cannot deserialize into a ClientMessage.
            send(Frame.Text("""{"type":"not_a_real_message"}"""))

            val reply = receiveDeserialized<ServerMessage>()

            val error = assertInstanceOf(ProtocolError::class.java, reply)
            assertEquals(ProtocolErrorCode.MALFORMED_MESSAGE, error.code)
        }
}
