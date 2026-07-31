package magefree.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
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
        private val connectionState: StateFlow<ConnectionState>,
        @IoDispatcher private val dispatcher: CoroutineDispatcher,
        @ApplicationScope private val scope: CoroutineScope,
    ) {
        private val _snapshot = MutableStateFlow(LobbySnapshot())

        /** The observable lobby snapshot. Starts idle/empty and returns there whenever disconnected. */
        val snapshot: StateFlow<LobbySnapshot> = _snapshot.asStateFlow()

        private var refreshJob: Job? = null

        init {
            // The lobby only makes sense while connected: reset to idle/empty on any non-connected state
            // (disconnect, reconnecting, auth-failed, …), cancelling any in-flight refresh.
            scope.launch {
                connectionState.collect { state ->
                    if (state != ConnectionState.Connected) {
                        refreshJob?.cancel()
                        _snapshot.value = LobbySnapshot()
                    }
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
                refreshJob?.cancel()
                _snapshot.value = LobbySnapshot()
                return
            }
            refreshJob?.cancel()
            refreshJob =
                scope.launch(dispatcher) {
                    _snapshot.update { current ->
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
                            _snapshot.value =
                                LobbySnapshot(
                                    tables = tables.await(),
                                    roomUsers = users.await(),
                                    gameTypes = types.await(),
                                    status = LobbyLoadState.Loaded,
                                    error = null,
                                )
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Error-as-state: keep any prior lists, flag the failure, never throw.
                        _snapshot.update { current ->
                            current.copy(
                                status = LobbyLoadState.Error,
                                error = e.message ?: "lobby refresh failed",
                            )
                        }
                    }
                }
        }
    }
