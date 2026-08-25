package magefree.network.reconnect

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn

/**
 * The Android [AppLifecycleObserver], backed by `ProcessLifecycleOwner` — the whole-app (not
 * per-Activity) lifecycle, so it survives rotation and only signals when the app truly goes to the
 * background/foreground.
 *
 * [isForeground] is a cold `callbackFlow` that adds a [LifecycleEventObserver] and emits `true` on
 * `ON_START`, `false` on `ON_STOP`, seeding the current state so a late collector sees where the app
 * already is. `flowOn(mainDispatcher)` runs the whole producer (registration, seeding, and the
 * `awaitClose` removal) on the main thread — lifecycle observers must be added/removed there; this is
 * the one legitimate place a main dispatcher is named. The observer is removed on cancellation.
 */
class ProcessAppLifecycleObserver(
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : AppLifecycleObserver {
    override val isForeground: Flow<Boolean> =
        callbackFlow {
            val owner = ProcessLifecycleOwner.get()
            val observer =
                LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_START -> trySend(true)
                        Lifecycle.Event.ON_STOP -> trySend(false)
                        else -> Unit
                    }
                }
            // Seed the current state, then register; the producer runs on mainDispatcher (see flowOn).
            trySend(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
            owner.lifecycle.addObserver(observer)
            awaitClose { owner.lifecycle.removeObserver(observer) }
        }.flowOn(mainDispatcher).distinctUntilChanged().conflate()
}
