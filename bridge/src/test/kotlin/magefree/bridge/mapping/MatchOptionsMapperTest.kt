package magefree.bridge.mapping

import mage.constants.MatchBufferTime
import mage.constants.MatchTimeLimit
import mage.constants.RangeOfInfluence
import mage.constants.SkillLevel
import mage.players.PlayerType
import magefree.protocol.CreateTableOptions
import magefree.protocol.RangeCode
import magefree.protocol.SeatPlayerTypeCode
import magefree.protocol.SkillLevelCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Hermetic tests for [MatchOptionsMapper]. `mage.game.match.MatchOptions` is a plain field/setter type
 * on the bridge classpath, so the mapping is asserted directly over a crafted [CreateTableOptions],
 * plus exhaustive per-enum translation.
 */
class MatchOptionsMapperTest {
    @Test
    fun `toMatchOptions maps every create field onto the MatchOptions`() {
        val options =
            CreateTableOptions(
                name = "Duel Night",
                gameType = "Two Player Duel",
                deckType = "Constructed - Standard",
                players = listOf(SeatPlayerTypeCode.HUMAN, SeatPlayerTypeCode.COMPUTER_MONTE_CARLO),
                rated = true,
                winsNeeded = 2,
                freeMulligans = 1,
                skillLevel = SkillLevelCode.SERIOUS,
                range = RangeCode.ONE,
                matchTimeLimitSeconds = 900,
                matchBufferTimeSeconds = 5,
                spectatorsAllowed = false,
                quitRatio = 80,
                minimumRating = 1200,
                password = "hunter2",
            )

        val match = MatchOptionsMapper.toMatchOptions(options)

        assertEquals("Duel Night", match.name)
        assertEquals("Two Player Duel", match.gameType)
        assertEquals("Constructed - Standard", match.deckType)
        assertEquals(listOf(PlayerType.HUMAN, PlayerType.COMPUTER_MONTE_CARLO), match.playerTypes)
        assertTrue(match.isRated)
        assertEquals(2, match.winsNeeded)
        assertEquals(1, match.freeMulligans)
        assertEquals(SkillLevel.SERIOUS, match.skillLevel)
        assertEquals(RangeOfInfluence.ONE, match.range)
        assertEquals(MatchTimeLimit.MIN__15, match.matchTimeLimit)
        assertEquals(MatchBufferTime.SEC__05, match.matchBufferTime)
        assertFalse(match.isSpectatorsAllowed)
        assertEquals(80, match.quitRatio)
        assertEquals(1200, match.minimumRating)
        assertEquals("hunter2", match.password)
        // A non-Limited deck type is not limited.
        assertFalse(match.isLimited)
    }

    @Test
    fun `a Limited deck type marks the match limited and a null password becomes empty`() {
        val options =
            CreateTableOptions(
                name = "Sealed",
                gameType = "Two Player Duel",
                deckType = "Limited - Sealed",
                password = null,
            )

        val match = MatchOptionsMapper.toMatchOptions(options)

        assertTrue(match.isLimited)
        assertEquals("", match.password)
    }

    @Test
    fun `player types map exhaustively and an UNKNOWN seat falls back to HUMAN`() {
        assertEquals(PlayerType.HUMAN, MatchOptionsMapper.playerTypeOf(SeatPlayerTypeCode.HUMAN))
        assertEquals(PlayerType.COMPUTER_MONTE_CARLO, MatchOptionsMapper.playerTypeOf(SeatPlayerTypeCode.COMPUTER_MONTE_CARLO))
        assertEquals(PlayerType.COMPUTER_MAD, MatchOptionsMapper.playerTypeOf(SeatPlayerTypeCode.COMPUTER_MAD))
        assertEquals(PlayerType.COMPUTER_DRAFT_BOT, MatchOptionsMapper.playerTypeOf(SeatPlayerTypeCode.COMPUTER_DRAFT_BOT))
        assertEquals(PlayerType.HUMAN, MatchOptionsMapper.playerTypeOf(SeatPlayerTypeCode.UNKNOWN))
    }

    @Test
    fun `skill levels map exhaustively and UNKNOWN falls back to CASUAL`() {
        assertEquals(SkillLevel.BEGINNER, MatchOptionsMapper.skillOf(SkillLevelCode.BEGINNER))
        assertEquals(SkillLevel.CASUAL, MatchOptionsMapper.skillOf(SkillLevelCode.CASUAL))
        assertEquals(SkillLevel.SERIOUS, MatchOptionsMapper.skillOf(SkillLevelCode.SERIOUS))
        assertEquals(SkillLevel.CASUAL, MatchOptionsMapper.skillOf(SkillLevelCode.UNKNOWN))
    }

    @Test
    fun `ranges map exhaustively`() {
        assertEquals(RangeOfInfluence.ONE, MatchOptionsMapper.rangeOf(RangeCode.ONE))
        assertEquals(RangeOfInfluence.TWO, MatchOptionsMapper.rangeOf(RangeCode.TWO))
        assertEquals(RangeOfInfluence.ALL, MatchOptionsMapper.rangeOf(RangeCode.ALL))
    }

    @Test
    fun `time limits resolve by seconds and fall back to NONE for an unmatched value`() {
        assertEquals(MatchTimeLimit.NONE, MatchOptionsMapper.timeLimitOf(0))
        assertEquals(MatchTimeLimit.MIN___5, MatchOptionsMapper.timeLimitOf(300))
        assertEquals(MatchTimeLimit.MIN_120, MatchOptionsMapper.timeLimitOf(7200))
        assertEquals(MatchTimeLimit.NONE, MatchOptionsMapper.timeLimitOf(7))

        assertEquals(MatchBufferTime.NONE, MatchOptionsMapper.bufferTimeOf(0))
        assertEquals(MatchBufferTime.SEC__10, MatchOptionsMapper.bufferTimeOf(10))
        assertEquals(MatchBufferTime.NONE, MatchOptionsMapper.bufferTimeOf(7))
    }
}
