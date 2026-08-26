package magefree.bridge.xmage

/**
 * A lifecycle event observed by [BridgeMageClient] on an XMage **remoting thread** and re-published
 * on [BridgeMageClient.events] for consumers to collect on their own coroutine/dispatcher.
 *
 * This is the thread-safe hand-off boundary: `SessionImpl` drives the [BridgeMageClient]
 * callbacks from JBoss-remoting threads, which must never touch a Ktor WebSocket directly. Instead
 * each callback becomes one of these immutable events, pushed into a buffered flow; the collector
 * (e.g. `XMageUpstreamSession`) turns them into protocol `SessionStatus` frames on the Ktor side.
 */
public sealed interface XMageClientEvent {
    /** `MageClient.connected(message)` fired — authenticated and connected. */
    public data class Connected(
        val message: String,
    ) : XMageClientEvent

    /**
     * `MageClient.disconnected(askToReconnect, keepMySessionActive)` fired. The upstream
     * connection-listener's reconnect path calls this with [askToReconnect]`=true` on an unexpected
     * drop; a clean `connectStop(false, false)` calls it with [askToReconnect]`=false`.
     */
    public data class Disconnected(
        val askToReconnect: Boolean,
        val keepMySessionActive: Boolean,
    ) : XMageClientEvent

    /** `MageClient.showMessage(message)` — an informational server message. */
    public data class ServerMessage(
        val message: String,
    ) : XMageClientEvent

    /** `MageClient.showError(message)` — a server-side error message. */
    public data class ServerError(
        val message: String,
    ) : XMageClientEvent

    /** `CallbackClient.onNewConnection()` — the callback channel was (re)established. */
    public data object NewConnection : XMageClientEvent
}
