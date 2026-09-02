package magefree.designsystem.card

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The Board tier's decisions that are logic rather than layout.
 *
 * The one that matters is **which signal a card emphasises**, because a card carries several at once
 * and has only one border. That is not a property of the card — it is a property of the moment, and
 * getting it wrong makes a board unreadable in exactly the situations where reading it matters.
 */
class BoardCardStateTest {
    @Test
    fun `a card carrying nothing relevant to the moment emphasises nothing`() {
        assertNull(focalSignal(emptySet(), BoardFocus.Targeting))
        assertNull(focalSignal(setOf(BoardCardSignal.Playable), BoardFocus.Combat))
    }

    @Test
    fun `while a spell is on the stack, being targeted is what the card says`() {
        // The case the whole focus model exists for: a creature that is already attacking gets
        // targeted by removal. The attack is old news; the removal is what the player must answer.
        val signals = setOf(BoardCardSignal.Attacking, BoardCardSignal.Targeted)

        assertEquals(BoardCardSignal.Targeted, focalSignal(signals, BoardFocus.Targeting))
        assertEquals(BoardCardSignal.Attacking, secondarySignal(signals, BoardFocus.Targeting))
    }

    @Test
    fun `during a combat declaration the same card says the opposite`() {
        val signals = setOf(BoardCardSignal.Attacking, BoardCardSignal.Targeted)

        assertEquals(BoardCardSignal.Attacking, focalSignal(signals, BoardFocus.Combat))
        assertEquals(BoardCardSignal.Targeted, secondarySignal(signals, BoardFocus.Combat))
    }

    @Test
    fun `combat promotes attackers and blockers alike`() {
        assertEquals(
            BoardCardSignal.Blocking,
            focalSignal(setOf(BoardCardSignal.Blocking, BoardCardSignal.Playable), BoardFocus.Combat),
        )
    }

    @Test
    fun `every focus promotes something, so no moment leaves the board mute`() {
        BoardFocus.entries.forEach { focus ->
            val promoted = focus.focalSignals
            assertEquals(
                "$focus promotes nothing, so no card can ever be emphasised during it",
                true,
                promoted.isNotEmpty(),
            )
            promoted.forEach { signal ->
                assertEquals(signal, focalSignal(setOf(signal), focus))
            }
        }
    }

    @Test
    fun `a secondary signal is never the focal one repeated`() {
        BoardFocus.entries.forEach { focus ->
            val all = BoardCardSignal.entries.toSet()
            val focal = focalSignal(all, focus)
            val secondary = secondarySignal(all, focus)
            assertNotEquals("$focus emphasised the same signal twice", focal, secondary)
        }
    }

    @Test
    fun `a card with one signal has nothing to put on the muted edge`() {
        assertNull(secondarySignal(setOf(BoardCardSignal.Targeted), BoardFocus.Targeting))
    }

    @Test
    fun `every signal has its own colour, so two never look alike`() {
        val colours = BoardCardSignal.entries.map { it.color }
        assertEquals("two signals share a colour: $colours", colours.size, colours.toSet().size)
    }

    @Test
    fun `a creature shows its power and toughness`() {
        assertEquals("2/2", boardStatsLabel("2", "2"))
        assertEquals("10/10", boardStatsLabel("10", "10"))
    }

    @Test
    fun `a non-creature shows no stats at all`() {
        assertNull(boardStatsLabel(null, null))
    }

    @Test
    fun `half a stat line still renders, because the client does not get to interpret it`() {
        assertEquals("3/-", boardStatsLabel("3", null))
        assertEquals("-/4", boardStatsLabel(null, "4"))
    }

    @Test
    fun `a counter shows its count and nothing else`() {
        assertEquals("2", counterCountLabel(2))
        assertEquals("99", counterCountLabel(99))
    }

    @Test
    fun `an absurd count is capped rather than overflowing the circle`() {
        assertEquals("99+", counterCountLabel(100))
        assertEquals("99+", counterCountLabel(1000))
    }

    @Test
    fun `every badge carries a short label, so a placeholder is never blank`() {
        BoardBadge.entries.forEach { badge ->
            assertEquals("${badge.name} has no short label", true, badge.shortLabel.isNotBlank())
        }
    }
}
