package magefree.bridge.xmage

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import mage.interfaces.MageClient
import mage.interfaces.callback.ClientCallback
import mage.utils.MageVersion
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * The bridge's implementation of XMage's [MageClient] callback sink — the object `SessionImpl`
 * drives during connect/auth and for the lifetime of a connection.
 *
 * Responsibilities in story 0003 are deliberately minimal: report a [MageVersion] derived from the
 * baked `mage-common` (so the mandatory version handshake matches the server), and *record* the
 * connection lifecycle and any callbacks that arrive. It performs **no decoding** of callback
 * payloads and does **no** `mage.view.*` mapping — those are stories 0004–0006. `onCallback` only
 * counts the callback and remembers the last method for observability.
 *
 * **Thread hand-off (story 0005).** `SessionImpl` invokes these callbacks on JBoss-remoting threads.
 * In addition to recording state, each lifecycle callback is re-published as an [XMageClientEvent] on
 * [events] — a buffered, non-suspending [MutableSharedFlow] — so a coroutine consumer
 * (`XMageUpstreamSession`) can translate them into protocol frames without ever being called on, or
 * blocking, a remoting thread. Emission uses `tryEmit`, which never blocks the caller.
 */
public class BridgeMageClient : MageClient {
    private val logger = LoggerFactory.getLogger(BridgeMageClient::class.java)

    @Volatile
    private var connectedFlag: Boolean = false
    private val lastConnectedMessageRef = AtomicReference<String?>(null)
    private val callbackCounter = AtomicInteger(0)
    private val lastCallbackMethodRef = AtomicReference<String?>(null)

    // Replay=0 (only live transitions matter); a generous buffer + DROP_OLDEST keeps tryEmit
    // non-blocking on the remoting thread even if no consumer is attached yet. A collector that
    // subscribes before connect() (as XMageUpstreamSession does) sees every transition.
    private val eventFlow =
        MutableSharedFlow<XMageClientEvent>(
            replay = 0,
            extraBufferCapacity = 64,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    /** Lifecycle events re-published from the remoting-thread callbacks; see the class KDoc. */
    public val events: SharedFlow<XMageClientEvent>
        get() = eventFlow

    /** True between a [connected] and the following [disconnected] callback. */
    public val isConnected: Boolean
        get() = connectedFlag

    /** The message passed to the most recent [connected] callback, or null if never connected. */
    public val lastConnectedMessage: String?
        get() = lastConnectedMessageRef.get()

    /** Number of [onCallback] invocations observed so far. */
    public val callbackCount: Int
        get() = callbackCounter.get()

    /** Name of the [ClientCallback] method most recently observed, or null if none yet. */
    public val lastCallbackMethod: String?
        get() = lastCallbackMethodRef.get()

    /**
     * The client version. Derived from the baked `mage-common` via `MageVersion(Class)`, so it
     * equals the server's version by construction and satisfies `connectStart`'s mandatory version
     * handshake. Do not hand-roll a version here.
     */
    override fun getVersion(): MageVersion = MageVersion(BridgeMageClient::class.java)

    override fun connected(message: String) {
        connectedFlag = true
        lastConnectedMessageRef.set(message)
        logger.info("XMage session connected: {}", message)
        eventFlow.tryEmit(XMageClientEvent.Connected(message))
    }

    override fun disconnected(
        askToReconnect: Boolean,
        keepMySessionActive: Boolean,
    ) {
        connectedFlag = false
        logger.info(
            "XMage session disconnected (askToReconnect={}, keepMySessionActive={})",
            askToReconnect,
            keepMySessionActive,
        )
        eventFlow.tryEmit(XMageClientEvent.Disconnected(askToReconnect, keepMySessionActive))
    }

    override fun showMessage(message: String) {
        logger.info("XMage server message: {}", message)
        eventFlow.tryEmit(XMageClientEvent.ServerMessage(message))
    }

    override fun showError(message: String) {
        logger.warn("XMage server error: {}", message)
        eventFlow.tryEmit(XMageClientEvent.ServerError(message))
    }

    override fun onNewConnection() {
        logger.info("XMage callback channel established (onNewConnection).")
        eventFlow.tryEmit(XMageClientEvent.NewConnection)
    }

    /**
     * Records that a server callback arrived. No decoding: `ClientCallback.getData()` is compressed
     * until `decompressData()`, which is left to story 0006. Here we only count callbacks and note
     * the method for observability.
     */
    override fun onCallback(callback: ClientCallback) {
        val method = callback.method?.name
        callbackCounter.incrementAndGet()
        lastCallbackMethodRef.set(method)
        logger.debug("XMage callback received: method={}", method)
    }
}
