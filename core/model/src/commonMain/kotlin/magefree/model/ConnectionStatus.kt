package magefree.model

/**
 * A connection [state] enriched with the failure **detail** that produced it.
 *
 * This is the detail-carrying companion to the bare [ConnectionState]. the repository seam
 * reduces every [SessionEvent] to a [ConnectionState] — lossless for the status bar, but
 * it drops the diagnostic a *failure* carries: an [SessionEvent.AuthFailed] message, the server/bridge
 * versions on a [SessionEvent.VersionUnsupported], or the transport reason behind a
 * [SessionEvent.Error]. [ConnectionStatus] carries that diagnostic through the seam so the sign-in UI
 * can render distinct, detailed failure surfaces (invalid credentials / version-unsupported /
 * network-timeout) instead of a single generic error.
 *
 * - [state] is the categorical signal — identical to what [ConnectionState] would report, so the two
 *   flows stay consistent.
 * - [detail] is an optional human-readable string (e.g. `"server=<v> bridge=<v>"`, an auth message,
 *   or a transport reason), shown when present.
 * - [error] is the structured [ConnectionError] for a transport/protocol fault, present only when one
 *   occurred; a non-null [error] at [ConnectionState.Disconnected] is how the UI tells a network
 *   failure apart from a clean disconnect.
 *
 * Never carries a credential or secret — only diagnostics safe to display.
 */
data class ConnectionStatus(
    val state: ConnectionState,
    val detail: String? = null,
    val error: ConnectionError? = null,
) {
    companion object {
        /** The idle, no-session status. */
        val Disconnected: ConnectionStatus = ConnectionStatus(ConnectionState.Disconnected)
    }
}
