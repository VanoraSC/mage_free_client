package magefree.network.reconnect

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A driveable [ConnectivityObserver] for hermetic reconnect tests: [set] flips reachability so a test
 * can simulate the network dropping and returning and assert the loop's back-off is kicked.
 */
class FakeConnectivityObserver(
    initial: Boolean = true,
) : ConnectivityObserver {
    private val state = MutableStateFlow(initial)
    override val isOnline: Flow<Boolean> = state.asStateFlow()

    fun set(online: Boolean) {
        state.value = online
    }
}

/**
 * A driveable [AppLifecycleObserver] for hermetic reconnect tests: [set] flips foreground/background so
 * a test can simulate the app being backgrounded and refocused.
 */
class FakeAppLifecycleObserver(
    initial: Boolean = true,
) : AppLifecycleObserver {
    private val state = MutableStateFlow(initial)
    override val isForeground: Flow<Boolean> = state.asStateFlow()

    fun set(foreground: Boolean) {
        state.value = foreground
    }
}
