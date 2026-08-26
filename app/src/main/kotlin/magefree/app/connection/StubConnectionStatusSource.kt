package magefree.app.connection

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The default [ConnectionStatusSource] for now: a driveable in-memory holder with no real network.
 *
 * It starts [Disconnected][ConnectionState.Disconnected] — the honest state when nothing is wired to
 * a server yet — and lets callers push arbitrary states via [emit]. Tests drive it to exercise every
 * state; an optional debug affordance could drive it in a running build. The real implementation is a
 * source backed by the real session.
 */

class StubConnectionStatusSource
    constructor() : ConnectionStatusSource {
        private val mutableState = MutableStateFlow(ConnectionState.Disconnected)

        override val state: StateFlow<ConnectionState> = mutableState.asStateFlow()

        /** Drive the surfaced state. Used by tests (and any debug toggle) to move between states. */
        fun emit(state: ConnectionState) {
            mutableState.value = state
        }

        /** No real connection to re-attempt behind the stub; nothing to do. */
        override fun retry() = Unit
    }
