package magefree.network

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import magefree.model.ConnectionError
import magefree.model.ConnectionState
import magefree.model.ConnectionStatus
import magefree.model.Credentials
import magefree.model.ServerTarget
import magefree.model.SessionEvent

/**
 * The app-level owner of the connection lifecycle. It wraps the raw [BridgeClient] (story 0016) into
 * a single source of truth for connection state: at most one active session at a time, its mapped
 * [magefree.model.SessionEvent]s reduced to a [StateFlow] of [ConnectionState] that the shell's
 * status surface (story 0010's seam) and every future connect screen observe consistently.
 *
 * ### Lifecycle
 * [connect] / [retry] publish a [Command] into [command]; [signOut] clears it. A single
 * `flatMapLatest` over [command] means each new command cancels the previous session's collection
 * before starting the next — enforcing the "one active session" invariant — and reconnect state from
 * the bridge ([magefree.model.SessionEvent.Reconnecting]) flows straight through.
 *
 * The state flow is shared `WhileSubscribed`, so the underlying bridge connection is held only while
 * something (the status bar / a screen) is observing, and torn down shortly after the last collector
 * goes away.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionRepository
    constructor(
        private val bridgeClient: BridgeClient,
        private val dispatcher: CoroutineDispatcher,
        scope: CoroutineScope,
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
         * The single shared stream of the active session's [SessionEvent]s. One `flatMapLatest` over
         * [command] preserves the "one active session" invariant (each command cancels the previous
         * session's collection); a hot [shareIn] with the default SUSPEND back-pressure then multicasts
         * those events **losslessly** to both public flows below, so the status bar and a connect
         * screen observing at once share one bridge connection rather than opening two. When there is
         * no active command it emits a single clean [SessionEvent.Disconnected].
         */
        private val sessionEvents: SharedFlow<SessionEvent> =
            command
                .flatMapLatest { cmd ->
                    if (cmd == null) {
                        flowOf(SessionEvent.Disconnected())
                    } else {
                        bridgeClient.connect(cmd.server, cmd.credentials)
                    }
                }.flowOn(dispatcher)
                .shareIn(
                    scope = scope,
                    started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                    replay = 1,
                )

        /**
         * The detail-carrying source of truth (story 0019). Reduces [sessionEvents] to a
         * [ConnectionStatus] — the **same** categorical reduction as [connectionState] but preserving
         * each failure's diagnostic (auth message, `server=… bridge=…` version detail, a transport
         * [ConnectionError]) so the sign-in UI can render distinct, detailed surfaces. Emits
         * [ConnectionStatus.Disconnected] whenever there is no active command.
         */
        val connectionStatus: StateFlow<ConnectionStatus> =
            sessionEvents
                .map { it.toConnectionStatus() }
                .stateIn(
                    scope = scope,
                    started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                    initialValue = ConnectionStatus.Disconnected,
                )

        /**
         * The single source of truth for connection state (story 0017; story 0010's status-bar
         * dependency via `ConnectionStatusSourceImpl`). Its type and observable behaviour are
         * **unchanged** — the same one-per-[SessionEvent] reduction to [ConnectionState], now taken
         * from the shared [sessionEvents] so it and [connectionStatus] stay consistent over one
         * session. Emits [ConnectionState.Disconnected] whenever there is no active command.
         */
        val connectionState: StateFlow<ConnectionState> =
            sessionEvents
                .map { it.connectionState }
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

        /**
         * Sign out: drop the connect intent and tear the session down **deliberately**, returning to
         * [ConnectionState.Disconnected].
         *
         * Every caller of this is a user leaving on purpose (the sign-in surfaces' Cancel / Back /
         * re-authenticate), so it routes to [BridgeClient.signOut] — the bridge disconnects the upstream
         * XMage session immediately instead of parking it for the resume TTL, and the username is free
         * again at once (story 0046).
         *
         * The *other* direction is not a method here: a drop or a lifecycle pause never calls this. It
         * ends the session by cancelling the shared collection (this class shares `sessionEvents`
         * `WhileSubscribed`), which closes the socket without a `Logout` and leaves the bridge holding
         * the session for a reconnect to resume — stories 0023/0024, deliberately untouched.
         */
        suspend fun signOut() {
            command.value = null
            bridgeClient.signOut()
        }

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }

/**
 * Reduce a [SessionEvent] to a detail-carrying [ConnectionStatus]. Mirrors
 * [SessionEvent.connectionState] for the categorical [ConnectionStatus.state] while preserving the
 * event's diagnostic: an [SessionEvent.AuthFailed] message, a [SessionEvent.VersionUnsupported]
 * `server=… bridge=…` detail, a [SessionEvent.Disconnected] reason, or a [SessionEvent.Error]'s
 * structured [ConnectionError] (a transport/protocol fault the UI renders as a network/timeout
 * surface). The plain lifecycle events (connecting/connected/reconnecting) carry no detail.
 */
private fun SessionEvent.toConnectionStatus(): ConnectionStatus =
    when (this) {
        is SessionEvent.AuthFailed -> ConnectionStatus(ConnectionState.AuthFailed, detail = message)
        is SessionEvent.VersionUnsupported -> ConnectionStatus(ConnectionState.Unsupported, detail = detail)
        is SessionEvent.Disconnected -> ConnectionStatus(ConnectionState.Disconnected, detail = message)
        is SessionEvent.Error ->
            ConnectionStatus(ConnectionState.Disconnected, detail = error.detailMessage(), error = error)
        else -> ConnectionStatus(connectionState)
    }

/** The human-readable reason carried by a [ConnectionError], for display in a failure surface. */
private fun ConnectionError.detailMessage(): String =
    when (this) {
        is ConnectionError.Transport -> message
        is ConnectionError.Protocol -> message
    }
