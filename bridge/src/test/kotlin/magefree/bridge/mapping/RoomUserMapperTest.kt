package magefree.bridge.mapping

import kotlinx.serialization.encodeToString
import mage.view.RoomUsersView
import mage.view.UsersView
import magefree.protocol.ProtocolJson
import magefree.protocol.RoomUserList
import magefree.protocol.RoomUserSummary
import magefree.protocol.ServerMessage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Hermetic golden/assertion test for [RoomUserMapper], built on **real in-memory `mage.view.*`
 * fixtures**: both `RoomUsersView(List<UsersView>, int, int, int)` and the 11-arg `UsersView`
 * constructor are public and on the bridge classpath (`mage-common`), so — unlike `TableView`/
 * `GameTypeView` — the raw view can be constructed here (as the `ChatMessageMapperTest` does with
 * `ChatMessage`). This exercises the full `RoomUsersView` → `List<RoomUserSummary>` path, including the
 * wrapped-`UsersView` flattening and per-user field projection.
 */
class RoomUserMapperTest {
    /** Builds a `UsersView` with the constructor's argument order (flag, name, matchHistory, ...). */
    private fun usersView(
        userName: String,
        flagName: String,
        matchHistory: String,
        infoGames: String,
        infoPing: String,
        generalRating: Int,
    ): UsersView =
        UsersView(
            // flagName =
            flagName,
            // userName =
            userName,
            // matchHistory =
            matchHistory,
            // matchQuitRatio =
            0,
            // tourneyHistory =
            "",
            // tourneyQuitRatio =
            0,
            // infoGames =
            infoGames,
            // infoPing =
            infoPing,
            // generalRating =
            generalRating,
            // constructedRating =
            0,
            // limitedRating =
            0,
        )

    @Test
    fun `maps a room's wrapped users to per-user summaries`() {
        val view =
            RoomUsersView(
                listOf(
                    usersView("alice", "United States", "3-1", "1 games", "42 ms", 1500),
                    usersView("bob", "", "0-0", "", "10 ms", 1200),
                ),
                // numberActiveGames =
                2,
                // numberGameThreads =
                2,
                // numberMaxGames =
                100,
            )

        val summaries = RoomUserMapper.map(view)

        assertEquals(
            listOf(
                RoomUserSummary(
                    name = "alice",
                    flag = "United States",
                    matchHistory = "3-1",
                    games = "1 games",
                    ping = "42 ms",
                    generalRating = 1500,
                ),
                RoomUserSummary(
                    name = "bob",
                    flag = "",
                    matchHistory = "0-0",
                    games = "",
                    ping = "10 ms",
                    generalRating = 1200,
                ),
            ),
            summaries,
        )
    }

    @Test
    fun `an empty room maps to an empty list`() {
        val view = RoomUsersView(emptyList(), 0, 0, 100)
        assertEquals(emptyList<RoomUserSummary>(), RoomUserMapper.map(view))
    }

    @Test
    fun `mapped room users round-trip through the wire envelope`() {
        val view =
            RoomUsersView(
                listOf(usersView("alice", "United States", "3-1", "1 games", "42 ms", 1500)),
                0,
                0,
                100,
            )

        val message = RoomUserList(users = RoomUserMapper.map(view))
        val encoded = ProtocolJson.json.encodeToString<ServerMessage>(message)
        val decoded = ProtocolJson.json.decodeFromString<ServerMessage>(encoded)
        assertEquals(message, decoded)
    }
}
