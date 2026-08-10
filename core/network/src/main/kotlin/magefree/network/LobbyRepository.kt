package magefree.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import magefree.model.ConnectionState
import magefree.model.LobbyLoadState
import magefree.model.LobbySnapshot
import magefree.network.di.ApplicationScope
import magefree.network.di.IoDispatcher
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The observable, refreshable lobby data layer (story 0028). Holds a single [StateFlow] of
 * [LobbySnapshot] — the tables, room users, and game types plus the current load/refresh/error
 * [status][LobbyLoadState] — that the browser UI (0029) renders.
 *
 * It does **not** own the session. It observes the app-level [connectionState] and drives the
 * [LobbyClient] (which rides the connected [BridgeClient]'s live socket). The lobby is meaningful only
 * while connected: any transition away from [ConnectionState.Connected] cancels an in-flight refresh
 * and resets to the idle/empty snapshot. [refresh] is the manual/pull-to-refresh trigger (there is no
 * server push for the table list); it fetches the three lists concurrently and reduces them into the
 * snapshot. A failure is captured **as state** ([LobbyLoadState.Error] + [LobbySnapshot.error]) and is
 * never thrown to the caller, keeping reconnection/refresh an expected path rather than an error.
 *
 * Sorting/filtering is deliberately **not** here — the raw snapshot is exposed and 0029 does
 * presentation.
 */
@Singleton
class LobbyRepository
    @Inject
    constructor(
        private val lobbyClient: LobbyClient,
        // @JvmSuppressWildcards: StateFlow is declared covariant (`out T`), so Kotlin would compile this
        // injected key to `StateFlow<? extends ConnectionState>` while the provider supplies the
        // invariant `StateFlow<ConnectionState>` — a Dagger missing-binding. Suppressing wildcards on
        // both ends keys them identically. (Surfaced when 0029 first assembled this into the app graph.)
        private val connectionState: StateFlow<@JvmSuppressWildcards ConnectionState>,
        @IoDispatcher private val dispatcher: CoroutineDispatcher,
        @ApplicationScope private val scope: CoroutineScope,
    ) {
        private val _snapshot = MutableStateFlow(LobbySnapshot())

        /** The observable lobby snapshot. Starts idle/empty and returns there whenever disconnected. */
        val snapshot: StateFlow<LobbySnapshot> = _snapshot.asStateFlow()

        /**
         * The in-flight refresh. Written from the caller's thread ([refresh]) and from the connection
         * collector on [scope] (an IO-dispatcher application scope), so the handle is held atomically:
         * `getAndSet` makes "take the current handle and install mine" one indivisible step, and no
         * thread can read a stale/absent handle for a refresh that is already running (story 0044).
         */
        private val refreshJob = AtomicReference<Job?>(null)

        init {
            // The lobby only makes sense while connected: reset to idle/empty on any non-connected state
            // (disconnect, reconnecting, auth-failed, …), cancelling any in-flight refresh.
            scope.launch {
                connectionState.collect { state ->
                    if (state != ConnectionState.Connected) resetToIdle()
                }
            }
        }

        /**
         * Refresh the lobby: fetch tables/users/types concurrently and reduce them into [snapshot]. A
         * no-op-to-idle when not connected. Errors surface as [LobbyLoadState.Error] state, never thrown.
         * A new call supersedes an in-flight one.
         */
        fun refresh() {
            if (connectionState.value != ConnectionState.Connected) {
                resetToIdle()
                return
            }
            // Started lazily so the handle is published *before* the body can run. An eagerly started
            // refresh can reach its first suspension — and a concurrent disconnect can therefore observe
            // no handle at all — before `launch` has even returned, which is precisely how a refresh used
            // to survive the disconnect that was meant to cancel it.
            val job = scope.launch(dispatcher, start = CoroutineStart.LAZY) { runRefresh() }
            refreshJob.getAndSet(job)?.cancel()
            job.start()
        }

        /** Cancel any in-flight refresh and return to the idle/empty snapshot. */
        private fun resetToIdle() {
            refreshJob.getAndSet(null)?.cancel()
            _snapshot.value = LobbySnapshot()
        }

        private suspend fun runRefresh() {
            publish { current ->
                current.copy(
                    status =
                        if (current.status == LobbyLoadState.Loaded) {
                            LobbyLoadState.Refreshing
                        } else {
                            LobbyLoadState.Loading
                        },
                    error = null,
                )
            }
            try {
                coroutineScope {
                    val tables = async { lobbyClient.tables() }
                    val users = async { lobbyClient.roomUsers() }
                    val types = async { lobbyClient.gameTypes() }
                    val loaded =
                        LobbySnapshot(
                            tables = tables.await(),
                            roomUsers = users.await(),
                            gameTypes = types.await(),
                            status = LobbyLoadState.Loaded,
                            error = null,
                        )
                    publish { loaded }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Error-as-state: keep any prior lists, flag the failure, never throw.
                publish { current ->
                    current.copy(
                        status = LobbyLoadState.Error,
                        error = e.message ?: "lobby refresh failed",
                    )
                }
            }
        }

        /**
         * The single snapshot write. Cancellation alone cannot make a refresh safe — between its last
         * fetch resolving and its terminal write there is no suspension point at which a cancel can take
         * effect, so a refresh racing a disconnect could still publish tables onto a disconnected lobby.
         * Re-reading [connectionState] *inside* the update closes that window: while disconnected the
         * write collapses to the same idle/empty snapshot the collector installs, so the class invariant
         * ("populated only while connected") holds whichever side wins the race.
         */
        private fun publish(next: (LobbySnapshot) -> LobbySnapshot) {
            _snapshot.update { current ->
                if (connectionState.value == ConnectionState.Connected) next(current) else LobbySnapshot()
            }
        }
    }
