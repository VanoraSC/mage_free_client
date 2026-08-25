package magefree.app.connection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import magefree.app.core.DispatcherProvider

/**
 * Semantic role for a connection state, decoupled from concrete colors. The ViewModel emits a role;
 * the Composable maps the role to a Material color so the UI layer owns theming and the ViewModel
 * stays free of Compose/Android types (and unit-testable without a framework).
 */
enum class ConnectionSeverity {
    /** Healthy — connected. */
    Positive,

    /** In-progress — connecting/reconnecting. */
    Progress,

    /** Attention — dropped/idle, user may want to retry. */
    Warning,

    /** Failure — auth rejected or version unsupported. */
    Error,
}

/**
 * Immutable UI state for the connection-status surface. Everything the [magefree.app.connection.ui.ConnectionStatusBar]
 * needs to render one state distinctly and accessibly:
 * - [label] visible text,
 * - [contentDescription] a distinct, non-color-only accessibility label,
 * - [severity] semantic role → color,
 * - [showRetry] whether a retry affordance is offered (wired to a real reconnect via [onRetry]).
 */
data class ConnectionStatusUiState(
    val state: ConnectionState,
    val label: String,
    val contentDescription: String,
    val severity: ConnectionSeverity,
    val showRetry: Boolean,
)

/** Pure mapping from the domain [ConnectionState] to its immutable [ConnectionStatusUiState]. */
fun ConnectionState.toUiState(): ConnectionStatusUiState =
    when (this) {
        ConnectionState.Connected ->
            ConnectionStatusUiState(
                state = this,
                label = "Connected",
                contentDescription = "Connection status: connected",
                severity = ConnectionSeverity.Positive,
                showRetry = false,
            )

        ConnectionState.Connecting ->
            ConnectionStatusUiState(
                state = this,
                label = "Connecting…",
                contentDescription = "Connection status: connecting",
                severity = ConnectionSeverity.Progress,
                showRetry = false,
            )

        ConnectionState.Reconnecting ->
            ConnectionStatusUiState(
                state = this,
                label = "Reconnecting…",
                contentDescription = "Connection status: reconnecting",
                severity = ConnectionSeverity.Progress,
                showRetry = false,
            )

        ConnectionState.Restoring ->
            ConnectionStatusUiState(
                state = this,
                label = "Restoring your session…",
                contentDescription = "Connection status: restoring your session",
                severity = ConnectionSeverity.Progress,
                showRetry = false,
            )

        ConnectionState.Disconnected ->
            ConnectionStatusUiState(
                state = this,
                label = "Disconnected",
                contentDescription = "Connection status: disconnected",
                severity = ConnectionSeverity.Warning,
                showRetry = true,
            )

        ConnectionState.AuthFailed ->
            ConnectionStatusUiState(
                state = this,
                label = "Authentication failed",
                contentDescription = "Connection status: authentication failed",
                severity = ConnectionSeverity.Error,
                showRetry = true,
            )

        ConnectionState.Unsupported ->
            ConnectionStatusUiState(
                state = this,
                label = "Version unsupported",
                contentDescription = "Connection status: server version unsupported",
                severity = ConnectionSeverity.Error,
                showRetry = false,
            )
    }

/**
 * Maps a [ConnectionStatusSource]'s [ConnectionState] stream into an immutable
 * [ConnectionStatusUiState] exposed as [uiState] for the shell's status bar (MVVM/UDF).
 *
 * The mapping runs on [DispatcherProvider.default] (never a hard-coded dispatcher, per `AGENTS.md`).
 * The source is injected behind the [ConnectionStatusSource] seam, so EPIC-04 swaps the real session
 * in without touching this ViewModel or the UI.
 */

class ConnectionStatusViewModel
    constructor(
        private val source: ConnectionStatusSource,
        dispatchers: DispatcherProvider,
    ) : ViewModel() {
        val uiState: StateFlow<ConnectionStatusUiState> =
            source.state
                .map { it.toUiState() }
                .flowOn(dispatchers.default)
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                    initialValue = source.state.value.toUiState(),
                )

        /**
         * Retry/reconnect affordance for the status bar. Delegates to the [ConnectionStatusSource] seam
         * (story 0026 F3), which the real [ConnectionStatusSourceImpl] implements as a genuine reconnect
         * via `ConnectionRepository.retry()` — so the always-visible Retry is no longer a dead control.
         */
        fun onRetry() {
            source.retry()
        }

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }
