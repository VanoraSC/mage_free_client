package magefree.network

import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import magefree.model.ConnectionError
import magefree.model.ConnectionState
import magefree.model.Credentials
import magefree.model.ServerTarget
import magefree.model.SessionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Turbine coverage of story 0019's **seam enrichment**: [ConnectionRepository.connectionStatus]
 * reduces the active session's [SessionEvent]s to a detail-carrying [magefree.model.ConnectionStatus]
 * — the same categorical reduction as the bare `connectionState`, but preserving each failure's
 * diagnostic (auth message, `server=… bridge=…` version detail, a transport [ConnectionError]). A
 * feature-local [ScriptedBridgeClient] emits domain [SessionEvent]s (the boundary type above
 * `:core:network`), letting a transport error — which only a live socket failure produces — be
 * exercised hermetically.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionStatusReductionTest {
    private val target = ServerTarget(host = "bridge.local", port = 9000)
    private val credentials = Credentials(username = "pete", password = null)

    private fun TestScope.repository(client: BridgeClient): ConnectionRepository =
        ConnectionRepository(
            bridgeClient = client,
            dispatcher = UnconfinedTestDispatcher(testScheduler),
            scope = backgroundScope,
        )

    @Test
    fun statusDefaultsToDisconnectedWithoutDetail() =
        runTest {
            val repository = repository(ScriptedBridgeClient(emptyList()))
            val status = repository.connectionStatus.value
            assertEquals(ConnectionState.Disconnected, status.state)
            assertNull(status.detail)
            assertNull(status.error)
        }

    @Test
    fun authFailedCarriesItsMessageDetail() =
        runTest {
            val repository =
                repository(
                    ScriptedBridgeClient(
                        listOf(SessionEvent.Connecting, SessionEvent.AuthFailed("bad creds")),
                    ),
                )
            repository.connectionStatus.test {
                assertEquals(ConnectionState.Disconnected, awaitItem().state)
                repository.connect(target, credentials)
                assertEquals(ConnectionState.Connecting, awaitItem().state)
                val failed = awaitItem()
                assertEquals(ConnectionState.AuthFailed, failed.state)
                assertEquals("bad creds", failed.detail)
                assertNull(failed.error)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun versionUnsupportedCarriesServerBridgeDetail() =
        runTest {
            val repository =
                repository(
                    ScriptedBridgeClient(
                        listOf(
                            SessionEvent.Connecting,
                            SessionEvent.VersionUnsupported("server=1.4.61 bridge=1.4.60"),
                        ),
                    ),
                )
            repository.connectionStatus.test {
                assertEquals(ConnectionState.Disconnected, awaitItem().state)
                repository.connect(target, credentials)
                assertEquals(ConnectionState.Connecting, awaitItem().state)
                val unsupported = awaitItem()
                assertEquals(ConnectionState.Unsupported, unsupported.state)
                assertEquals("server=1.4.61 bridge=1.4.60", unsupported.detail)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun transportErrorReducesToDisconnectedCarryingTheError() =
        runTest {
            val transport = ConnectionError.Transport("failed to open websocket: timeout")
            val repository =
                repository(
                    ScriptedBridgeClient(
                        listOf(SessionEvent.Connecting, SessionEvent.Error(transport)),
                    ),
                )
            repository.connectionStatus.test {
                assertEquals(ConnectionState.Disconnected, awaitItem().state)
                repository.connect(target, credentials)
                assertEquals(ConnectionState.Connecting, awaitItem().state)
                val errored = awaitItem()
                assertEquals(ConnectionState.Disconnected, errored.state)
                assertTrue("error must be a Transport fault", errored.error is ConnectionError.Transport)
                assertEquals("failed to open websocket: timeout", errored.detail)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun bareConnectionStateStaysConsistentWithStatus() =
        runTest {
            // Proves the derived connectionState (0010's dependency) mirrors connectionStatus.state.
            val repository =
                repository(
                    ScriptedBridgeClient(
                        listOf(SessionEvent.Connecting, SessionEvent.AuthFailed("bad creds")),
                    ),
                )
            repository.connectionState.test {
                assertEquals(ConnectionState.Disconnected, awaitItem())
                repository.connect(target, credentials)
                assertEquals(ConnectionState.Connecting, awaitItem())
                assertEquals(ConnectionState.AuthFailed, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    /**
     * Minimal [BridgeClient] that replays a scripted list of domain [SessionEvent]s on each [connect],
     * mirroring `connectionState` from the latest event — the correct seam for a repository-level test
     * that needs to inject a transport [SessionEvent.Error] no `:protocol` message can produce.
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

        override suspend fun <ReplyT : Any> request(
            message: Any,
            requestId: String,
        ): ReplyT = error("request not used in this test")
    }
}
