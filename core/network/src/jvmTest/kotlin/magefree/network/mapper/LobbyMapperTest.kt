package magefree.network.mapper

import magefree.model.SkillLevel
import magefree.model.TableState
import magefree.network.mapper.LobbyMapper.toModel
import magefree.protocol.GameTypeSummary
import magefree.protocol.RoomUserSummary
import magefree.protocol.SkillLevelCode
import magefree.protocol.TableStateCode
import magefree.protocol.TableSummary
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Golden coverage of the  lobby mapper boundary: real `:protocol` summaries in, pure
 * `:core:model` types out. Asserts every browse-relevant field is carried and that the wire enum codes
 * map to the model enums (including the `Unknown` forward-compat fallback).
 */
class LobbyMapperTest {
    @Test
    fun tableSummaryMapsEveryBrowseField() {
        val summary =
            TableSummary(
                tableId = "t-1",
                name = "Pete's Table",
                controllerName = "pete",
                gameType = "Two Player Duel",
                deckType = "Constructed - Standard",
                state = TableStateCode.WAITING,
                seatsFilled = 1,
                seatsTotal = 2,
                isTournament = false,
                isRated = true,
                isPassworded = false,
                isLimited = false,
                skillLevel = SkillLevelCode.CASUAL,
                createdAtEpochMs = 1_700_000_000_000L,
            )

        val table = summary.toModel()

        assertEquals("t-1", table.id)
        assertEquals("Pete's Table", table.name)
        assertEquals("pete", table.host)
        assertEquals("Two Player Duel", table.gameType)
        assertEquals("Constructed - Standard", table.deckType)
        assertEquals(TableState.Waiting, table.state)
        assertEquals(1, table.seatsFilled)
        assertEquals(2, table.seatsTotal)
        assertEquals(false, table.isTournament)
        assertEquals(true, table.isRated)
        assertEquals(false, table.isPassworded)
        assertEquals(false, table.isLimited)
        assertEquals(SkillLevel.Casual, table.skillLevel)
        assertEquals(1_700_000_000_000L, table.createdAtEpochMs)
    }

    @Test
    fun tableStateCodesMapOneForOne() {
        val expected =
            mapOf(
                TableStateCode.WAITING to TableState.Waiting,
                TableStateCode.READY_TO_START to TableState.ReadyToStart,
                TableStateCode.STARTING to TableState.Starting,
                TableStateCode.DRAFTING to TableState.Drafting,
                TableStateCode.CONSTRUCTING to TableState.Constructing,
                TableStateCode.DUELING to TableState.Dueling,
                TableStateCode.SIDEBOARDING to TableState.Sideboarding,
                TableStateCode.FINISHED to TableState.Finished,
                TableStateCode.UNKNOWN to TableState.Unknown,
            )
        expected.forEach { (code, state) ->
            assertEquals(state, sampleTable(state = code).toModel().state)
        }
    }

    @Test
    fun skillLevelCodesMapOneForOne() {
        val expected =
            mapOf(
                SkillLevelCode.BEGINNER to SkillLevel.Beginner,
                SkillLevelCode.CASUAL to SkillLevel.Casual,
                SkillLevelCode.SERIOUS to SkillLevel.Serious,
                SkillLevelCode.UNKNOWN to SkillLevel.Unknown,
            )
        expected.forEach { (code, level) ->
            assertEquals(level, sampleTable(skill = code).toModel().skillLevel)
        }
    }

    @Test
    fun roomUserSummaryMapsEveryField() {
        val user =
            RoomUserSummary(
                name = "pete",
                flag = "United States",
                matchHistory = "W:3 L:1",
                games = "in 1 game",
                ping = "42ms",
                generalRating = 1500,
            ).toModel()

        assertEquals("pete", user.name)
        assertEquals("United States", user.flag)
        assertEquals("W:3 L:1", user.matchHistory)
        assertEquals("in 1 game", user.games)
        assertEquals("42ms", user.ping)
        assertEquals(1500, user.generalRating)
    }

    @Test
    fun gameTypeSummaryMapsEveryField() {
        val type = GameTypeSummary(name = "Two Player Duel", minPlayers = 2, maxPlayers = 2).toModel()

        assertEquals("Two Player Duel", type.name)
        assertEquals(2, type.minPlayers)
        assertEquals(2, type.maxPlayers)
    }

    private fun sampleTable(
        state: TableStateCode = TableStateCode.WAITING,
        skill: SkillLevelCode = SkillLevelCode.CASUAL,
    ) = TableSummary(
        tableId = "t",
        name = "n",
        controllerName = "h",
        gameType = "g",
        deckType = "d",
        state = state,
        seatsFilled = 0,
        seatsTotal = 2,
        isTournament = false,
        isRated = false,
        isPassworded = false,
        isLimited = false,
        skillLevel = skill,
        createdAtEpochMs = 0L,
    )
}
