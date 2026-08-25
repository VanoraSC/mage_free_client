package magefree.network.fake

import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import magefree.model.ConnectionState
import magefree.model.Credentials
import magefree.model.ServerTarget
import magefree.model.SessionEvent
import magefree.protocol.ProtocolVersion
import magefree.protocol.ServerHello
import magefree.protocol.SessionStateCode
import magefree.protocol.SessionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Turbine coverage of the fake-driven client state machine. Because [FakeBridgeClient] replays real
 * `:protocol` messages through the production mapper, these assertions cover both the mapping and the
 * terminal-state handling of a `BridgeClient` without a live bridge.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FakeBridgeClientTest {
    private val target = ServerTarget(host = "bridge.local", port = 9000)
    private val credentials = Credentials(username = "pete", password = null)

    @Test
    fun happyPathEmitsConnectingConnectedThenDisconnected() =
        runTest {
            val client =
                FakeBridgeClient(
                    script =
                        listOf(
                            SessionStatus(SessionStateCode.CONNECTED),
                            SessionStatus(SessionStateCode.DISCONNECTED, message = "server shutdown"),
                        ),
                )

            client.connect(target, credentials).test {
                assertEquals(SessionEvent.Connecting, awaitItem())
                val connected = awaitItem()
                assertTrue(connected is SessionEvent.Connected)
                assertEquals("pete", (connected as SessionEvent.Connected).session.username)
                assertEquals("fake-bridge", connected.session.bridgeVersion)
                assertEquals(SessionEvent.Disconnected("server shutdown"), awaitItem())
                awaitComplete()
            }

            assertEquals(ConnectionState.Disconnected, client.connectionState.value)
        }

    @Test
    fun reconnectingIsRelayedBeforeReconnected() =
        runTest {
            val client =
                FakeBridgeClient(
                    script =
                        listOf(
                            SessionStatus(SessionStateCode.RECONNECTING),
                            SessionStatus(SessionStateCode.CONNECTED),
                        ),
                )

            client.connect(target, credentials).test {
                assertEquals(SessionEvent.Connecting, awaitItem())
                assertEquals(SessionEvent.Reconnecting, awaitItem())
                assertTrue(awaitItem() is SessionEvent.Connected)
                awaitComplete()
            }

            assertEquals(ConnectionState.Connected, client.connectionState.value)
        }

    @Test
    fun authFailureIsTerminalAndStopsTheScript() =
        runTest {
            val client =
                FakeBridgeClient(
                    script =
                        listOf(
                            SessionStatus(SessionStateCode.AUTH_FAILED, message = "bad credentials"),
                            // Must never be reached: auth failure is terminal.
                            SessionStatus(SessionStateCode.CONNECTED),
                        ),
                )

            client.connect(target, credentials).test {
                assertEquals(SessionEvent.Connecting, awaitItem())
                assertEquals(SessionEvent.AuthFailed("bad credentials"), awaitItem())
                awaitComplete()
            }

            assertEquals(ConnectionState.AuthFailed, client.connectionState.value)
        }

    @Test
    fun mismatchedHandshakeShortCircuitsToVersionUnsupported() =
        runTest {
            val client =
                FakeBridgeClient(
                    script = listOf(SessionStatus(SessionStateCode.CONNECTED)),
                    serverHello = ServerHello(ProtocolVersion.MAJOR + 1, 0, "bridge-9.9.9"),
                )

            client.connect(target, credentials).test {
                assertEquals(SessionEvent.Connecting, awaitItem())
                assertTrue(awaitItem() is SessionEvent.VersionUnsupported)
                awaitComplete()
            }

            assertEquals(ConnectionState.Unsupported, client.connectionState.value)
        }

    @Test
    fun relayedVersionUnsupportedStatusIsTerminal() =
        runTest {
            val client =
                FakeBridgeClient(
                    script =
                        listOf(
                            SessionStatus(
                                SessionStateCode.VERSION_UNSUPPORTED,
                                message = "server=1.4.61 bridge=1.4.60",
                            ),
                            SessionStatus(SessionStateCode.CONNECTED),
                        ),
                )

            client.connect(target, credentials).test {
                assertEquals(SessionEvent.Connecting, awaitItem())
                assertEquals(
                    SessionEvent.VersionUnsupported("server=1.4.61 bridge=1.4.60"),
                    awaitItem(),
                )
                awaitComplete()
            }

            assertEquals(ConnectionState.Unsupported, client.connectionState.value)
        }
}
