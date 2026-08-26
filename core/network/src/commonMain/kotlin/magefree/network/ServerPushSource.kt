package magefree.network

import kotlinx.coroutines.flow.SharedFlow
import magefree.protocol.ServerMessage

/**
 * The **internal** side-channel of spontaneous, *uncorrelated* server-pushed frames (the seam
 * (b)). Where a `BridgeClient.request` reply is routed to its waiter by [magefree.network.reconnect.PendingRequests]
 * and a session-lifecycle frame becomes a `SessionEvent`, a server *push* that is neither — a 0036 table
 * event ([magefree.protocol.TableUpdated]/[magefree.protocol.ConstructPrompt]/…) — was previously dropped
 * by the relay. The relay now forwards those frames here, and the table layer folds the ones for its
 * table into [magefree.network.table.TableState].
 *
 * It is **deliberately `internal`**: it exposes a `Flow<ServerMessage>` (a `:protocol` type), so it must
 * never reach a module above `:core:network`. The public [BridgeClient]/`TableClient` ABIs stay
 * `:protocol`-free (the 0028 discipline); this hook is consumed only inside `:core:network` (the DI
 * provider hands the same [BridgeClient] singleton, cast to this, to the table client).
 *
 * The stream is hot and replay-less: a subscriber sees pushes emitted after it subscribes. That matches
 * the table subscription model — `observeTable` seeds current state, then folds subsequent pushes; a
 * reconnect re-syncs by re-emitting the held state rather than replaying the stream.
 */
internal interface ServerPushSource {
    /** The hot stream of uncorrelated server-pushed frames the relay could not route or map. */
    val serverPushes: SharedFlow<ServerMessage>
}
