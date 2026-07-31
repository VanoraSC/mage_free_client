package magefree.bridge.mapping

import kotlinx.coroutines.runBlocking
import magefree.bridge.xmage.XMageConnection
import magefree.bridge.xmage.XMageServerTarget
import magefree.bridge.xmage.XMageSession
import magefree.protocol.GameTypeSummary
import magefree.protocol.RoomUserSummary
import magefree.protocol.TableSummary
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.util.UUID

/**
 * Live end-to-end proof of the read-only lobby relay (story 0027): connect a real [XMageSession] to
 * the reference server and browse the real main room through the same code path the bridge uses —
 * `XMageSession.tables()`/`roomUsers()`/`gameTypes()` → [LobbyRelay] → the per-view mappers →
 * app-schema summaries.
 *
 * The reference server (story 0022) starts with **no open tables**, so browsing tables must yield a
 * successful, well-formed **empty** list (never an error) — this proves the empty/no-error contract the
 * hermetic tests cannot. `gameTypes` must be **non-empty** (the server always offers its formats),
 * which also exercises the [GameTypeMapper.map] getter-forwarding shim against real `GameTypeView`s
 * deserialized from the server. `roomUsers` is asserted only to be a well-formed, non-error list (the
 * reference server does not reflect a just-connected user in `getRoomUsers` synchronously, so it is
 * legitimately empty here — the full `RoomUsersView`→`RoomUserSummary` path is covered hermetically by
 * `RoomUserMapperTest` with real in-memory fixtures).
 *
 * **Env-gated:** enabled only when `XMAGE_SERVER` is set (mirroring 0003's `ConnectAuthenticateIT`);
 * otherwise JUnit reports it *skipped*, keeping `./scripts/dev gradle check` hermetic.
 *
 * ```
 * ./scripts/dev up xmage-server
 * XMAGE_SERVER=xmage-server:17171 ./scripts/dev gradle :bridge:test --tests '*LobbyRelayIT' --rerun-tasks
 * ```
 */
@EnabledIfEnvironmentVariable(named = XMageServerTarget.ENV_VAR, matches = ".+")
class LobbyRelayIT {
    @Test
    fun `browses the real main room - gameTypes non-empty, tables well-formed empty`() =
        runBlocking {
            val target = requireNotNull(XMageServerTarget.fromEnv()) { "XMAGE_SERVER must be set for this test" }
            // XMage validates usernames: [a-z0-9_], length 3..14. "it_" + 8 hex = 11 chars.
            val username = "it_${UUID.randomUUID().toString().replace("-", "").substring(0, 8)}"

            val session = XMageSession()
            val connection =
                XMageConnection.build(
                    host = target.host,
                    port = target.port,
                    username = username,
                    password = "",
                )

            check(session.connect(connection)) { "connectStart should succeed against the reference server" }
            try {
                // Game types: always offered by the server; also covers the getter-forwarding shim.
                val gameTypes: List<GameTypeSummary> = session.gameTypes()
                assertTrue(gameTypes.isNotEmpty(), "the server should offer at least one game type")
                gameTypes.forEach { type ->
                    assertTrue(type.name.isNotBlank(), "each game type should have a name")
                    assertTrue(type.maxPlayers >= type.minPlayers, "maxPlayers should be >= minPlayers for ${type.name}")
                }

                // Tables: the reference server has none open → a well-formed, non-error empty list.
                val tables: List<TableSummary> = session.tables()
                assertNotNull(tables, "tables() must return a list, never null")
                assertTrue(tables.isEmpty(), "the reference server should have no open tables, got ${tables.size}")

                // Room users: assert only a well-formed, non-error list (each entry, if any, has a
                // name). The reference server does not reflect a just-connected user in getRoomUsers
                // synchronously (it updates the room-users list on its own cadence), so this list is
                // legitimately empty here — like tables. The full RoomUsersView→RoomUserSummary path is
                // covered hermetically by RoomUserMapperTest with real in-memory fixtures.
                val roomUsers: List<RoomUserSummary> = session.roomUsers()
                assertNotNull(roomUsers, "roomUsers() must return a list, never null")
                roomUsers.forEach { user ->
                    assertTrue(user.name.isNotBlank(), "each room user should have a name")
                }
            } finally {
                session.disconnect()
            }
        }
}
