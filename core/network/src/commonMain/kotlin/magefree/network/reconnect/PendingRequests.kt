package magefree.network.reconnect

import kotlinx.coroutines.CompletableDeferred
import magefree.network.concurrent.ConcurrentMap
import magefree.protocol.GameActionResult
import magefree.protocol.GameStateSnapshot
import magefree.protocol.GameStateUnavailable
import magefree.protocol.GameTypeList
import magefree.protocol.RoomUserList
import magefree.protocol.ServerMessage
import magefree.protocol.TableActionResult
import magefree.protocol.TableCreated
import magefree.protocol.TableDetail
import magefree.protocol.TableList
import magefree.protocol.TableNotFound

/**
 * The thread-safe correlation registry that multiplexes **request/response** over the single live
 * session socket, alongside the session-event/push stream the [SessionRelay] already
 * relays. A `BridgeClient.request` registers a waiter keyed by its `requestId`; the relay's inbound
 * loop routes a matching correlated reply to that waiter (via [tryComplete]) instead of mapping it to a
 * `SessionEvent`; and an ended/dropped session fails every outstanding waiter ([failAll]) so a blocked
 * requester surfaces the failure as state rather than hanging.
 *
 * The correlated replies are the 0027 lobby list replies ([TableList]/[RoomUserList]/[GameTypeList]),
 * the 0036 table-action replies ([TableCreated]/[TableActionResult]), the targeted
 * table-read replies ([TableDetail]/[TableNotFound]), 0051's game-action replies
 * ([GameActionResult] - every join/watch/quit and every prompt answer) and the targeted
 * game-state read replies ([GameStateSnapshot]/[GameStateUnavailable]) — each keyed by its
 * echoed `requestId`. Resume flow-control frames, session-status pushes, and the spontaneous 0036 table
 * and 0051 game *events* (no `requestId` — routed to the push side-channel instead) are left untouched,
 * so the existing session-event behaviour is unchanged.
 */
internal class PendingRequests {
    private val waiters = ConcurrentMap<String, CompletableDeferred<ServerMessage>>()

    /**
     * Register a waiter for [requestId] and return its [CompletableDeferred]. The caller sends the
     * request on the socket, then awaits this (with a timeout). If two requests ever share an id the
     * later one wins; ids are UUIDs in practice, so collisions do not occur.
     */
    fun register(requestId: String): CompletableDeferred<ServerMessage> {
        val deferred = CompletableDeferred<ServerMessage>()
        waiters.put(requestId, deferred)
        return deferred
    }

    /** Drop the waiter for [requestId] (on timeout/cancellation) so the map does not leak. */
    fun forget(requestId: String) {
        waiters.remove(requestId)
    }

    /**
     * If [message] is a correlated lobby reply whose `requestId` matches an outstanding waiter, complete
     * that waiter and return `true` (the relay then skips session mapping for it). Returns `false` for
     * any other message — including a lobby reply with no waiter (a late/duplicate reply is dropped).
     */
    fun tryComplete(message: ServerMessage): Boolean {
        val id = message.correlationId() ?: return false
        val deferred = waiters.remove(id) ?: return false
        return deferred.complete(message)
    }

    /** Fail every outstanding waiter with [cause] (the socket ended/dropped) and clear the registry. */
    fun failAll(cause: Throwable) {
        val outstanding = waiters.values.toList()
        waiters.clear()
        outstanding.forEach { it.completeExceptionally(cause) }
    }

    private fun ServerMessage.correlationId(): String? =
        when (this) {
            is TableList -> requestId
            is RoomUserList -> requestId
            is GameTypeList -> requestId
            is TableCreated -> requestId
            is TableActionResult -> requestId
            is TableDetail -> requestId
            is TableNotFound -> requestId
            // every game verb's reply — without this the game client's `request` would wait
            // out its timeout on a reply that did in fact arrive, which is the shape of defect this
            // registry exists to prevent.
            is GameActionResult -> requestId
            // the game-state read's two arms. **Both** are correlated: an uncorrelated
            // "no state" would leave the reconnecting board blocked on a waiter until it timed out —
            // indistinguishable from the bridge never answering, which is the very failure the read
            // exists to remove.
            is GameStateSnapshot -> requestId
            is GameStateUnavailable -> requestId
            else -> null
        }
}
