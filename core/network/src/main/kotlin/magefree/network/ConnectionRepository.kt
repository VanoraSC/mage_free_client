package magefree.network

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import magefree.model.ConnectionState
import magefree.model.Credentials
import magefree.model.ServerTarget
import magefree.network.di.ApplicationScope
import magefree.network.di.IoDispatcher
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The app-level owner of the connection lifecycle. It wraps the raw [BridgeClient] (story 0016) into
 * a single source of truth for connection state: at most one active session at a time, its mapped
 * [magefree.model.SessionEvent]s reduced to a [StateFlow] of [ConnectionState] that the shell's
 * status surface (story 0010's seam) and every future connect screen observe consistently.
 *
 * ### Lifecycle
 * [connect] / [retry] publish a [Command] into [command]; [disconnect] clears it. A single
 * `flatMapLatest` over [command] means each new command cancels the previous session's collection
 * before starting the next — enforcing the "one active session" invariant — and reconnect state from
 * the bridge ([magefree.model.SessionEvent.Reconnecting]) flows straight through.
 *
 * The state flow is shared `WhileSubscribed`, so the underlying bridge connection is held only while
 * something (the status bar / a screen) is observing, and torn down shortly after the last collector
 * goes away.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class ConnectionRepository
    @Inject
    constructor(
        private val bridgeClient: BridgeClient,
        @IoDispatcher private val dispatcher: CoroutineDispatcher,
        @ApplicationScope scope: CoroutineScope,
    ) {
        /**
         * The current connect intent, or `null` when disconnected. [Command.attempt] increments on
         * every [connect]/[retry] so an otherwise-identical request still re-triggers `flatMapLatest`
         * (a `StateFlow` would drop a duplicate value).
         */
        private data class Command(
            val server: ServerTarget,
            val credentials: Credentials,
            val attempt: Long,
        )

        private val command = MutableStateFlow<Command?>(null)

        /**
         * The single source of truth for connection state. Reduces the active session's
         * [magefree.model.SessionEvent]s to their [ConnectionState]; emits [ConnectionState.Disconnected]
         * whenever there is no active command.
         */
        val connectionState: StateFlow<ConnectionState> =
            command
                .flatMapLatest { cmd ->
                    if (cmd == null) {
                        flowOf(ConnectionState.Disconnected)
                    } else {
                        bridgeClient
                            .connect(cmd.server, cmd.credentials)
                            .map { it.connectionState }
                    }
                }.flowOn(dispatcher)
                .stateIn(
                    scope = scope,
                    started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                    initialValue = ConnectionState.Disconnected,
                )

        /** Open (or replace) the active session against [server] as [credentials]. */
        fun connect(
            server: ServerTarget,
            credentials: Credentials,
        ) {
            command.update { current ->
                Command(server, credentials, (current?.attempt ?: 0L) + 1L)
            }
        }

        /** Re-attempt the most recent connect, if any. No-op when nothing has been connected yet. */
        fun retry() {
            command.update { current ->
                current?.copy(attempt = current.attempt + 1L)
            }
        }

        /** Tear down the active session and return to [ConnectionState.Disconnected]. */
        suspend fun disconnect() {
            command.value = null
            bridgeClient.disconnect()
        }

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }
