package magefree.feature.connect

import magefree.model.ConnectionState
import magefree.model.ConnectionStatus

/**
 * The sign-in flow's UI-facing view of the live connection.
 *
 * The feature drives [magefree.network.ConnectionRepository.connect] and observes its
 * `StateFlow<ConnectionStatus>` (story 0019's detail-carrying seam) — a [ConnectionState] plus the
 * failure diagnostic. [ConnectPhase] re-groups those states into the surfaces the sign-in screen
 * shows, keeping [AuthFailed], [VersionUnsupported], and [Network] **distinct** — the story's error
 * taxonomy (first-class version mismatch; a network/timeout distinct from a login error) — rather than
 * collapsing them into a single generic error.
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

    /**
     * A transport/network failure (or timeout) ended the attempt — its own surface, distinct from a
     * login error. Maps from a [ConnectionState.Disconnected] [ConnectionStatus] that carries a
     * [magefree.model.ConnectionError].
     */
    Network,
    ;

    /** True while a connection attempt is in progress and the form/actions should be locked. */
    val isInProgress: Boolean get() = this == Connecting || this == Reconnecting
}

/**
 * Projects the repository's bare [ConnectionState] enum onto the flow's [ConnectPhase]. Total and
 * injective over the six states; retained for the status-only projection and its pure-logic test.
 */
fun ConnectionState.toConnectPhase(): ConnectPhase =
    when (this) {
        ConnectionState.Disconnected -> ConnectPhase.Idle
        ConnectionState.Connecting -> ConnectPhase.Connecting
        ConnectionState.Reconnecting -> ConnectPhase.Reconnecting
        ConnectionState.Connected -> ConnectPhase.Connected
        ConnectionState.AuthFailed -> ConnectPhase.AuthFailed
        ConnectionState.Unsupported -> ConnectPhase.VersionUnsupported
    }

/**
 * Projects the enriched [ConnectionStatus] onto the flow's [ConnectPhase]. Identical to the
 * [ConnectionState] projection except that a [ConnectionState.Disconnected] carrying a
 * [ConnectionStatus.error] surfaces as [ConnectPhase.Network] (a transport/timeout failure) rather
 * than [ConnectPhase.Idle] (a clean return to the form).
 */
fun ConnectionStatus.toConnectPhase(): ConnectPhase =
    when (state) {
        ConnectionState.Disconnected -> if (error != null) ConnectPhase.Network else ConnectPhase.Idle
        ConnectionState.Connecting -> ConnectPhase.Connecting
        ConnectionState.Reconnecting -> ConnectPhase.Reconnecting
        ConnectionState.Connected -> ConnectPhase.Connected
        ConnectionState.AuthFailed -> ConnectPhase.AuthFailed
        ConnectionState.Unsupported -> ConnectPhase.VersionUnsupported
    }
