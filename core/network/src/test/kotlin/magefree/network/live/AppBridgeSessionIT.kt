package magefree.network.live

import kotlinx.coroutines.runBlocking
import magefree.model.ConnectionState
import magefree.model.SessionEvent
import magefree.network.ktor.KtorBridgeClient
import magefree.network.live.LiveBridge.Companion.awaitValue
import magefree.network.live.LiveBridge.Companion.requireTarget
import magefree.network.live.LiveBridge.Companion.uniqueUsername
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Live proof that the **app's own** session + lobby stack talks to a **real bridge** (story 0045).
 *
 * Everything above `:bridge` — [KtorBridgeClient], the handshake, `SessionRelay`'s `Login`,
 * `PendingRequests` correlation, `LobbyClientImpl` and `LobbyMapper` — had only ever run against
 * `FakeBridgeClient`, i.e. against its own idea of the wire. A fake cannot disagree with the thing it
 * stands in for, so a `requestId` correlation mismatch, an `ignoreUnknownKeys` drift, a sealed-type
 * discriminator rename or a `ServerHello` major bump would all pass hermetically and fail in production.
 * These tests are the first thing in the repo that would notice.
 *
 * The assertions are on the app's **domain** types ([magefree.model.Session],
 * [magefree.model.GameType], [magefree.model.RoomUser], [magefree.model.LobbyTable]) rather than on wire
 * shapes: what is being verified is that the app's *mapped* view of a real server is correct.
 *
 * **Env-gated:** enabled only when `BRIDGE_URL` is set (mirroring `:bridge`'s `XMAGE_SERVER` gate);
 * otherwise JUnit reports these *skipped*, so `:core:network:check` stays hermetic and offline.
 *
 * ```
 * ./scripts/dev up bridge          # brings up xmage-server + the bridge
 * BRIDGE_URL=localhost:8080 ./gradlew :core:network:testDebugUnitTest --tests '*live*' --rerun-tasks
 * ```
 */
class AppBridgeSessionIT {
    @Test
    fun `connects and logs in over a real socket, yielding a Connected session`() {
        val target = requireTarget()
        runBlocking {
            val username = uniqueUsername()
            val bridge = LiveBridge(this, target)
            try {
                val session = bridge.connect(username)

                assertEquals(
                    "the established session should name the bridge we dialled",
                    target.toServerTarget(),
                    session.target,
                )
                assertEquals("the established session should carry the username we logged in as", username, session.username)
                assertNotNull(
                    "the ServerHello's bridgeVersion should reach the domain Session handle",
                    session.bridgeVersion,
                )
                assertTrue(
                    "the bridge build string should not be blank, got '${session.bridgeVersion}'",
                    session.bridgeVersion!!.isNotBlank(),
                )

                // The hot state the app's status surface reads, driven by the same events.
                assertEquals(
                    "the client's connectionState should follow the session to Connected",
                    ConnectionState.Connected,
                    bridge.client.connectionState.value,
                )
                // A version mismatch or a rejected login is a *first-class* event, not an exception, so
                // it would otherwise be invisible: assert none of them happened.
                assertTrue(
                    "no failure event should precede a successful connect, got ${bridge.events}",
                    bridge.events.none {
                        it is SessionEvent.AuthFailed || it is SessionEvent.VersionUnsupported || it is SessionEvent.Error
                    },
                )
                assertTrue(
                    "the relay should report Connecting before Connected, got ${bridge.events}",
                    bridge.events.indexOf(SessionEvent.Connecting) >= 0 &&
                        bridge.events.indexOf(SessionEvent.Connecting) <
                        bridge.events.indexOfFirst { it is SessionEvent.Connected },
                )
            } finally {
                bridge.close()
            }
        }
    }

    @Test
    fun `reads the lobby over the live socket into mapped domain types`() {
        val target = requireTarget()
        runBlocking {
            val username = uniqueUsername()
            val bridge = LiveBridge(this, target)
            try {
                bridge.connect(username)

                // Three separate request/response exchanges multiplexed over the one live socket: each
                // proves `PendingRequests` matched a real reply to a real `requestId` (a correlation
                // mismatch would time out here, not silently pass as it would against a fake).
                val gameTypes = bridge.lobby.gameTypes()
                assertTrue("the server should offer at least one game type", gameTypes.isNotEmpty())
                val duel = gameTypes.singleOrNull { it.name == DUEL }
                assertNotNull("the reference server's config offers '$DUEL', got $gameTypes", duel)
                assertEquals("'$DUEL' seats exactly two players (min)", 2, duel!!.minPlayers)
                assertEquals("'$DUEL' seats exactly two players (max)", 2, duel.maxPlayers)

                // Our own login must show up in the room's user list — the strongest thing a lobby read
                // can assert without hosting anything. It arrives on the room's ~2s snapshot timer.
                val users =
                    awaitValue(
                        what = "the room user list to include '$username'",
                        read = { bridge.lobby.roomUsers() },
                        condition = { list -> list.any { it.name == username } },
                    )
                val self = users.single { it.name == username }
                assertEquals("the room user should carry our name verbatim", username, self.name)

                // The table list maps too. The reference room is normally empty (`LobbyRelayIT` relies on
                // that), so this asserts the call and the shape; the *content* of a real LobbyTable is
                // asserted in AppBridgeHostTableIT, where a known table exists.
                val tables = bridge.lobby.tables()
                assertTrue(
                    "every listed table should carry a usable id, got $tables",
                    tables.all { it.id.isNotBlank() && it.seatsTotal >= it.seatsFilled },
                )
            } finally {
                bridge.close()
            }
        }
    }

    private companion object {
        /** The two-seat, non-tournament game type the reference server offers (see its `config.xml`). */
        const val DUEL = "Two Player Duel"
    }
}
