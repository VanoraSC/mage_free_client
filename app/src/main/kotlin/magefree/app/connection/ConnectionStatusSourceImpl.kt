package magefree.app.connection

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import magefree.app.core.DispatcherProvider
import magefree.network.ConnectionRepository
import javax.inject.Inject
import javax.inject.Singleton
import magefree.model.ConnectionState as DomainConnectionState

/**
 * The real [ConnectionStatusSource] (story 0017), replacing story 0010's stub behind the seam.
 *
 * It delegates to [ConnectionRepository]'s single source-of-truth state and maps `:core:model`'s
 * [DomainConnectionState] onto the app-local [ConnectionState] the ViewModel/UI already consume. The
 * two enums are intentionally identical (0016 aligned names + order), so this is a total, lossless
 * 1:1 mapping — the swap is transparent to [ConnectionStatusViewModel] and `ConnectionStatusBar`,
 * which now reflect the *real* connection instead of the stub.
 *
 * The mapping runs on [DispatcherProvider.default] (no hard-coded dispatcher, per `AGENTS.md`) and is
 * shared `WhileSubscribed`, so subscription pressure propagates down to the repository (and thus the
 * bridge) rather than holding a connection open forever.
 */
@Singleton
class ConnectionStatusSourceImpl
    @Inject
    constructor(
        repository: ConnectionRepository,
        dispatchers: DispatcherProvider,
    ) : ConnectionStatusSource {
        private val scope = CoroutineScope(SupervisorJob() + dispatchers.default)

        override val state: StateFlow<ConnectionState> =
            repository.connectionState
                .map { it.toAppState() }
                .stateIn(
                    scope = scope,
                    started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                    initialValue = repository.connectionState.value.toAppState(),
                )

        private fun DomainConnectionState.toAppState(): ConnectionState =
            when (this) {
                DomainConnectionState.Disconnected -> ConnectionState.Disconnected
                DomainConnectionState.Connecting -> ConnectionState.Connecting
                DomainConnectionState.Connected -> ConnectionState.Connected
                DomainConnectionState.Reconnecting -> ConnectionState.Reconnecting
                DomainConnectionState.Restoring -> ConnectionState.Restoring
                DomainConnectionState.AuthFailed -> ConnectionState.AuthFailed
                DomainConnectionState.Unsupported -> ConnectionState.Unsupported
            }

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }
