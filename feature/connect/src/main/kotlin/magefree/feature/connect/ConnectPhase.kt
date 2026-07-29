package magefree.feature.connect

import magefree.model.ConnectionState

/**
 * The sign-in flow's UI-facing view of the live [ConnectionState].
 *
 * The feature drives [magefree.network.ConnectionRepository.connect] and observes its
 * `StateFlow<ConnectionState>`; that enum is the *only* connection signal the repository seam
 * exposes (it maps every [magefree.model.SessionEvent] down to its [ConnectionState] before it leaves
 * `:core:network`). [ConnectPhase] re-groups those states into the surfaces the sign-in screen shows,
 * keeping [AuthFailed] and [VersionUnsupported] **distinct** — the story's first-class version-mismatch
 * requirement — rather than collapsing both into a generic error.
 */
enum class ConnectPhase {
    /** No attempt in flight: show the credential form. Maps from [ConnectionState.Disconnected]. */
    Idle,

    /** A first connection attempt is opening. Maps from [ConnectionState.Connecting]. */
    Connecting,

    /** A previously-established session dropped and is being restored. Maps from [ConnectionState.Reconnecting]. */
    Reconnecting,

    /** Authenticated and connected; hand off to the shell. Maps from [ConnectionState.Connected]. */
    Connected,

    /** The server rejected the credentials. Its own surface, distinct from [VersionUnsupported]. */
    AuthFailed,

    /** The server/protocol version is incompatible. First-class surface. Maps from [ConnectionState.Unsupported]. */
    VersionUnsupported,
    ;

    /** True while a connection attempt is in progress and the form/actions should be locked. */
    val isInProgress: Boolean get() = this == Connecting || this == Reconnecting
}

/** Projects the repository's [ConnectionState] enum onto the flow's [ConnectPhase]. */
fun ConnectionState.toConnectPhase(): ConnectPhase =
    when (this) {
        ConnectionState.Disconnected -> ConnectPhase.Idle
        ConnectionState.Connecting -> ConnectPhase.Connecting
        ConnectionState.Reconnecting -> ConnectPhase.Reconnecting
        ConnectionState.Connected -> ConnectPhase.Connected
        ConnectionState.AuthFailed -> ConnectPhase.AuthFailed
        ConnectionState.Unsupported -> ConnectPhase.VersionUnsupported
    }
