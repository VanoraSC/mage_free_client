package magefree.network.reconnect

import kotlinx.coroutines.CompletableDeferred
import magefree.protocol.GameTypeList
import magefree.protocol.RoomUserList
import magefree.protocol.ServerMessage
import magefree.protocol.TableActionResult
import magefree.protocol.TableCreated
import magefree.protocol.TableList
import java.util.concurrent.ConcurrentHashMap

/**
 * The thread-safe correlation registry that multiplexes **request/response** over the single live
 * session socket (story 0028), alongside the session-event/push stream the [SessionRelay] already
 * relays. A `BridgeClient.request` registers a waiter keyed by its `requestId`; the relay's inbound
 * loop routes a matching correlated reply to that waiter (via [tryComplete]) instead of mapping it to a
 * `SessionEvent`; and an ended/dropped session fails every outstanding waiter ([failAll]) so a blocked
 * requester surfaces the failure as state rather than hanging.
 *
 * The correlated replies are the 0027 lobby list replies ([TableList]/[RoomUserList]/[GameTypeList])
 * and the 0036 table-action replies ([TableCreated]/[TableActionResult], story 0037), each keyed by its
 * echoed `requestId`. Resume flow-control frames, session-status pushes, and the spontaneous 0036 table
 * *events* (no `requestId` — routed to the push side-channel instead) are left untouched, so the existing
 * session-event behaviour (stories 0016/0024) is unchanged.
 */
internal class PendingRequests {
    private val waiters = ConcurrentHashMap<String, CompletableDeferred<ServerMessage>>()

    /**
     * Register a waiter for [requestId] and return its [CompletableDeferred]. The caller sends the
     * request on the socket, then awaits this (with a timeout). If two requests ever share an id the
     * later one wins; ids are UUIDs in practice, so collisions do not occur.
     */
    fun register(requestId: String): CompletableDeferred<ServerMessage> {
        val deferred = CompletableDeferred<ServerMessage>()
        waiters[requestId] = deferred
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
            else -> null
        }
}
