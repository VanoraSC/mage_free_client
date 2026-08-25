package magefree.network.reconnect

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Observes whole-app foreground/background transitions so the reconnect loop can nudge a
 * reconnect/health-check when the app returns to the foreground and relax its retry cadence while
 * backgrounded (story 0024). The bridge holds the session across a background stretch (story 0023), so
 * a quiet app resumes on return rather than spending battery on aggressive retries.
 *
 * [isForeground] is a hot `Flow<Boolean>` — `true` from `ON_START`, `false` from `ON_STOP`. The Android
 * implementation ([ProcessAppLifecycleObserver]) is backed by `ProcessLifecycleOwner`; tests use a fake.
 */
interface AppLifecycleObserver {
    /** Emits the current foreground state and every subsequent change. */
    val isForeground: Flow<Boolean>
}

/**
 * An [AppLifecycleObserver] that reports "always foreground" and never changes — the safe default when
 * no platform observer is wired (e.g. a directly-constructed `KtorBridgeClient` in a JVM/unit context).
 * With it, the reconnect loop always uses its normal foreground back-off cadence.
 */
object AlwaysForegroundAppLifecycleObserver : AppLifecycleObserver {
    override val isForeground: Flow<Boolean> = flowOf(true)
}
