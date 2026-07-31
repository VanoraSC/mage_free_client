package magefree.network.fake

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import magefree.model.ConnectionState
import magefree.model.Credentials
import magefree.model.ServerTarget
import magefree.model.SessionEvent
import magefree.network.BridgeClient
import magefree.network.mapper.SessionMapper
import magefree.network.mapper.SessionMapper.handshakeResult
import magefree.protocol.ClientMessage
import magefree.protocol.ProtocolVersion
import magefree.protocol.ServerHello
import magefree.protocol.ServerMessage

/**
 * A [BridgeClient] test double that replays a scripted sequence of **real `:protocol`
 * [ServerMessage]s** (fakes are recordings — not invented shapes) through the production
 * [SessionMapper]. Because it shares the mapper and terminal-state logic with
 * [magefree.network.ktor.KtorBridgeClient], a test over the fake exercises the same mapping and
 * client state machine, hermetically and with no live bridge.
 *
 * On [connect] it emits an initial [SessionEvent.Connecting] (the socket-opening phase), applies the
 * [serverHello] handshake (a mismatched major short-circuits to [SessionEvent.VersionUnsupported]),
 * then maps each message in [script] in order, stopping at the first terminal event.
 */
class FakeBridgeClient(
    private val script: List<ServerMessage> = emptyList(),
    private val serverHello: ServerHello =
        ServerHello(ProtocolVersion.MAJOR, ProtocolVersion.MINOR, "fake-bridge"),
    private val responder: (ClientMessage) -> ServerMessage = {
        error("FakeBridgeClient: no request/response scripted for $it")
    },
) : BridgeClient {
    private val _connectionState = MutableStateFlow(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    override fun connect(
        server: ServerTarget,
        credentials: Credentials,
    ): Flow<SessionEvent> =
        flow {
            emit(SessionEvent.Connecting)
            handshakeResult(serverHello)?.let {
                emit(it)
                return@flow
            }
            for (message in script) {
                val event =
                    SessionMapper.toSessionEvent(
                        message = message,
                        target = server,
                        username = credentials.username,
                        bridgeVersion = serverHello.bridgeVersion,
                    ) ?: continue
                emit(event)
                if (event.isTerminal()) break
            }
        }.onEach { _connectionState.value = it.connectionState }

    /**
     * Replay a scripted reply for a request/response exchange (story 0028). The [responder] maps the
     * sent [ClientMessage] to its [ServerMessage] reply; the default throws so a test that does not
     * script requests is unaffected. Mirrors the real client's correlation without a socket.
     */
    override suspend fun <ReplyT : Any> request(
        message: Any,
        requestId: String,
    ): ReplyT {
        val reply = responder(message as ClientMessage)
        @Suppress("UNCHECKED_CAST")
        return reply as ReplyT
    }

    override suspend fun disconnect() {
        _connectionState.value = ConnectionState.Disconnected
    }

    private fun SessionEvent.isTerminal(): Boolean =
        this is SessionEvent.AuthFailed ||
            this is SessionEvent.VersionUnsupported ||
            this is SessionEvent.Disconnected ||
            this is SessionEvent.Error
}
