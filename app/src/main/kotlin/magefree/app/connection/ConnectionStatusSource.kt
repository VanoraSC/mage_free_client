package magefree.app.connection

import kotlinx.coroutines.flow.StateFlow

/**
 * The seam between the shell's connection-status surface and whatever actually knows the
 * connection state. It exposes the current [ConnectionState] as an observable [StateFlow].
 *
 * Today the only implementation is [StubConnectionStatusSource]. EPIC-04 re-implements this
 * interface against the real bridge session and swaps the Hilt binding — the ViewModel and the
 * `ConnectionStatusBar` UI never change.
 */
interface ConnectionStatusSource {
    /** The current connection state, hot and always readable. */
    val state: StateFlow<ConnectionState>
}
