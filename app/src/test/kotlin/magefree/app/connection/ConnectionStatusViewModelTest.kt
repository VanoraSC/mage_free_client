package magefree.app.connection

import app.cash.turbine.test
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import magefree.app.core.DispatcherProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Hermetic Turbine + coroutines-test coverage for [ConnectionStatusViewModel] — part of
 * `./gradlew check`, no device required. It drives the [StubConnectionStatusSource] through **every**
 * [ConnectionState] and asserts the emitted immutable [ConnectionStatusUiState].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionStatusViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()

    private val dispatchers =
        object : DispatcherProvider {
            override val main: CoroutineDispatcher = testDispatcher
            override val default: CoroutineDispatcher = testDispatcher
            override val io: CoroutineDispatcher = testDispatcher
        }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun uiStateReflectsEverySourceState() =
        runTest {
            val source = StubConnectionStatusSource()
            val viewModel = ConnectionStatusViewModel(source, dispatchers)

            viewModel.uiState.test {
                // Initial value comes from the stub's default state (Disconnected).
                assertEquals(ConnectionState.Disconnected, awaitItem().state)

                // Drive through the remaining states; each differs from the previous so StateFlow
                // (which conflates equal values) emits a fresh item for every one.
                val remaining =
                    listOf(
                        ConnectionState.Connecting,
                        ConnectionState.Connected,
                        ConnectionState.Reconnecting,
                        ConnectionState.Restoring,
                        ConnectionState.AuthFailed,
                        ConnectionState.Unsupported,
                    )
                remaining.forEach { state ->
                    source.emit(state)
                    val ui = awaitItem()
                    assertEquals(state, ui.state)
                    assertTrue("label must be non-blank for $state", ui.label.isNotBlank())
                    assertTrue(
                        "contentDescription must be non-blank for $state",
                        ui.contentDescription.isNotBlank(),
                    )
                }
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun everyStateMapsToADistinctLabelAndContentDescription() {
        val uiStates = ConnectionState.entries.map { it.toUiState() }

        assertEquals(ConnectionState.entries.size, uiStates.map { it.label }.toSet().size)
        assertEquals(ConnectionState.entries.size, uiStates.map { it.contentDescription }.toSet().size)
    }

    @Test
    fun retryAffordanceOnlyOffFailureAndDisconnectedStates() {
        assertTrue(ConnectionState.Disconnected.toUiState().showRetry)
        assertTrue(ConnectionState.AuthFailed.toUiState().showRetry)
        assertFalse(ConnectionState.Connected.toUiState().showRetry)
        assertFalse(ConnectionState.Connecting.toUiState().showRetry)
        assertFalse(ConnectionState.Reconnecting.toUiState().showRetry)
        assertFalse(ConnectionState.Restoring.toUiState().showRetry)
        assertFalse(ConnectionState.Unsupported.toUiState().showRetry)
    }

    @Test
    fun restoringIsAProgressStateDistinctFromReconnecting() {
        val restoring = ConnectionState.Restoring.toUiState()
        val reconnecting = ConnectionState.Reconnecting.toUiState()
        assertEquals(ConnectionSeverity.Progress, restoring.severity)
        assertFalse(restoring.showRetry)
        // Visually/semantically distinct from a plain reconnect (story 0025).
        assertTrue(restoring.label != reconnecting.label)
        assertTrue(restoring.contentDescription != reconnecting.contentDescription)
    }

    @Test
    fun onRetryIsAStubThatDoesNotChangeState() =
        runTest {
            val source = StubConnectionStatusSource()
            val viewModel = ConnectionStatusViewModel(source, dispatchers)

            viewModel.onRetry()

            assertEquals(ConnectionState.Disconnected, source.state.value)
        }

    @Test
    fun onRetryDelegatesToTheSourceRetry() =
        runTest {
            // Story 0026 F3: the status-bar Retry must be a real reconnect through the seam, not a no-op.
            var retries = 0
            val source =
                object : ConnectionStatusSource {
                    override val state: StateFlow<ConnectionState> =
                        MutableStateFlow(ConnectionState.Disconnected)

                    override fun retry() {
                        retries++
                    }
                }
            val viewModel = ConnectionStatusViewModel(source, dispatchers)

            viewModel.onRetry()

            assertEquals(1, retries)
        }
}
