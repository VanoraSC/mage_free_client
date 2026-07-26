package magefree.bridge.mapping

import mage.interfaces.callback.ClientCallback
import mage.interfaces.callback.ClientCallbackMethod
import mage.view.ChatMessage
import magefree.protocol.ServerMessage

/**
 * The generic server→client push mapper: turns a raw [ClientCallback] into an app-schema
 * [ServerMessage], or `null` when no mapper is registered for the callback's method. This — together
 * with the per-feature mappers it dispatches to (e.g. [ChatMessageMapper]) — is the **single coupling
 * surface** where `mage.view.*` payloads are read; nothing outside `magefree.bridge.mapping` sees them.
 *
 * [map] is the only place a callback's compressed payload is decompressed and cast. Downstream epics
 * register more `when` cases (lobby, deck, game); an **unmapped** method returns `null` and the relay
 * logs and drops it — mapping never throws on an unknown method, so a new upstream push cannot crash
 * the bridge.
 */
public object CallbackMapper {
    /**
     * Decompresses [callback] and dispatches by its [ClientCallbackMethod]:
     * - [ClientCallbackMethod.CHATMESSAGE] → [ChatMessageMapper] over the [ChatMessage] payload.
     * - anything else → `null` (the caller logs "unmapped callback: <method>" and drops it).
     */
    public fun map(callback: ClientCallback): ServerMessage? {
        // The payload is compressed until decompressed; getData() returns the mage.view.* object after.
        callback.decompressData()
        return when (callback.method) {
            ClientCallbackMethod.CHATMESSAGE -> ChatMessageMapper.map(callback.data as ChatMessage)
            else -> null
        }
    }
}
