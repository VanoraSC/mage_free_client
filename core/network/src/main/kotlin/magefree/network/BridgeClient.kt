package magefree.network

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import magefree.model.ConnectionState
import magefree.model.Credentials
import magefree.model.ServerTarget
import magefree.model.SessionEvent

/**
 * The seam the rest of the app depends on for talking to the bridge. The Ktor WebSocket
 * implementation ([magefree.network.ktor.KtorBridgeClient]) and the test double
 * ([magefree.network.fake.FakeBridgeClient]) are interchangeable behind it (fakes over mocks).
 *
 * A `BridgeClient` speaks the `:protocol` contract on the wire but only ever exposes mapped
 * `:core:model` types ([SessionEvent], [ConnectionState]) — the wire shapes never escape the
 * implementation's mapper boundary.
 */
interface BridgeClient {
    /**
     * The current connection state, hot and always readable. Mirrors the latest emitted
     * [SessionEvent]'s [SessionEvent.connectionState]; starts at [ConnectionState.Disconnected].
     */
    val connectionState: StateFlow<ConnectionState>

    /**
     * Open a session against [server] as [credentials]: perform the `:protocol` handshake, send
     * `Login`, and emit mapped [SessionEvent]s until the session ends or the flow is cancelled.
     * The returned flow is cold — collecting it starts a fresh connection attempt. Reconnection is
     * handled internally and surfaces as [SessionEvent.Reconnecting].
     */
    fun connect(
        server: ServerTarget,
        credentials: Credentials,
    ): Flow<SessionEvent>

    /** Tear down the active session, if any. */
    suspend fun disconnect()

    /**
     * Perform one **request/response** exchange over the *live* session socket (story 0028): send
     * [message] and suspend until the correlated reply carrying [requestId] arrives, then return it.
     * The exchange is multiplexed with the session-event/push stream on the same socket.
     *
     * The signature is intentionally **wire-agnostic** — `message` and the `ReplyT` reply are erased
     * `Any` here so no `:protocol` type appears on this interface's ABI (modules above `:core:network`,
     * e.g. `:feature:*`, implement `BridgeClient` without seeing `:protocol`). Callers *inside*
     * `:core:network` (the `LobbyClient`) supply the concrete `:protocol` request and reply types; the
     * implementation sends `message` as a `ClientMessage` and returns the matching `ServerMessage`.
     *
     * Throws if there is no active session, if the reply does not arrive before the implementation's
     * timeout, or if the session drops while awaiting it — the caller (the repository) surfaces that as
     * error state, never propagating it as an unhandled throw.
     */
    suspend fun <ReplyT : Any> request(
        message: Any,
        requestId: String,
    ): ReplyT
}
