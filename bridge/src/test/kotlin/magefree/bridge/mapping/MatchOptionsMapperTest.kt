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
import magefree.protocol.TableActionCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Hermetic tests for [MatchOptionsMapper]. `mage.game.match.MatchOptions` is a plain field/setter type
 * on the bridge classpath, so the mapping is asserted directly over a crafted [CreateTableOptions],
 * plus exhaustive per-enum translation.
 *
 * The clock cases pin story 0044's contract: a budget XMage has no value for is a typed
 * [UnsupportedMatchOption] failure that reaches the host as a failed `CREATE` result — it is never
 * silently downgraded to "no clock" under a successful create.
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

        val match = MatchOptionsMapper.toMatchOptions(options).getOrThrow()

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

        val match = MatchOptionsMapper.toMatchOptions(options).getOrThrow()

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
    fun `clocks resolve by exact seconds and an unsupported value resolves to null, never NONE`() {
        assertEquals(MatchTimeLimit.NONE, MatchOptionsMapper.timeLimitOf(0))
        assertEquals(MatchTimeLimit.MIN___5, MatchOptionsMapper.timeLimitOf(300))
        assertEquals(MatchTimeLimit.MIN_120, MatchOptionsMapper.timeLimitOf(7200))
        // 7s is no XMage budget: null (unsupported), *not* NONE — which is the real "no clock" value.
        assertNull(MatchOptionsMapper.timeLimitOf(7))

        assertEquals(MatchBufferTime.NONE, MatchOptionsMapper.bufferTimeOf(0))
        assertEquals(MatchBufferTime.SEC__10, MatchOptionsMapper.bufferTimeOf(10))
        assertNull(MatchOptionsMapper.bufferTimeOf(7))
    }

    @Test
    fun `the supported budgets are the upstream enumerations and include no-clock zero`() {
        val timeLimits = MatchOptionsMapper.supportedTimeLimitSeconds()
        assertEquals(MatchTimeLimit.entries.size, timeLimits.size)
        assertTrue(timeLimits.contains(MatchTimeLimit.NONE.prioritySecs))
        assertTrue(timeLimits.contains(MatchTimeLimit.MIN___5.prioritySecs))
        assertEquals(timeLimits.sorted(), timeLimits)
        assertFalse(timeLimits.contains(7))

        val buffers = MatchOptionsMapper.supportedBufferSeconds()
        assertEquals(MatchBufferTime.entries.size, buffers.size)
        assertTrue(buffers.contains(MatchBufferTime.NONE.bufferSecs))
        assertEquals(buffers.sorted(), buffers)
    }

    @Test
    fun `an unsupported match time limit is rejected as a typed failure, never silently no-clock`() {
        val options = clocked(timeLimitSeconds = 7)

        val result = MatchOptionsMapper.toMatchOptions(options)

        assertTrue(result.isFailure)
        val failure = result.exceptionOrNull()
        assertTrue(failure is UnsupportedMatchOption, "failure=$failure")
        failure as UnsupportedMatchOption
        assertEquals(MatchOptionsMapper.TIME_LIMIT, failure.field)
        assertEquals(7, failure.seconds)
        assertEquals(MatchOptionsMapper.supportedTimeLimitSeconds(), failure.supported)
        // The reason the host is shown names the value and what is on offer.
        assertTrue(failure.message!!.contains("7s"), "message=${failure.message}")
        assertTrue(failure.message!!.contains(MatchTimeLimit.MIN___5.prioritySecs.toString()))
    }

    @Test
    fun `an unsupported buffer time is rejected as a typed failure`() {
        val options = clocked(bufferSeconds = 7)

        val failure = MatchOptionsMapper.toMatchOptions(options).exceptionOrNull()

        assertTrue(failure is UnsupportedMatchOption, "failure=$failure")
        failure as UnsupportedMatchOption
        assertEquals(MatchOptionsMapper.BUFFER_TIME, failure.field)
        assertEquals(7, failure.seconds)
        assertEquals(MatchOptionsMapper.supportedBufferSeconds(), failure.supported)
    }

    @Test
    fun `every supported budget maps onto the MatchOptions rather than being rejected`() {
        MatchTimeLimit.entries.forEach { limit ->
            val mapped = MatchOptionsMapper.toMatchOptions(clocked(timeLimitSeconds = limit.prioritySecs))
            assertEquals(limit, mapped.getOrThrow().matchTimeLimit)
        }
        MatchBufferTime.entries.forEach { buffer ->
            val mapped = MatchOptionsMapper.toMatchOptions(clocked(bufferSeconds = buffer.bufferSecs))
            assertEquals(buffer, mapped.getOrThrow().matchBufferTime)
        }
    }

    @Test
    fun `a rejected create replies a failed CREATE result carrying the reason`() {
        val options = clocked(timeLimitSeconds = 7)
        val failure = MatchOptionsMapper.toMatchOptions(options).exceptionOrNull()!!

        val reply = TableRelay.rejectedMessage(failure.message!!)

        assertEquals(TableActionCode.CREATE, reply.action)
        assertFalse(reply.ok)
        assertEquals(failure.message, reply.reason)
    }

    /** A minimal create request differing only in its clocks — the field under test in these cases. */
    private fun clocked(
        timeLimitSeconds: Int = 0,
        bufferSeconds: Int = 0,
    ): CreateTableOptions =
        CreateTableOptions(
            name = "Clocked",
            gameType = "Two Player Duel",
            deckType = "Constructed",
            matchTimeLimitSeconds = timeLimitSeconds,
            matchBufferTimeSeconds = bufferSeconds,
        )
}
