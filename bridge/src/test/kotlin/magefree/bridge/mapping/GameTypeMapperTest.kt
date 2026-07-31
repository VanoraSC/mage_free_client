package magefree.bridge.mapping

import kotlinx.serialization.encodeToString
import magefree.protocol.GameTypeList
import magefree.protocol.GameTypeSummary
import magefree.protocol.ProtocolJson
import magefree.protocol.ServerMessage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Hermetic test for [GameTypeMapper].
 *
 * `mage.view.GameTypeView`'s only constructor takes a `mage.game.match.MatchType`, which is not on the
 * bridge classpath (only `mage-common` is), so the raw view cannot be constructed here. The mapping
 * logic lives in the pure [GameTypeMapper.build] over the extracted getter values and is asserted
 * directly. Unlike tables, the thin [GameTypeMapper.map] getter-forwarding shim **is** covered
 * end-to-end by the live `LobbyRelayIT` (the reference server offers a non-empty game-type list).
 */
class GameTypeMapperTest {
    @Test
    fun `build projects the name and player range`() {
        assertEquals(
            GameTypeSummary(name = "Two Player Duel", minPlayers = 2, maxPlayers = 2),
            GameTypeMapper.build(name = "Two Player Duel", minPlayers = 2, maxPlayers = 2),
        )
        assertEquals(
            GameTypeSummary(name = "Commander Free For All", minPlayers = 2, maxPlayers = 8),
            GameTypeMapper.build(name = "Commander Free For All", minPlayers = 2, maxPlayers = 8),
        )
    }

    @Test
    fun `mapped game types round-trip through the wire envelope`() {
        val message =
            GameTypeList(
                gameTypes =
                    listOf(
                        GameTypeMapper.build("Two Player Duel", 2, 2),
                        GameTypeMapper.build("Commander Free For All", 2, 8),
                    ),
            )
        val encoded = ProtocolJson.json.encodeToString<ServerMessage>(message)
        val decoded = ProtocolJson.json.decodeFromString<ServerMessage>(encoded)
        assertEquals(message, decoded)
    }
}
