package magefree.network.reconnect

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import magefree.model.SessionEvent

/**
 * The resume-aware, back-off/connectivity/lifecycle-aware reconnect loop (story 0024).
 *
 * Wraps a [SessionRunner] in a cold `channelFlow` that:
 * - emits [SessionEvent.Connecting] for the first attempt and [SessionEvent.Reconnecting] before each
 *   subsequent one, so a recovery surfaces as `Reconnecting` (not a bare `Disconnected`) through the
 *   `connectionState`/`connectionStatus` seam;
 * - stops on a terminal outcome ([SessionOutcome.TERMINAL]) or an explicit disconnect
 *   ([SessionOutcome.CLEAN_CLOSE] → a clean [SessionEvent.Disconnected]);
 * - on an unexpected drop ([SessionOutcome.RETRY]) waits a [ReconnectPolicy] back-off before retrying,
 *   carrying the [ResumeHandle] forward so the runner can `Resume` the parked session;
 * - **cuts a waiting back-off short** when connectivity returns or the app is refocused, and while
 *   backgrounded **relaxes** to waiting for the foreground instead of spinning attempts (the bridge
 *   holds the session per story 0023).
 *
 * The single [ResumeHandle] lives for the life of the flow, so a `resumeId` captured in one attempt is
 * offered to the next; it is not tied to any Activity, so rotation (which never cancels this flow — it
 * is collected on the repository's `@ApplicationScope`) is transparent.
 */
class ReconnectingSession(
    private val policy: ReconnectPolicy,
    private val connectivity: ConnectivityObserver,
    private val lifecycle: AppLifecycleObserver,
    private val isDisconnectRequested: () -> Boolean,
    private val runner: SessionRunner,
) {
    fun events(): Flow<SessionEvent> =
        channelFlow {
            val handle = ResumeHandle()
            // Mirror the observer flows into local StateFlows so the loop can read the current value and
            // detect a return edge. These collectors are cancelled when the loop ends so the channelFlow
            // completes (an in-producer `stateIn` would leave a non-completing child behind).
            val online = MutableStateFlow(true)
            val foreground = MutableStateFlow(true)
            val onlineJob = launch { connectivity.isOnline.collect { online.value = it } }
            val foregroundJob = launch { lifecycle.isForeground.collect { foreground.value = it } }

            try {
                var attempt = 0
                while (isActive && !isDisconnectRequested()) {
                    if (attempt == 0) send(SessionEvent.Connecting)

                    val outcome = runner.runOnce(handle) { send(it) }
                    if (outcome == SessionOutcome.TERMINAL) break
                    if (outcome == SessionOutcome.CLEAN_CLOSE) {
                        send(SessionEvent.Disconnected())
                        break
                    }

                    // SessionOutcome.RETRY: the socket dropped unexpectedly.
                    if (isDisconnectRequested()) break
                    attempt++
                    val max = policy.maxAttempts
                    if (max != null && attempt > max) {
                        send(SessionEvent.Disconnected("reconnect attempts exhausted"))
                        break
                    }
                    // Surface Reconnecting for the whole recovery window (not a stale Connected/Disconnected).
                    send(SessionEvent.Reconnecting)
                    awaitReconnectWindow(policy.delayForAttempt(attempt), online, foreground)
                }
            } finally {
                onlineJob.cancel()
                foregroundJob.cancel()
            }
        }

    /**
     * Wait before the next reconnect attempt. Foregrounded: a bounded [delayMillis] back-off, cut short
     * the moment connectivity returns or the app is refocused. Backgrounded: relax — wait quietly for a
     * return to the foreground (the bridge is holding the session) rather than retrying on battery.
     */
    private suspend fun awaitReconnectWindow(
        delayMillis: Long,
        online: StateFlow<Boolean>,
        foreground: StateFlow<Boolean>,
    ) {
        if (!foreground.value) {
            foreground.first { it }
            return
        }
        withTimeoutOrNull(delayMillis) {
            merge(online.risingEdge(), foreground.risingEdge()).first()
        }
    }

    /**
     * A `false → true` transition of a boolean [StateFlow] (connectivity/foreground *returning*). A
     * `StateFlow` already suppresses consecutive duplicates, so no `distinctUntilChanged` is needed:
     * drop the leading `true`(s), then wait for the next `true` (which can only follow a `false`).
     */
    private fun StateFlow<Boolean>.risingEdge(): Flow<Unit> =
        dropWhile { it }
            .filter { it }
            .map { }
}
