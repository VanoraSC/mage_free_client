package magefree.model

/**
 * A mapped session lifecycle event emitted by a `BridgeClient`.
 *
 * This is the domain-side output of `:core:network`'s mapper: every `:protocol` `SessionStatus`
 * (and the local handshake result) is translated into one of these before it leaves the network
 * module. Each event carries its corresponding [connectionState] so a caller can drive a
 * `StateFlow<ConnectionState>` (or the story-0010 status surface) without re-deriving it.
 *
 * [VersionUnsupported] is intentionally its own event — a client/server version mismatch is a
 * first-class outcome, never folded into a generic error.
 */
sealed interface SessionEvent {
    /** The [ConnectionState] this event represents. */
    val connectionState: ConnectionState

    /** The bridge has begun opening the session but is not yet authenticated. */
    data object Connecting : SessionEvent {
        override val connectionState: ConnectionState get() = ConnectionState.Connecting
    }

    /** Authenticated and connected; carries the established [session] handle. */
    data class Connected(
        val session: Session,
    ) : SessionEvent {
        override val connectionState: ConnectionState get() = ConnectionState.Connected
    }

    /** A previously-established session dropped and a reconnect is in progress. */
    data object Reconnecting : SessionEvent {
        override val connectionState: ConnectionState get() = ConnectionState.Reconnecting
    }

    /** The server rejected the credentials; [message] is an optional human-readable detail. */
    data class AuthFailed(
        val message: String? = null,
    ) : SessionEvent {
        override val connectionState: ConnectionState get() = ConnectionState.AuthFailed
    }

    /**
     * The client/server protocol or server version is incompatible. [detail] carries the wire
     * diagnostic (e.g. `"server=<v> bridge=<v>"`). First-class — never a generic error.
     */
    data class VersionUnsupported(
        val detail: String? = null,
    ) : SessionEvent {
        override val connectionState: ConnectionState get() = ConnectionState.Unsupported
    }

    /** The session ended and is not being restored; [message] is an optional reason. */
    data class Disconnected(
        val message: String? = null,
    ) : SessionEvent {
        override val connectionState: ConnectionState get() = ConnectionState.Disconnected
    }

    /**
     * A transport/protocol failure ended the session. Reported distinctly from a clean
     * [Disconnected] but still resolves to [ConnectionState.Disconnected] for the UI.
     */
    data class Error(
        val error: ConnectionError,
    ) : SessionEvent {
        override val connectionState: ConnectionState get() = ConnectionState.Disconnected
    }
}
