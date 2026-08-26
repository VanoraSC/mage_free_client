package magefree.feature.connect

import magefree.model.ConnectionState
import magefree.model.ConnectionStatus

/**
 * The sign-in flow's UI-facing view of the live connection.
 *
 * The feature drives [magefree.network.ConnectionRepository.connect] and observes its
 * `StateFlow<ConnectionStatus>` (the detail-carrying seam) — a [ConnectionState] plus the
 * failure diagnostic. [ConnectPhase] re-groups those states into the surfaces the sign-in screen
 * shows, keeping [AuthFailed], [VersionUnsupported], and [Network] **distinct** — the error
 * taxonomy (first-class version mismatch; a network/timeout distinct from a login error) — rather than
 * collapsing them into a single generic error.
 */
enum class ConnectPhase {
    /** No attempt in flight: show the credential form. Maps from [ConnectionState.Disconnected]. */
    Idle,

    /** A first connection attempt is opening. Maps from [ConnectionState.Connecting]. */
    Connecting,

    /**
     * A previously-established session dropped and the client is still trying to reach the bridge.
     * Maps from [ConnectionState.Reconnecting]. Non-destructive: the current screen is preserved.
     */
    Reconnecting,

    /**
     * The socket is back and the held session is being re-attached (resume in progress) — visually
     * distinct from [Reconnecting] and a fresh [Connecting]. Maps from [ConnectionState.Restoring].
     * Non-destructive: the current screen is preserved.
     */
    Restoring,

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

    /**
     * Recovery has truly failed — the reconnect/resume budget was exhausted (or the parked session
     * was rejected and re-authentication is required). A terminal surface, distinct from a clean return
     * to the form ([Idle]) and from a first-connect transport failure ([Network]): it offers a
     * re-authenticate CTA that routes back to sign-in with the last server pre-selected. Maps from a
     * [ConnectionState.Disconnected] [ConnectionStatus] that carries a reason [ConnectionStatus.detail]
     * (but no transport [ConnectionStatus.error]).
     */
    SessionLost,
    ;

    /** True while a connection attempt or recovery is in progress and the form/actions should be locked. */
    val isInProgress: Boolean get() = this == Connecting || this == Reconnecting || this == Restoring
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
        ConnectionState.Restoring -> ConnectPhase.Restoring
        ConnectionState.Connected -> ConnectPhase.Connected
        ConnectionState.AuthFailed -> ConnectPhase.AuthFailed
        ConnectionState.Unsupported -> ConnectPhase.VersionUnsupported
    }

/**
 * Projects the enriched [ConnectionStatus] onto the flow's [ConnectPhase]. Identical to the
 * [ConnectionState] projection except for how a [ConnectionState.Disconnected] is disambiguated by its
 * diagnostic:
 * - a transport [ConnectionStatus.error] → [ConnectPhase.Network] (a first-connect network/timeout);
 * - otherwise a non-blank [ConnectionStatus.detail] (a reason: recovery exhausted, or a relayed
 *   disconnect) → the terminal [ConnectPhase.SessionLost];
 * - no detail (a clean close / no active session) → [ConnectPhase.Idle] (return to the form).
 */
fun ConnectionStatus.toConnectPhase(): ConnectPhase =
    when (state) {
        ConnectionState.Disconnected ->
            when {
                error != null -> ConnectPhase.Network
                !detail.isNullOrBlank() -> ConnectPhase.SessionLost
                else -> ConnectPhase.Idle
            }
        ConnectionState.Connecting -> ConnectPhase.Connecting
        ConnectionState.Reconnecting -> ConnectPhase.Reconnecting
        ConnectionState.Restoring -> ConnectPhase.Restoring
        ConnectionState.Connected -> ConnectPhase.Connected
        ConnectionState.AuthFailed -> ConnectPhase.AuthFailed
        ConnectionState.Unsupported -> ConnectPhase.VersionUnsupported
    }
