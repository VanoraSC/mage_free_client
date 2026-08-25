package magefree.network.reconnect

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Observes device network reachability so the reconnect loop can wake a waiting back-off the instant
 * connectivity returns instead of sleeping out the full delay (story 0024).
 *
 * [isOnline] is a hot `Flow<Boolean>` — `true` when a usable network is available, `false` when lost.
 * The Android implementation ([AndroidConnectivityObserver]) is backed by
 * `ConnectivityManager.NetworkCallback`; tests use a fake driving the flow directly.
 */
interface ConnectivityObserver {
    /** Emits the current reachability and every subsequent change. */
    val isOnline: Flow<Boolean>
}

/**
 * A [ConnectivityObserver] that reports "always online" and never changes — the safe default when no
 * platform observer is wired (e.g. a directly-constructed `KtorBridgeClient` in a JVM/unit context).
 * With it, the reconnect loop simply falls back to pure timed back-off (no connectivity kicks).
 */
object AlwaysOnlineConnectivityObserver : ConnectivityObserver {
    override val isOnline: Flow<Boolean> = flowOf(true)
}
