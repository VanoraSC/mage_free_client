package magefree.network.reconnect

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
 *   holds the session per story 0023);
 * - **ends a running attempt as soon as connectivity is lost** (story 0050 defect B), instead of
 *   reporting `Connected` over a dead radio until the socket read times out.
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

                    // Track whether this session actually reached Connected so a later independent drop
                    // restarts the back-off from the initial delay rather than a permanently-grown one.
                    var reachedConnected = false
                    val outcome =
                        runWhileOnline(online) {
                            runner.runOnce(handle) { event ->
                                if (event is SessionEvent.Connected) reachedConnected = true
                                send(event)
                            }
                        }
                    if (outcome == SessionOutcome.TERMINAL) break
                    if (outcome == SessionOutcome.CLEAN_CLOSE) {
                        send(SessionEvent.Disconnected())
                        break
                    }

                    // SessionOutcome.RETRY: the socket dropped unexpectedly.
                    if (isDisconnectRequested()) break
                    // F2: a successful connection resets the counter, so back-off does not climb
                    // unbounded across independent drop→recover cycles.
                    if (reachedConnected) attempt = 0
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
     * Runs one session attempt, **ending it the moment connectivity is lost** (story 0050 defect B).
     *
     * Without this the loop only learned about a dead radio when the socket read eventually failed —
     * which, when the network vanishes without a FIN, is a TCP timeout away. Until then the app went on
     * reporting `Connected` over a link that no longer existed, and the bridge went on holding a session
     * bound to a socket that would never speak again. Cancelling here does two useful things at once: the
     * status surface tells the truth immediately, and the transport gets its one best-effort chance to
     * close the socket while the interface is still up.
     *
     * The outcome is an ordinary [SessionOutcome.RETRY] — a lost network is a transport drop, so 0023's
     * park and 0024's resume still apply and the caller's back-off already waits for connectivity to
     * return. A cancellation of the *loop itself* (the collector going away) is re-thrown, not swallowed.
     */
    private suspend fun runWhileOnline(
        online: StateFlow<Boolean>,
        attempt: suspend () -> SessionOutcome,
    ): SessionOutcome =
        coroutineScope {
            val running = async { attempt() }
            val watchdog =
                launch {
                    // A *falling* edge only. A leading `false` (the observer catching up, or a connect
                    // attempted while it believes there is no network) must not cancel an attempt that
                    // may be succeeding — if there really is no network the socket fails on its own.
                    online.fallingEdge().first()
                    running.cancel(CancellationException("connectivity lost"))
                }
            try {
                running.await()
            } catch (cancellation: CancellationException) {
                if (!isActive) throw cancellation
                SessionOutcome.RETRY
            } finally {
                watchdog.cancel()
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

    /**
     * The mirror of [risingEdge]: a `true → false` transition (connectivity being *lost*). The leading
     * `false`(s) are dropped so only a network that goes away **while a session is running** counts.
     */
    private fun StateFlow<Boolean>.fallingEdge(): Flow<Unit> =
        dropWhile { !it }
            .filter { !it }
            .map { }
}
