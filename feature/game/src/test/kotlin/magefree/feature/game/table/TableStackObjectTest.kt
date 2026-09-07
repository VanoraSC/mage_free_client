package magefree.feature.game.table

import magefree.network.game.CardType
import magefree.network.game.GameCard
import magefree.network.game.GamePermanent
import magefree.network.game.GamePlayer
import magefree.network.game.GameState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

/**
 * Power and toughness, and the objects that do not have them.
 *
 * Upstream fills both on **every** object, so a land arrives as a 0/0 and an enchantment as a 0/0.
 * `BoardUi` gated on `isCreature` from the start and wrote down why; the table tier did not, and every
 * land on the board drew "0/0" under it. Found by eye once the marks doubled in size.
 */
class TableCardStatsTest {
    @Test
    fun `a land on the battlefield has no power or toughness`() {
        val side = battlefieldModel(battlefieldWith(swamp())).viewer!!
        val land = side.landStacks().single().representative

        assertNull("a Swamp is not a 0/0", land.state.power)
        assertNull(land.state.toughness)
    }

    @Test
    fun `an enchantment on the battlefield has none either`() {
        val side = battlefieldModel(battlefieldWith(mastery())).viewer!!
        val enchantment = side.inRole(PermanentRole.Other).single()

        assertNull(enchantment.state.power)
        assertNull(enchantment.state.toughness)
    }

    @Test
    fun `a creature keeps the numbers the server sent`() {
        // The other half: what makes an object a creature is `isCreature`, upstream's own game-aware
        // predicate. An animated land is a creature and says so.
        val side = battlefieldModel(battlefieldWith(bear())).viewer!!
        val creature = side.inRole(PermanentRole.Creature).single()

        assertEquals("2", creature.state.power)
        assertEquals("2", creature.state.toughness)
    }

    @Test
    fun `a spell on the stack shows none, and a creature spell shows its own`() {
        assertNull(tableStack(GameState(gameId = "g", stack = listOf(mastery()))).single().state.power)
        assertEquals("2", tableStack(GameState(gameId = "g", stack = listOf(bear()))).single().state.power)
    }

    @Test
    fun `a card in hand shows none unless it is a creature`() {
        assertNull(handCards(GameState(gameId = "g", hand = listOf(swamp()))).single().power)
        assertEquals("2", handCards(GameState(gameId = "g", hand = listOf(bear()))).single().power)
    }

    private fun battlefieldWith(card: GameCard) =
        GameState(
            gameId = "g",
            viewerPlayerId = "me",
            players =
                listOf(
                    GamePlayer(
                        playerId = "me",
                        name = "Me",
                        isViewer = true,
                        battlefield = listOf(GamePermanent(card = card)),
                    ),
                ),
        )

    /** As the server really sends them: a land is a 0/0, and so is an enchantment. */
    private fun swamp() =
        GameCard(
            id = "swamp",
            name = "Swamp",
            setCode = "10E",
            collectorNumber = "371",
            power = "0",
            toughness = "0",
            cardTypes = listOf(CardType.Land),
        )

    private fun mastery() =
        GameCard(
            id = "mastery",
            name = "Liliana's Mastery",
            setCode = "HOU",
            collectorNumber = "78",
            power = "0",
            toughness = "0",
            cardTypes = listOf(CardType.Enchantment),
        )

    private fun bear() =
        GameCard(
            id = "bear",
            name = "Grizzly Bears",
            setCode = "10E",
            collectorNumber = "268",
            power = "2",
            toughness = "2",
            isCreature = true,
            cardTypes = listOf(CardType.Creature),
        )
}
