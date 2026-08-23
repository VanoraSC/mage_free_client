package magefree.model

/**
 * The app-owned model of how the client relates to the game server.
 *
 * This is the canonical, wire-agnostic connection vocabulary for the whole app. It is deliberately
 * **independent of any bridge/`:protocol` type**: `:core:network` maps the real session state
 * (`magefree.protocol.SessionStateCode`) *into* this model at its mapper boundary, so nothing above
 * `:core:network` ever imports a wire shape.
 *
 * The states mirror story 0010's app-side `magefree.app.connection.ConnectionState` one-for-one
 * (same names, same order) so story 0017 can consolidate the shell onto this type without a
 * behavioural change.
 */
enum class ConnectionState {
    /** No session; the app is not connected to a server. */
    Disconnected,

    /** A connection attempt is in progress (first connect). */
    Connecting,

    /** Connected and authenticated; play is possible. */
    Connected,

    /** A previously-established session dropped and is being re-established. */
    Reconnecting,

    /**
     * The socket is back and the held session is being re-attached — a `Resume` was sent and its ack
     * is awaited (story 0023/0024). Distinct from [Reconnecting] (still reaching the bridge) so the UI
     * can show a "restoring your session" state in the moment before [Connected].
     */
    Restoring,

    /** The server rejected authentication (bad credentials / session). */
    AuthFailed,

    /** The server/protocol version is incompatible with this client. */
    Unsupported,
}
