package magefree.bridge.session

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mage.remote.MageVersionException
import magefree.bridge.mapping.CallbackRelay
import magefree.bridge.xmage.XMageClientEvent
import magefree.bridge.xmage.XMageConnection
import magefree.bridge.xmage.XMageSession
import magefree.protocol.ServerInfo
import magefree.protocol.ServerMessage
import magefree.protocol.SessionStateCode
import magefree.protocol.SessionStatus
import org.slf4j.LoggerFactory

/**
 * The real [UpstreamSession] over story 0003's `XMageSession`/`BridgeMageClient`/`SessionImpl`.
 *
 * It builds a `Connection` to the bridge's **pinned** [target], runs `connectStart` on
 * `Dispatchers.IO` (inside `XMageSession.connect`), and translates the lifecycle into
 * [SessionStatus] frames:
 *
 * | upstream signal                                   | status               |
 * |---------------------------------------------------|----------------------|
 * | before connect                                    | `CONNECTING`         |
 * | `connectStart` → true (`connected(...)` fired)    | `CONNECTED`          |
 * | `connectStart` → false                            | `AUTH_FAILED`        |
 * | `MageVersionException` (see note)                 | `VERSION_UNSUPPORTED` |
 * | `disconnected(askToReconnect=true, ...)`          | `RECONNECTING`       |
 * | `disconnected(askToReconnect=false, ...)`         | `DISCONNECTED`       |
 *
 * **Thread hand-off.** Remoting-thread callbacks are captured by `BridgeMageClient` and re-published
 * on its `events` [kotlinx.coroutines.flow.SharedFlow]; the `relay` coroutine here collects them and
 * turns them into frames via the `channelFlow` `send`, which is the only place the Ktor coroutine is
 * touched — a remoting thread never sends a WebSocket frame.
 *
 * **Version-mismatch note.** The baked `mage-common:1.4.60` `SessionImpl.connectStart` *catches*
 * `MageVersionException` internally and returns `false`, so a real version gap presents as
 * `AUTH_FAILED` and the `VERSION_UNSUPPORTED` arm below is defensive (unreachable with this upstream).
 * `MageVersionException` also exposes neither version — it only formats a message — so the arm reports
 * the bridge's own version and `server=unknown`. Under the pinned-server posture a gap cannot occur;
 * the exact `"server=<v> bridge=<v>"` contract is proven hermetically by `FakeUpstreamSession`.
 */
public class XMageUpstreamSession(
    private val target: UpstreamTarget,
    private val sessionFactory: () -> XMageSession = { XMageSession() },
) : UpstreamSession {
    private val logger = LoggerFactory.getLogger(XMageUpstreamSession::class.java)

    @Volatile
    private var current: XMageSession? = null

    override fun connect(credentials: Credentials): Flow<ServerMessage> =
        channelFlow {
            val session = sessionFactory()
            current = session

            // Collect the handed-off remoting events and relay drops/reconnects as status frames.
            // send(...) here runs on the flow's collecting coroutine, never on a remoting thread.
            val relay =
                launch {
                    session.client.events.collect { event ->
                        when (event) {
                            is XMageClientEvent.Disconnected ->
                                if (event.askToReconnect) {
                                    send(SessionStatus(SessionStateCode.RECONNECTING))
                                } else {
                                    send(SessionStatus(SessionStateCode.DISCONNECTED))
                                    close()
                                }
                            else -> Unit // Connected handled inline; messages/errors carry no status here.
                        }
                    }
                }

            // Relay mapped server pushes (chat, etc.) onto the SAME outbound stream (story 0006). The
            // raw callbacks are handed off by BridgeMageClient off the remoting thread; CallbackRelay
            // does the decompress+map on this coroutine and the ClientCallback/mage.view.* references
            // stay entirely inside magefree.bridge.mapping — this layer only ever sees ServerMessage.
            val callbackRelay =
                launch {
                    CallbackRelay.relay(session.client.callbacks).collect { message ->
                        send(message)
                    }
                }

            try {
                send(SessionStatus(SessionStateCode.CONNECTING))

                val connection =
                    XMageConnection.build(
                        host = target.host,
                        port = target.port,
                        username = credentials.username,
                        password = credentials.password ?: "",
                    )

                val connected =
                    try {
                        session.connect(connection)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (versionMismatch: MageVersionException) {
                        logger.warn("Upstream version mismatch", versionMismatch)
                        send(
                            SessionStatus(
                                state = SessionStateCode.VERSION_UNSUPPORTED,
                                message = "server=unknown bridge=${session.client.getVersion()}",
                            ),
                        )
                        session.disconnect()
                        return@channelFlow
                    }

                if (connected) {
                    send(SessionStatus(SessionStateCode.CONNECTED))
                    // Stay hot: relay later RECONNECTING/DISCONNECTED until the collector cancels
                    // (socket close / Logout) or the relay closes the channel on a clean drop.
                    awaitClose()
                } else {
                    send(SessionStatus(SessionStateCode.AUTH_FAILED))
                    session.disconnect()
                }
            } finally {
                relay.cancel()
                callbackRelay.cancel()
            }
        }

    override suspend fun serverInfo(): ServerInfo? {
        val session = current ?: return null
        if (!session.isConnected) return null
        return withContext(Dispatchers.IO) {
            ServerInfo(
                serverVersion = session.versionInfo(),
                mainRoomId = session.mainRoomId()?.toString(),
            )
        }
    }

    override suspend fun disconnect() {
        current?.disconnect()
    }
}
