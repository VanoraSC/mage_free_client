package magefree.network.fake

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import magefree.model.ConnectionState
import magefree.model.Credentials
import magefree.model.ServerTarget
import magefree.model.SessionEvent
import magefree.network.BridgeClient
import magefree.network.ServerPushSource
import magefree.network.concurrent.ConcurrentList
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
) : BridgeClient,
    ServerPushSource {
    private val _connectionState = MutableStateFlow(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    /**
     * The server-push side-channel (story 0037's seam (b)) mirrored for tests: a test drives an
     * uncorrelated 0036 table event by [emitPush]-ing a real `:protocol` [ServerMessage] here, exactly as
     * the production relay's `featurePush` would. Replay-less like production — a test subscribes its
     * `observeTable` collector before emitting.
     */
    private val _serverPushes = MutableSharedFlow<ServerMessage>(replay = 0, extraBufferCapacity = 64)
    override val serverPushes: SharedFlow<ServerMessage> = _serverPushes.asSharedFlow()

    /** Emit a spontaneous server push, as the bridge relay's feature side-channel would. */
    suspend fun emitPush(message: ServerMessage) {
        _serverPushes.emit(message)
    }

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
        teardowns += Teardown.DISCONNECT
        _connectionState.value = ConnectionState.Disconnected
    }

    /**
     * Mirrors the production client's deliberate sign-out (story 0046): the real
     * [magefree.network.ktor.KtorBridgeClient] sends `Logout` before closing, which a socket-less fake
     * cannot do — so it records the *intent* instead. Tests over this fake assert which teardown a
     * caller chose; that `signOut` actually puts a `Logout` on the wire is pinned against a real socket
     * by `KtorBridgeClientSignOutTest`.
     */
    override suspend fun signOut() {
        teardowns += Teardown.SIGN_OUT
        _connectionState.value = ConnectionState.Disconnected
    }

    /** The teardown entry points a caller can choose between (story 0046). */
    enum class Teardown {
        /** [disconnect] — close without signalling intent; the bridge parks the session. */
        DISCONNECT,

        /** [signOut] — deliberate exit; the bridge tears the upstream session down now. */
        SIGN_OUT,
    }

    /** Every teardown this client was asked for, in order — the fake's record of caller intent. */
    val teardowns: MutableList<Teardown> = ConcurrentList()

    private fun SessionEvent.isTerminal(): Boolean =
        this is SessionEvent.AuthFailed ||
            this is SessionEvent.VersionUnsupported ||
            this is SessionEvent.Disconnected ||
            this is SessionEvent.Error
}
