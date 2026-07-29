package magefree.feature.connect

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import magefree.model.ConnectionState
import magefree.model.Credentials
import magefree.model.ServerTarget
import magefree.model.Session
import magefree.model.SessionEvent
import magefree.network.BridgeClient
import magefree.network.ConnectionRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

/**
 * Hermetic Turbine coverage of [SignInViewModel] driving a **real** [ConnectionRepository] over a
 * feature-local fake [BridgeClient] that emits domain [SessionEvent]s (the boundary type visible above
 * `:core:network`; the `:protocol` wire shapes deliberately do not leak this high). Exercises every
 * [ConnectPhase]: connecting → connected, auth-failed, version-unsupported, reconnecting — plus form
 * validation and retry/cancel.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SignInViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val target = ServerTarget(host = "bridge.local", port = 9000, displayName = "Local")

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.viewModel(events: List<SessionEvent>): SignInViewModel {
        val repository =
            ConnectionRepository(
                bridgeClient = ScriptedBridgeClient(events),
                dispatcher = dispatcher,
                scope = backgroundScope,
            )
        return SignInViewModel(repository)
    }

    /** Awaits items until one reaches [phase] (tolerates form-only intermediate emissions). */
    private suspend fun ReceiveTurbine<SignInUiState>.awaitPhase(phase: ConnectPhase): SignInUiState {
        while (true) {
            val item = awaitItem()
            if (item.phase == phase) return item
        }
    }

    /** Awaits items until one satisfies [predicate], returning it. */
    private suspend fun ReceiveTurbine<SignInUiState>.awaitUntil(predicate: (SignInUiState) -> Boolean): SignInUiState {
        while (true) {
            val item = awaitItem()
            if (predicate(item)) return item
        }
    }

    private fun connectedScript() = listOf(SessionEvent.Connecting, SessionEvent.Connected(Session(target, "pete")))

    @Test
    fun idleFormValidatesUsernamePresence() =
        runTest {
            val vm = viewModel(emptyList())
            vm.uiState.test {
                assertFalse("no server bound, no username", awaitItem().canSubmit)

                vm.bind(target)
                vm.updateUsername("pete")
                val ready = awaitUntil { it.canSubmit }
                assertEquals(ConnectPhase.Idle, ready.phase)
                assertEquals(target, ready.server)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun connectDrivesConnectingThenConnected() =
        runTest {
            val vm = viewModel(connectedScript())
            vm.uiState.test {
                awaitItem()
                vm.bind(target)
                vm.updateUsername("pete")

                vm.connect()
                awaitPhase(ConnectPhase.Connecting)
                awaitPhase(ConnectPhase.Connected)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun authFailedSurfacesDistinctly() =
        runTest {
            val vm = viewModel(listOf(SessionEvent.Connecting, SessionEvent.AuthFailed("bad creds")))
            vm.uiState.test {
                awaitItem()
                vm.bind(target)
                vm.updateUsername("pete")

                vm.connect()
                awaitPhase(ConnectPhase.Connecting)
                assertEquals(ConnectPhase.AuthFailed, awaitPhase(ConnectPhase.AuthFailed).phase)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun versionUnsupportedSurfacesDistinctlyFromAuthFailed() =
        runTest {
            val vm =
                viewModel(
                    listOf(
                        SessionEvent.Connecting,
                        SessionEvent.VersionUnsupported("server=1.4.61 bridge=1.4.60"),
                    ),
                )
            vm.uiState.test {
                awaitItem()
                vm.bind(target)
                vm.updateUsername("pete")

                vm.connect()
                awaitPhase(ConnectPhase.Connecting)
                val unsupported = awaitPhase(ConnectPhase.VersionUnsupported)
                assertEquals(ConnectPhase.VersionUnsupported, unsupported.phase)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun reconnectingIsReflected() =
        runTest {
            val vm =
                viewModel(
                    listOf(
                        SessionEvent.Connecting,
                        SessionEvent.Connected(Session(target, "pete")),
                        SessionEvent.Reconnecting,
                    ),
                )
            vm.uiState.test {
                awaitItem()
                vm.bind(target)
                vm.updateUsername("pete")

                vm.connect()
                awaitPhase(ConnectPhase.Connecting)
                awaitPhase(ConnectPhase.Connected)
                awaitPhase(ConnectPhase.Reconnecting)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun connectIsNoOpWithoutUsername() =
        runTest {
            val vm = viewModel(connectedScript())
            vm.uiState.test {
                awaitItem()
                vm.bind(target)

                vm.connect() // no username -> ignored
                val latest = expectMostRecentItem()
                assertEquals(ConnectPhase.Idle, latest.phase)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun cancelReturnsToIdleAfterFailure() =
        runTest {
            val vm = viewModel(listOf(SessionEvent.Connecting, SessionEvent.AuthFailed()))
            vm.uiState.test {
                awaitItem()
                vm.bind(target)
                vm.updateUsername("pete")

                vm.connect()
                awaitPhase(ConnectPhase.AuthFailed)

                vm.cancel()
                awaitPhase(ConnectPhase.Idle)
                cancelAndIgnoreRemainingEvents()
            }
        }

    /**
     * Minimal [BridgeClient] that replays a scripted list of domain [SessionEvent]s on each [connect].
     * It mirrors the real client's contract (mirror `connectionState` from the latest event) without
     * touching the `:protocol` wire layer — the correct seam for a feature-level ViewModel test.
     */
    private class ScriptedBridgeClient(
        private val events: List<SessionEvent>,
    ) : BridgeClient {
        private val mutableState = MutableStateFlow(ConnectionState.Disconnected)
        override val connectionState: StateFlow<ConnectionState> = mutableState.asStateFlow()

        override fun connect(
            server: ServerTarget,
            credentials: Credentials,
        ): Flow<SessionEvent> =
            flow {
                for (event in events) emit(event)
            }.onEach { mutableState.value = it.connectionState }

        override suspend fun disconnect() {
            mutableState.value = ConnectionState.Disconnected
        }
    }
}
