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
import magefree.model.ConnectionState
import magefree.model.Credentials
import magefree.model.ServerTarget
import magefree.model.Session
import magefree.model.SessionEvent
import magefree.network.fake.FakeBridgeClient
import magefree.protocol.SessionStateCode
import magefree.protocol.SessionStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Turbine coverage of [ConnectionRepository]'s reduction of a [FakeBridgeClient]'s scripted
 * `:protocol` sessions into a single `StateFlow<ConnectionState>`. The fake replays real protocol
 * messages through the production mapper, so these assertions exercise the true mapping + the
 * repository's lifecycle (one active session, reconnect, disconnect, retry) hermetically — every
 * state (connecting → connected → reconnecting → disconnected, auth-failed, version-unsupported).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionRepositoryTest {
    private val target = ServerTarget(host = "bridge.local", port = 9000)
    private val credentials = Credentials(username = "pete", password = null)

    private fun TestScope.repository(client: BridgeClient): ConnectionRepository =
        ConnectionRepository(
            bridgeClient = client,
            dispatcher = UnconfinedTestDispatcher(testScheduler),
            scope = backgroundScope,
        )

    @Test
    fun defaultsToDisconnected() =
        runTest {
            val repository = repository(FakeBridgeClient())
            assertEquals(ConnectionState.Disconnected, repository.connectionState.value)
        }

    @Test
    fun connectingIsReflectedWhileOpening() =
        runTest {
            // Empty script: the fake emits only the socket-opening Connecting, then idles.
            val repository = repository(FakeBridgeClient())
            repository.connectionState.test {
                assertEquals(ConnectionState.Disconnected, awaitItem())
                repository.connect(target, credentials)
                assertEquals(ConnectionState.Connecting, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun connectedIsReflected() =
        runTest {
            val repository = repository(FakeBridgeClient(listOf(SessionStatus(SessionStateCode.CONNECTED))))
            repository.connectionState.test {
                assertEquals(ConnectionState.Disconnected, awaitItem())
                repository.connect(target, credentials)
                assertEquals(ConnectionState.Connecting, awaitItem())
                assertEquals(ConnectionState.Connected, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun reconnectingIsReflected() =
        runTest {
            val repository = repository(FakeBridgeClient(listOf(SessionStatus(SessionStateCode.RECONNECTING))))
            repository.connectionState.test {
                assertEquals(ConnectionState.Disconnected, awaitItem())
                repository.connect(target, credentials)
                assertEquals(ConnectionState.Connecting, awaitItem())
                assertEquals(ConnectionState.Reconnecting, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun disconnectedIsReflectedAfterCleanClose() =
        runTest {
            val repository =
                repository(
                    FakeBridgeClient(
                        listOf(
                            SessionStatus(SessionStateCode.CONNECTED),
                            SessionStatus(SessionStateCode.DISCONNECTED, message = "server shutdown"),
                        ),
                    ),
                )
            repository.connectionState.test {
                assertEquals(ConnectionState.Disconnected, awaitItem())
                repository.connect(target, credentials)
                assertEquals(ConnectionState.Connecting, awaitItem())
                assertEquals(ConnectionState.Connected, awaitItem())
                assertEquals(ConnectionState.Disconnected, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun authFailedIsReflected() =
        runTest {
            val repository =
                repository(FakeBridgeClient(listOf(SessionStatus(SessionStateCode.AUTH_FAILED, message = "bad creds"))))
            repository.connectionState.test {
                assertEquals(ConnectionState.Disconnected, awaitItem())
                repository.connect(target, credentials)
                assertEquals(ConnectionState.Connecting, awaitItem())
                assertEquals(ConnectionState.AuthFailed, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun versionUnsupportedIsReflected() =
        runTest {
            val repository =
                repository(
                    FakeBridgeClient(
                        listOf(SessionStatus(SessionStateCode.VERSION_UNSUPPORTED, message = "server=1.4.61 bridge=1.4.60")),
                    ),
                )
            repository.connectionState.test {
                assertEquals(ConnectionState.Disconnected, awaitItem())
                repository.connect(target, credentials)
                assertEquals(ConnectionState.Connecting, awaitItem())
                assertEquals(ConnectionState.Unsupported, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun emitsTheFullTransitionSequenceInOrder() =
        runTest {
            // connecting -> connected -> reconnecting -> connected -> disconnected in a single session.
            val repository =
                repository(
                    FakeBridgeClient(
                        listOf(
                            SessionStatus(SessionStateCode.CONNECTED),
                            SessionStatus(SessionStateCode.RECONNECTING),
                            SessionStatus(SessionStateCode.CONNECTED),
                            SessionStatus(SessionStateCode.DISCONNECTED),
                        ),
                    ),
                )

            repository.connectionState.test {
                assertEquals(ConnectionState.Disconnected, awaitItem())
                repository.connect(target, credentials)
                assertEquals(ConnectionState.Connecting, awaitItem())
                assertEquals(ConnectionState.Connected, awaitItem())
                assertEquals(ConnectionState.Reconnecting, awaitItem())
                assertEquals(ConnectionState.Connected, awaitItem())
                assertEquals(ConnectionState.Disconnected, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun disconnectReturnsToDisconnected() =
        runTest {
            val repository = repository(FakeBridgeClient(listOf(SessionStatus(SessionStateCode.CONNECTED))))
            repository.connectionState.test {
                assertEquals(ConnectionState.Disconnected, awaitItem())
                repository.connect(target, credentials)
                assertEquals(ConnectionState.Connecting, awaitItem())
                assertEquals(ConnectionState.Connected, awaitItem())

                repository.disconnect()
                assertEquals(ConnectionState.Disconnected, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun retryReopensASession() =
        runTest {
            // A counting client proves retry() drives a fresh connect (one active session per attempt).
            val client = CountingBridgeClient()
            val repository = repository(client)

            repository.connectionState.test {
                assertEquals(ConnectionState.Disconnected, awaitItem())
                repository.connect(target, credentials)
                assertEquals(ConnectionState.Connecting, awaitItem())
                assertEquals(ConnectionState.Connected, awaitItem())
                assertEquals(1, client.connectCount)

                repository.retry()
                assertEquals(ConnectionState.Connecting, awaitItem())
                assertEquals(ConnectionState.Connected, awaitItem())
                assertEquals(2, client.connectCount)
                cancelAndIgnoreRemainingEvents()
            }
        }

    /** Minimal deterministic [BridgeClient] that counts each [connect] collection (connecting → connected). */
    private class CountingBridgeClient : BridgeClient {
        var connectCount = 0
            private set

        private val _connectionState = MutableStateFlow(ConnectionState.Disconnected)
        override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

        override fun connect(
            server: ServerTarget,
            credentials: Credentials,
        ): Flow<SessionEvent> =
            flow {
                connectCount++
                emit(SessionEvent.Connecting)
                emit(SessionEvent.Connected(Session(server, credentials.username)))
            }.onEach { _connectionState.value = it.connectionState }

        override suspend fun disconnect() {
            _connectionState.value = ConnectionState.Disconnected
        }
    }
}
