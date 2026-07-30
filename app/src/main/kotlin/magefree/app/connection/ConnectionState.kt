package magefree.app.connection

/**
 * The app-owned model of how the client relates to the game server, surfaced by the shell's
 * connection-status indicator.
 *
 * This is deliberately **independent of any bridge/protocol type**: it conceptually mirrors the
 * bridge's session states (story 0005 `SessionStateCode`), but EPIC-04 maps the real session state
 * *into* this app model rather than the UI importing wire shapes. Keeping the type app-local means
 * the shell and its tests have no dependency on the bridge module.
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

    /** The socket is back and the held session is being re-attached (resume in progress). */
    Restoring,

    /** The server rejected authentication (bad credentials / session). */
    AuthFailed,

    /** The server/protocol version is incompatible with this client. */
    Unsupported,
}
