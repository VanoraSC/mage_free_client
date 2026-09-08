package magefree.bridge.mapping

import mage.players.net.UserData
import magefree.protocol.PriorityStops
import magefree.protocol.SetPriorityStops
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The stops, as upstream's own type.
 *
 * The mapping is field for field, so what is worth testing is not the arithmetic but the two ways a
 * field-for-field mapping goes wrong: a side that ends up mirroring the other, and a flag that is only
 * ever set rather than cleared. Both would pass a test that set every stop to `true`.
 */
class PriorityStopsMapperTest {
    @Test
    fun `each side lands on its own SkipPrioritySteps`() {
        val applied =
            PriorityStopsMapper.apply(
                SetPriorityStops(
                    yourTurn = PriorityStops(upkeep = true, draw = false, main1 = true, main2 = true),
                    opponentTurn = PriorityStops(upkeep = false, draw = true, main1 = false, main2 = false),
                ),
                UserData.getDefaultUserDataView(),
            )

        val yours = applied.userSkipPrioritySteps.yourTurn
        val theirs = applied.userSkipPrioritySteps.opponentTurn
        assertTrue(yours.isUpkeep, "your upkeep was asked for")
        assertFalse(theirs.isUpkeep, "their upkeep was not — the sides must not share a value")
        assertFalse(yours.isDraw)
        assertTrue(theirs.isDraw)
        assertTrue(yours.isMain1)
        assertFalse(theirs.isMain1)
    }

    @Test
    fun `a cleared stop is written as false, not left at the upstream default`() {
        // `main1` and `main2` start `true` in upstream's own `SkipPrioritySteps`. A mapper that only
        // ever set flags would silently keep the server stopping at a main phase the player turned off.
        val applied =
            PriorityStopsMapper.apply(
                SetPriorityStops(
                    yourTurn = PriorityStops(main1 = false, main2 = false),
                    opponentTurn = PriorityStops(main1 = false, main2 = false),
                ),
                UserData.getDefaultUserDataView(),
            )

        assertFalse(applied.userSkipPrioritySteps.yourTurn.isMain1)
        assertFalse(applied.userSkipPrioritySteps.yourTurn.isMain2)
        assertFalse(applied.userSkipPrioritySteps.opponentTurn.isMain2)
    }

    @Test
    fun `the rest of the profile is carried across untouched`() {
        // The server *merges* what it is sent (`Session.setUserData` -> `UserData.update`), so a
        // preference the mapper dropped would be merged back as a default rather than left alone.
        val base = UserData.getDefaultUserDataView().apply { avatarId = 42 }
        val before = base.flagName

        val applied = PriorityStopsMapper.apply(SetPriorityStops(), base)

        assertSame(base, applied, "the session's own profile is what gets pushed upstream")
        assertEquals(42, applied.avatarId)
        assertEquals(before, applied.flagName)
    }
}
