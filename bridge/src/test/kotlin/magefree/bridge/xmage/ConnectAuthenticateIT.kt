package magefree.bridge.xmage

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.util.UUID

/**
 * End-to-end connect + authenticate against a real XMage server.
 *
 * **Env-gated:** runs only when `XMAGE_SERVER` (host:port) is set — otherwise JUnit reports it
 * *skipped*, keeping `./scripts/dev gradle check` hermetic. Against the story-0022 reference server
 * (`authenticationActivated="false"`), any username connects without registration.
 *
 * It proves the full path: build a [mage.remote.Connection], drive `SessionImpl.connectStart`,
 * confirm the [BridgeMageClient] observed `connected(...)`, fetch a non-null main room id, then
 * disconnect cleanly.
 */
@EnabledIfEnvironmentVariable(named = XMageServerTarget.ENV_VAR, matches = ".+")
class ConnectAuthenticateIT {
    @Test
    fun `connects, authenticates, fetches the main room id, and disconnects cleanly`() =
        runBlocking {
            val target = requireNotNull(XMageServerTarget.fromEnv()) { "XMAGE_SERVER must be set for this test" }
            // XMage validates usernames: only [a-z0-9_] (server: invalidUserNamePattern [^a-z0-9_])
            // and length 3..14 (min/maxUserNameLength). "it_" + 8 hex chars = 11 chars, always valid.
            val username = "it_${UUID.randomUUID().toString().replace("-", "").substring(0, 8)}"

            val session = XMageSession()
            val connection =
                XMageConnection.build(
                    host = target.host,
                    port = target.port,
                    username = username,
                    password = "",
                )

            val connected = session.connect(connection)

            try {
                assertTrue(connected, "connectStart should succeed against the reference server")
                assertTrue(session.isConnected, "session should report connected")
                assertNotNull(
                    session.client.lastConnectedMessage,
                    "MageClient.connected(...) should have fired",
                )

                val mainRoomId: UUID? = session.mainRoomId()
                assertNotNull(mainRoomId, "mainRoomId() should return a non-null UUID once connected")
            } finally {
                session.disconnect()
            }

            assertFalse(session.isConnected, "session should be cleanly closed after disconnect()")
        }
}
