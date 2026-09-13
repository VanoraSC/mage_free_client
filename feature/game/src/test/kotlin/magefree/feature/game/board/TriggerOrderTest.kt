package magefree.feature.game.board

import magefree.network.game.GameCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The translation between the order a player arranges their triggers in and the questions the server asks.
 *
 * **Both ways to get it wrong are silent.** The server asks which trigger goes onto the stack *first* —
 * resolving last — and asks N−1 times for N triggers, placing the last with no question. A reversal or an
 * off-by-one here still ends with an ordered stack, just not the one the player made.
 */
class TriggerOrderTest {
    @Test
    fun `triggers from cards of one name with one rule are one group, and a different card is another`() {
        val groups = triggerGroups(listOf(warden("t1"), attendant("t2"), warden("t3"), attendant("t4"), champion("t5")))

        assertEquals(listOf(listOf("t1", "t3"), listOf("t2", "t4"), listOf("t5")), groups.map { it.abilityIds })
    }

    @Test
    fun `two triggers already aimed at different things are not one group`() {
        val groups = triggerGroups(listOf(warden("t1").copy(targets = listOf("c1")), warden("t2").copy(targets = listOf("c2"))))

        assertEquals(2, groups.size)
    }

    @Test
    fun `the rule an auto-order is remembered by names its source, as the server writes it`() {
        val card = GameCard(id = "t1", name = "Wall of Omens", rules = listOf("When {this} enters, draw a card."))

        assertEquals("When Wall of Omens enters, draw a card.", card.autoOrderRule())
    }

    @Test
    fun `a trigger with no source name or no rule cannot key an auto-order`() {
        assertNull(GameCard(id = "t1", name = "", rules = listOf("When {this} enters, draw a card.")).autoOrderRule())
        assertNull(GameCard(id = "t1", name = "Wall of Omens").autoOrderRule())
    }

    @Test
    fun `the first question is answered with the trigger that resolves last`() {
        assertEquals("c", nextTriggerToStack(resolveOrder = listOf("a", "b", "c"), offered = setOf("b", "c", "a")))
    }

    @Test
    fun `answering every question in turn leaves the stack reading as arranged`() {
        val arranged = listOf("a", "b", "c", "d")
        val bottomUp = mutableListOf<String>()
        var offered = setOf("d", "b", "a", "c")
        // The server asks while more than one is left, and places the last one itself.
        while (offered.size > 1) {
            val next = nextTriggerToStack(arranged, offered)!!
            bottomUp += next
            offered = offered - next
        }
        bottomUp += offered.single()

        assertEquals("the top of the stack is what resolves first", arranged, bottomUp.reversed())
    }

    @Test
    fun `a question offering a trigger the arrangement does not hold is not answered`() {
        assertNull(nextTriggerToStack(resolveOrder = listOf("a", "b"), offered = setOf("a", "fired-since")))
    }

    @Test
    fun `no arrangement answers nothing`() {
        assertNull(nextTriggerToStack(resolveOrder = emptyList(), offered = setOf("a")))
        assertNull(nextTriggerToStack(resolveOrder = listOf("a"), offered = emptySet()))
    }

    @Test
    fun `a trigger that fired after the player arranged comes first, and the rest keep their order`() {
        val groups = triggerGroups(listOf(warden("t1"), attendant("t2"), champion("t3")))

        assertEquals(listOf("t3", "t2", "t1"), arrangedBy(groups, resolveOrder = listOf("t2", "t1")).map { it.id })
    }

    private fun warden(id: String) = GameCard(id = id, name = "Soul Warden", rules = listOf(GAIN))

    private fun attendant(id: String) = GameCard(id = id, name = "Soul's Attendant", rules = listOf(MAY_GAIN))

    // The same words as Soul's Attendant, from a different card: a different ability.
    private fun champion(id: String) = GameCard(id = id, name = "Auriok Champion", rules = listOf(MAY_GAIN))
}

private const val GAIN = "Whenever another creature enters the battlefield, you gain 1 life."
private const val MAY_GAIN = "Whenever another creature enters the battlefield, you may gain 1 life."
