package magefree.designsystem.card

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Board tier's decisions that are logic rather than layout.
 *
 * A card can carry several signals at once but has only one border, so which one wins is a rule the
 * player learns and relies on. Keeping it here — pure, ordered by an enum's declaration — means the
 * rule cannot quietly change while the rendering still looks plausible.
 */
class BoardCardStateTest {
    @Test
    fun `no signals means no border signal`() {
        assertNull(primarySignal(emptySet()))
    }

    @Test
    fun `a lone signal is the primary one, whichever it is`() {
        BoardCardSignal.entries.forEach { signal ->
            assertEquals(signal, primarySignal(setOf(signal)))
        }
    }

    @Test
    fun `the most immediate signal claims the border`() {
        // The case this exists for: a creature already attacking is targeted by removal. What the
        // player has to react to is the removal, not the attack they already declared.
        assertEquals(
            BoardCardSignal.Targeted,
            primarySignal(setOf(BoardCardSignal.Attacking, BoardCardSignal.Targeted)),
        )
        assertEquals(
            BoardCardSignal.Attacking,
            primarySignal(setOf(BoardCardSignal.Playable, BoardCardSignal.Attacking)),
        )
    }

    @Test
    fun `precedence is total, so any combination resolves to exactly one signal`() {
        val all = BoardCardSignal.entries.toSet()
        assertEquals(BoardCardSignal.entries.first(), primarySignal(all))

        // Every pair resolves to whichever is declared first — the rule holds across the whole set,
        // not just the pairs anyone thought to write down.
        BoardCardSignal.entries.forEach { first ->
            BoardCardSignal.entries.forEach { second ->
                val winner = primarySignal(setOf(first, second))
                val expected = if (first.ordinal <= second.ordinal) first else second
                assertEquals("$first vs $second", expected, winner)
            }
        }
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
        // The server decides what a permanent's power and toughness are. If it sends one and not the
        // other, hiding the label would hide server state; a dash shows what arrived.
        assertEquals("3/-", boardStatsLabel("3", null))
        assertEquals("-/4", boardStatsLabel(null, "4"))
    }

    @Test
    fun `a counter kind this build has never heard of still renders as itself`() {
        // Upstream's counter kinds are an open set — poison, energy, experience, and hundreds more.
        assertEquals("+1/+1 2", counterLabel(BoardCounter("+1/+1", 2)))
        assertEquals("poison 3", counterLabel(BoardCounter("poison", 3)))
        assertTrue(counterLabel(BoardCounter("wibble", 1)).contains("wibble"))
    }
}
