package magefree.network.reconnect

import app.cash.turbine.test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import magefree.model.ServerTarget
import magefree.model.Session
import magefree.model.SessionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Turbine coverage of the story-0024 reconnect loop over a **fake [SessionRunner] + fake connectivity/
 * lifecycle**, exercising every path hermetically: an unexpected drop auto-reconnects (surfacing
 * `Reconnecting`) and carries the resume handle forward; a terminal outcome and an explicit disconnect
 * stop the loop; a connectivity return and a foreground refocus cut a waiting back-off short; a
 * backgrounded app relaxes to waiting for the foreground; and `maxAttempts` exhaustion gives up.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReconnectingSessionTest {
    private val target = ServerTarget(host = "bridge.local", port = 9000)
    private val connected = SessionEvent.Connected(Session(target, "pete"))

    /** A [SessionRunner] driven by a list of per-invocation steps; records the handle each attempt saw. */
    private class ScriptedRunner(
        private val steps: List<suspend (ResumeHandle, suspend (SessionEvent) -> Unit) -> SessionOutcome>,
    ) : SessionRunner {
        val handlesSeen = mutableListOf<String?>()

        override suspend fun runOnce(
            handle: ResumeHandle,
            emit: suspend (SessionEvent) -> Unit,
        ): SessionOutcome {
            handlesSeen += handle.resumeId
            val index = (handlesSeen.size - 1).coerceAtMost(steps.size - 1)
            return steps[index](handle, emit)
        }
    }

    private fun session(
        runner: SessionRunner,
        policy: ReconnectPolicy = ReconnectPolicy(initialDelayMillis = 1_000, jitterRatio = 0.0),
        connectivity: ConnectivityObserver = FakeConnectivityObserver(),
        lifecycle: AppLifecycleObserver = FakeAppLifecycleObserver(),
        isDisconnectRequested: () -> Boolean = { false },
    ) = ReconnectingSession(policy, connectivity, lifecycle, isDisconnectRequested, runner)

    @Test
    fun dropReconnectsResumesAndSurfacesReconnecting() =
        runTest {
            val runner =
                ScriptedRunner(
                    listOf(
                        // First connect: reach Connected, capture a resume handle, then drop.
                        { handle, emit ->
                            emit(connected)
                            handle.resumeId = "r1"
                            SessionOutcome.RETRY
                        },
                        // Reconnect: should be offered the handle (real runner would Resume), then connect.
                        { _, emit ->
                            emit(connected)
                            SessionOutcome.TERMINAL
                        },
                    ),
                )

            session(runner).events().test {
                assertEquals(SessionEvent.Connecting, awaitItem())
                assertTrue(awaitItem() is SessionEvent.Connected)
                assertEquals(SessionEvent.Reconnecting, awaitItem())
                assertTrue(awaitItem() is SessionEvent.Connected)
                awaitComplete()
            }

            // The handle captured in attempt 0 was carried into attempt 1 (Resume, not a fresh Login).
            assertEquals(listOf(null, "r1"), runner.handlesSeen)
        }

    @Test
    fun backoffRestartsFromInitialDelayAfterEachSuccessfulReconnect() =
        runTest {
            // Plain geometric growth, no jitter: delayForAttempt(1)=1000ms, delayForAttempt(2)=2000ms.
            // Foreground+online stay true (default fakes) so no rising edge cuts the wait short — each
            // back-off elapses in full on the virtual clock, letting us measure it.
            val policy =
                ReconnectPolicy(initialDelayMillis = 1_000, multiplier = 2.0, maxDelayMillis = 60_000, jitterRatio = 0.0)
            val runner =
                ScriptedRunner(
                    listOf(
                        // Cycle 1: connect, then an unexpected drop.
                        { _, emit ->
                            emit(connected)
                            SessionOutcome.RETRY
                        },
                        // Cycle 2: reconnect succeeds (resets back-off), then a second independent drop.
                        { _, emit ->
                            emit(connected)
                            SessionOutcome.RETRY
                        },
                        // Cycle 3: reconnect succeeds and the loop ends.
                        { _, emit ->
                            emit(connected)
                            SessionOutcome.TERMINAL
                        },
                    ),
                )

            session(runner, policy = policy).events().test {
                assertEquals(SessionEvent.Connecting, awaitItem())
                assertTrue(awaitItem() is SessionEvent.Connected) // cycle 1 connect
                assertEquals(SessionEvent.Reconnecting, awaitItem()) // drop 1 → waits delayForAttempt(1)=1000
                assertTrue(awaitItem() is SessionEvent.Connected) // cycle 2 connect
                assertEquals(SessionEvent.Reconnecting, awaitItem()) // drop 2 → with reset, again 1000 (not 2000)
                assertTrue(awaitItem() is SessionEvent.Connected) // cycle 3 connect
                awaitComplete()
            }

            // Two recovery waits of the initial 1000ms each = 2000ms. Without the reset the second would
            // have grown to 2000ms, totalling 3000ms — so this pins the back-off reset.
            assertEquals(2_000L, testScheduler.currentTime)
        }

    @Test
    fun terminalOutcomeStopsTheLoop() =
        runTest {
            val runner =
                ScriptedRunner(
                    listOf { _, emit ->
                        emit(SessionEvent.AuthFailed("bad creds"))
                        SessionOutcome.TERMINAL
                    },
                )

            session(runner).events().test {
                assertEquals(SessionEvent.Connecting, awaitItem())
                assertEquals(SessionEvent.AuthFailed("bad creds"), awaitItem())
                awaitComplete()
            }
            assertEquals(1, runner.handlesSeen.size) // never retried
        }

    @Test
    fun explicitDisconnectSurfacesACleanDisconnected() =
        runTest {
            val runner =
                ScriptedRunner(
                    listOf { _, emit ->
                        emit(connected)
                        SessionOutcome.CLEAN_CLOSE
                    },
                )

            session(runner).events().test {
                assertEquals(SessionEvent.Connecting, awaitItem())
                assertTrue(awaitItem() is SessionEvent.Connected)
                assertEquals(SessionEvent.Disconnected(), awaitItem())
                awaitComplete()
            }
        }

    @Test
    fun connectivityReturnCutsTheBackoffShort() =
        runTest(UnconfinedTestDispatcher()) {
            // A huge back-off so any progress before it elapses proves the connectivity kick.
            val policy = ReconnectPolicy(initialDelayMillis = 3_600_000, maxDelayMillis = 3_600_000, jitterRatio = 0.0)
            // Start offline so the rising-edge collector is definitely waiting for the return.
            val connectivity = FakeConnectivityObserver(initial = false)
            val runner =
                ScriptedRunner(
                    listOf(
                        { _, emit ->
                            emit(connected)
                            SessionOutcome.RETRY
                        },
                        { _, emit ->
                            emit(connected)
                            SessionOutcome.TERMINAL
                        },
                    ),
                )

            session(runner, policy = policy, connectivity = connectivity).events().test {
                assertEquals(SessionEvent.Connecting, awaitItem())
                assertTrue(awaitItem() is SessionEvent.Connected)
                assertEquals(SessionEvent.Reconnecting, awaitItem())
                // Network returns → the waiting back-off is kicked immediately (no virtual time elapses).
                connectivity.set(true)
                assertTrue(awaitItem() is SessionEvent.Connected)
                awaitComplete()
            }
            assertEquals("reconnect must happen immediately, not after the back-off", 0, testScheduler.currentTime)
        }

    @Test
    fun foregroundRefocusCutsTheBackoffShort() =
        runTest(UnconfinedTestDispatcher()) {
            val policy = ReconnectPolicy(initialDelayMillis = 3_600_000, maxDelayMillis = 3_600_000, jitterRatio = 0.0)
            val lifecycle = FakeAppLifecycleObserver(initial = true)
            val runner =
                ScriptedRunner(
                    listOf(
                        { _, emit ->
                            emit(connected)
                            SessionOutcome.RETRY
                        },
                        { _, emit ->
                            emit(connected)
                            SessionOutcome.TERMINAL
                        },
                    ),
                )

            session(runner, policy = policy, lifecycle = lifecycle).events().test {
                assertEquals(SessionEvent.Connecting, awaitItem())
                assertTrue(awaitItem() is SessionEvent.Connected)
                assertEquals(SessionEvent.Reconnecting, awaitItem())
                // A background→foreground edge refocuses the app and kicks the waiting back-off.
                lifecycle.set(false)
                lifecycle.set(true)
                assertTrue(awaitItem() is SessionEvent.Connected)
                awaitComplete()
            }
            assertEquals(0, testScheduler.currentTime)
        }

    @Test
    fun backgroundedAppRelaxesUntilForegroundReturns() =
        runTest(UnconfinedTestDispatcher()) {
            val lifecycle = FakeAppLifecycleObserver(initial = false) // start backgrounded
            val runner =
                ScriptedRunner(
                    listOf(
                        { _, emit ->
                            emit(connected)
                            SessionOutcome.RETRY
                        },
                        { _, emit ->
                            emit(connected)
                            SessionOutcome.TERMINAL
                        },
                    ),
                )

            session(runner, lifecycle = lifecycle).events().test {
                assertEquals(SessionEvent.Connecting, awaitItem())
                assertTrue(awaitItem() is SessionEvent.Connected)
                assertEquals(SessionEvent.Reconnecting, awaitItem())
                // Backgrounded: the loop waits quietly (bridge holds the session) — no reconnect yet.
                expectNoEvents()
                // Return to foreground → the reconnect proceeds.
                lifecycle.set(true)
                assertTrue(awaitItem() is SessionEvent.Connected)
                awaitComplete()
            }
        }

    /**
     * Story 0050 defect B. When the radio dies there is no FIN and no socket error — the read simply
     * never returns — so before this the loop stayed inside a "live" attempt and the app went on
     * reporting `Connected` over a link that no longer existed. The bridge, on the other side, kept the
     * session **bound** to that socket rather than parking it, which is what made a returning app open a
     * *second* upstream login for the same username.
     *
     * The runner here is the honest shape of that: an attempt that never returns on its own. The loop
     * must end it on the connectivity edge and surface `Reconnecting`.
     */
    @Test
    fun losingConnectivityEndsTheRunningAttemptInsteadOfReportingConnected() =
        runTest(UnconfinedTestDispatcher()) {
            val connectivity = FakeConnectivityObserver(initial = true)
            val ended = CompletableDeferred<Unit>()
            val runner =
                ScriptedRunner(
                    listOf(
                        // A socket that has connected and is now blocked in a read that will never
                        // return — exactly what a vanished radio leaves behind.
                        { _, emit ->
                            emit(connected)
                            try {
                                awaitCancellation()
                            } finally {
                                ended.complete(Unit)
                            }
                        },
                        { _, emit ->
                            emit(connected)
                            SessionOutcome.TERMINAL
                        },
                    ),
                )

            session(runner, connectivity = connectivity).events().test {
                assertEquals(SessionEvent.Connecting, awaitItem())
                assertTrue(awaitItem() is SessionEvent.Connected)

                connectivity.set(false)

                assertEquals(
                    "losing the network must move the app off Connected at once, not when a TCP read " +
                        "eventually times out",
                    SessionEvent.Reconnecting,
                    awaitItem(),
                )
                assertTrue("the dead attempt must actually be torn down", ended.isCompleted)

                // …and the network coming back resumes rather than re-authenticating: the loop keeps the
                // handle and the caller's runner is offered it again (stories 0023/0024, unchanged).
                connectivity.set(true)
                assertTrue(awaitItem() is SessionEvent.Connected)
                awaitComplete()
            }
        }

    @Test
    fun exhaustingMaxAttemptsGivesUpWithDisconnected() =
        runTest {
            val policy = ReconnectPolicy(initialDelayMillis = 10, maxDelayMillis = 10, jitterRatio = 0.0, maxAttempts = 1)
            val runner = ScriptedRunner(listOf { _, _ -> SessionOutcome.RETRY })

            session(runner, policy = policy).events().test {
                assertEquals(SessionEvent.Connecting, awaitItem())
                // attempt 1 (allowed) then attempt 2 exceeds maxAttempts=1 → give up.
                assertEquals(SessionEvent.Reconnecting, awaitItem())
                assertEquals(SessionEvent.Disconnected("reconnect attempts exhausted"), awaitItem())
                awaitComplete()
            }
        }
}
