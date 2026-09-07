package magefree.feature.game.table

import magefree.network.game.CardType
import magefree.network.game.GameCard
import magefree.network.game.GameState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stack, as the board reads it.
 *
 * The two things worth pinning are the ones a reader would otherwise have to take on trust: that the
 * order is turned round, and that nothing here is derived — the text and the targets are the server's.
 */
class TableStackObjectTest {
    @Test
    fun `the stack reads top first, because that is the order it resolves in`() {
        // Upstream sends it bottom-first, in the order things were put on. A player reading it is
        // asking what happens next, and what happens next is the last thing put on.
        val state = stateWith(spell("first"), spell("second"), spell("third"))

        assertEquals(listOf("third", "second", "first"), tableStack(state).map { it.id })
    }

    @Test
    fun `an object carries the server's own text for it, not the printing's`() {
        // `GameCard.rules` on a stack object is the ability as it exists now. Blank lines are dropped
        // because upstream pads with them, and an empty line drawn on the board is a gap nobody asked
        // for.
        val state = stateWith(spell("bolt", rules = listOf("Lightning Bolt deals 3 damage to any target.", "   ")))

        assertEquals(listOf("Lightning Bolt deals 3 damage to any target."), tableStack(state).single().rules)
    }

    @Test
    fun `an object carries what it is pointing at, in the server's order`() {
        val state = stateWith(spell("bolt", targets = listOf("bears", "them")))

        assertEquals(listOf("bears", "them"), tableStack(state).single().targetIds)
    }

    @Test
    fun `a spell on the stack is never tapped, in combat or marked playable`() {
        // All three are properties of a permanent, and a spell on the stack is not one. Leaning a stack
        // object over would be saying something about it that cannot be true.
        val entry = tableStack(stateWith(spell("bolt"))).single()

        assertTrue("a stack object is not a permanent", !entry.state.tapped && entry.state.signals.isEmpty())
    }

    @Test
    fun `an empty stack is empty, which is the ordinary state`() {
        assertTrue(tableStack(GameState(gameId = "g")).isEmpty())
    }

    private fun stateWith(vararg cards: GameCard) = GameState(gameId = "g", stack = cards.toList())

    private fun spell(
        id: String,
        rules: List<String> = emptyList(),
        targets: List<String> = emptyList(),
    ) = GameCard(
        id = id,
        name = "Lightning Bolt",
        setCode = "10E",
        collectorNumber = "203",
        cardTypes = listOf(CardType.Instant),
        rules = rules,
        targets = targets,
    )
}
