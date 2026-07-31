package magefree.bridge.mapping

import mage.interfaces.callback.ClientCallback
import mage.interfaces.callback.ClientCallbackMethod
import mage.view.ChatMessage
import magefree.protocol.ServerMessage
import org.slf4j.LoggerFactory

/**
 * The generic server→client push mapper: turns a raw [ClientCallback] into an app-schema
 * [ServerMessage], or `null` when no mapper is registered for the callback's method. This — together
 * with the per-feature mappers it dispatches to (e.g. [ChatMessageMapper]) — is the **single coupling
 * surface** where `mage.view.*` payloads are read; nothing outside `magefree.bridge.mapping` sees them.
 *
 * [map] is the only place a callback's compressed payload is decompressed and cast. Downstream epics
 * register more `when` cases (lobby, deck, game). Mapping **never throws** — so a new upstream push
 * cannot crash the session (story 0006 + 0026 F5):
 * - an **unmapped** method returns `null` (the relay logs and drops it);
 * - a **known** method whose payload fails to decompress or is not the expected `mage.view.*` shape
 *   (upstream drift) is logged and returns `null` too, rather than letting the throw propagate and
 *   evict the [magefree.bridge.session.LiveSession].
 */
public object CallbackMapper {
    private val logger = LoggerFactory.getLogger(CallbackMapper::class.java)

    /**
     * Dispatches [callback] by its [ClientCallbackMethod]:
     * - [ClientCallbackMethod.CHATMESSAGE] → [ChatMessageMapper] over the decompressed [ChatMessage].
     * - anything else → `null` (the caller logs "unmapped callback: <method>" and drops it).
     *
     * The decompress + cast for a known method is guarded: a malformed payload is logged and mapped to
     * `null`, never thrown.
     */
    public fun map(callback: ClientCallback): ServerMessage? =
        when (callback.method) {
            ClientCallbackMethod.CHATMESSAGE ->
                mapGuarded(callback) { ChatMessageMapper.map(it.chatMessage()) }
            else -> null
        }

    /** Runs [transform], turning any malformed-payload failure into a logged `null` (never a throw). */
    private inline fun mapGuarded(
        callback: ClientCallback,
        transform: (ClientCallback) -> ServerMessage?,
    ): ServerMessage? =
        try {
            transform(callback)
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            logger.warn("Dropping malformed {} callback payload: {}", callback.method, failure.toString())
            null
        }

    /** Decompresses the payload and returns it as a [ChatMessage] (throws if the shape drifted). */
    private fun ClientCallback.chatMessage(): ChatMessage {
        // The payload is compressed until decompressed; getData() returns the mage.view.* object after.
        decompressData()
        return data as ChatMessage
    }
}
