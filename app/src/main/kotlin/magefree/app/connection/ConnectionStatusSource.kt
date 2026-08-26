package magefree.app.connection

import kotlinx.coroutines.flow.StateFlow

/**
 * The seam between the shell's connection-status surface and whatever actually knows the
 * connection state. It exposes the current [ConnectionState] as an observable [StateFlow].
 *
 * Today the only implementation is [StubConnectionStatusSource]. The real implementation replaces this
 * interface against the real bridge session and swaps the Hilt binding — the ViewModel and the
 * `ConnectionStatusBar` UI never change.
 */
interface ConnectionStatusSource {
    /** The current connection state, hot and always readable. */
    val state: StateFlow<ConnectionState>

    /**
     * Re-attempt the connection behind this source. Backs the status-bar Retry so it is
     * a real reconnect rather than a dead control. The real [ConnectionStatusSourceImpl] delegates to
     * `ConnectionRepository.retry()`; the [StubConnectionStatusSource] no-ops (nothing to reconnect).
     */
    fun retry()
}
