package magefree.bridge.mapping

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import mage.interfaces.callback.ClientCallback
import magefree.protocol.ServerMessage
import org.slf4j.LoggerFactory

/**
 * The mapping-side of the callback relay: turns a cold/hot [Flow] of raw [ClientCallback]s (published
 * by `BridgeMageClient` off the remoting thread) into a [Flow] of app-schema [ServerMessage]s by
 * running each through [CallbackMapper].
 *
 * Keeping this transform here — rather than in `magefree.bridge.session` — confines every
 * `ClientCallback`/`mage.view.*` reference to the `magefree.bridge.mapping` boundary: the session layer
 * only ever sees a `Flow<ServerMessage>`. Unmapped methods are logged once and dropped; the relay
 * never throws on an unknown push.
 */
public object CallbackRelay {
    private val logger = LoggerFactory.getLogger(CallbackRelay::class.java)

    /**
     * Maps [callbacks] to the server messages they translate to, dropping (and logging) any callback
     * whose method has no registered mapper. The decompress+map work runs on the collector's
     * coroutine/dispatcher — never on the remoting thread that produced the callback.
     */
    public fun relay(callbacks: Flow<ClientCallback>): Flow<ServerMessage> =
        callbacks.mapNotNull { callback ->
            CallbackMapper.map(callback)
                ?: run {
                    logger.debug("unmapped callback: {}", callback.method)
                    null
                }
        }
}
