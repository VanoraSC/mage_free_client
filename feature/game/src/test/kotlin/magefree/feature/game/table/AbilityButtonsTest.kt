package magefree.feature.game.table

import magefree.designsystem.card.CardDisplay
import magefree.designsystem.card.CardPreviewAction
import magefree.designsystem.card.CardPreviewState
import magefree.network.game.CardType
import magefree.network.game.GameCard
import magefree.network.game.PlayableObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A planeswalker's abilities, as buttons.
 *
 * What the server offers is not decided here, so what is worth pinning is the drawing of it: every loyalty
 * ability is a button whether or not it can be used, only the ones the server listed can be pressed, each
 * reads as the ability it activates — through upstream's fifty-character clip — and a line that is not a
 * loyalty ability is not mistaken for one.
 *
 * The rules text is Liliana, Death's Majesty's as the server sends it, from the board Pete reported: her
 * +1 offered, her −3 not (no creature card in the graveyard to target), and her −7 out of reach at six
 * loyalty.
 */
class AbilityButtonsTest {
    @Test
    fun `every loyalty ability is a button, in the card's order, and only the offered ones can be pressed`() {
        val buttons = abilityButtons(majesty(), listOf(offered(listOf("plus-1"), listOf(PLUS))), activatable = true)

        assertEquals(listOf(PLUS, MINUS, ULTIMATE), buttons.map { it.label })
        assertEquals(listOf("plus-1", null, null), buttons.map { it.abilityId })
    }

    @Test
    fun `a name upstream clipped at fifty characters still finds its line`() {
        // `PlayableObjectStats.load`: longer than fifty, and it is cut to forty-nine and `...`.
        val buttons = abilityButtons(majesty(), listOf(offered(listOf("minus-3"), listOf(MINUS.take(49) + "..."))), activatable = true)

        assertEquals(listOf(null, "minus-3", null), buttons.map { it.abilityId })
    }

    @Test
    fun `outside a window where she can be activated, every ability is drawn and none can be pressed`() {
        val buttons = abilityButtons(majesty(), listOf(offered(listOf("plus-1"), listOf(PLUS))), activatable = false)

        assertEquals(3, buttons.size)
        assertTrue(buttons.none { it.canActivate })
    }

    @Test
    fun `a planeswalker the server lists nothing for draws every ability greyed`() {
        val buttons = abilityButtons(majesty(), emptyList(), activatable = true)

        assertEquals(listOf(PLUS, MINUS, ULTIMATE), buttons.map { it.label })
        assertTrue(buttons.none { it.canActivate })
    }

    @Test
    fun `a loyalty line is told from any other line by its cost`() {
        // `PayLoyaltyCost` writes `0` for a zero cost and `PayVariableLoyaltyCost` writes `-X`. A static
        // ability that only mentions +1/+1 is not a loyalty ability, and neither is an activated ability
        // with a colon in it that is paid for some other way — both stay text.
        val walker =
            majesty().copy(
                rules =
                    listOf(
                        "Creatures you control get +1/+1.",
                        "{T}: Add {B}.",
                        "0: Draw a card.",
                        "-X: Destroy target creature with mana value X.",
                    ),
            )

        val buttons = abilityButtons(walker, emptyList(), activatable = false)

        assertEquals(listOf("0: Draw a card.", "-X: Destroy target creature with mana value X."), buttons.map { it.label })
    }

    @Test
    fun `an offered ability that is not one of its loyalty lines is still a button, after them`() {
        val buttons =
            abilityButtons(majesty(), listOf(offered(listOf("plus-1", "granted"), listOf(PLUS, "{T}: Add {B}."))), activatable = true)

        assertEquals("{T}: Add {B}.", buttons.last().label)
        assertEquals("granted", buttons.last().abilityId)
        assertNull("no line of hers claimed", buttons.last().rule)
    }

    @Test
    fun `an offered ability with no name at all is still a button, numbered`() {
        // An older bridge sends ids without names.
        val buttons = abilityButtons(majesty(), listOf(offered(listOf("a"), emptyList())), activatable = true)

        assertEquals("Ability 1", buttons.last().label)
        assertEquals("a", buttons.last().abilityId)
    }

    @Test
    fun `a permanent that is not a planeswalker gets no buttons`() {
        val bears = majesty().copy(cardTypes = listOf(CardType.Creature))

        assertTrue(abilityButtons(bears, listOf(offered(listOf("a"), listOf(PLUS))), activatable = true).isEmpty())
    }

    @Test
    fun `pressable buttons replace Play, a greyed one presses nothing, and every line that became a button leaves the text`() {
        val preview =
            CardPreviewState(
                card = CardDisplay(name = "Liliana, Death's Majesty"),
                abilities = listOf(PLUS, MINUS, "A static ability."),
                action = CardPreviewAction(label = "Play") {},
            )
        val pressed = mutableListOf<String>()

        val shown =
            preview.withAbilityButtons(listOf(AbilityButton("plus-1", PLUS, PLUS), AbilityButton(null, MINUS, MINUS))) { pressed += it }
        shown.abilityActions.forEach { it.onAct() }

        assertNull(shown.action)
        assertEquals(listOf(true, false), shown.abilityActions.map { it.enabled })
        assertEquals(listOf("A static ability."), shown.abilities)
        assertEquals(listOf("plus-1"), pressed)
    }

    @Test
    fun `a planeswalker none of whose abilities can be pressed keeps its action`() {
        // A planeswalker that is the target of a spell: the press on it picks it, and greyed buttons must not
        // take that away.
        val target = CardPreviewAction(label = "Choose as target") {}
        val preview = CardPreviewState(card = CardDisplay(name = "Liliana, Death's Majesty"), action = target)

        val shown = preview.withAbilityButtons(listOf(AbilityButton(null, PLUS, PLUS))) {}

        assertEquals(target, shown.action)
    }

    @Test
    fun `no buttons leaves the preview exactly as it was`() {
        val preview = CardPreviewState(card = CardDisplay(name = "Grizzly Bears"), action = CardPreviewAction(label = "Play") {})

        assertEquals(preview, preview.withAbilityButtons(emptyList()) {})
    }

    private fun majesty() =
        GameCard(
            id = "pw-1",
            name = "Liliana, Death's Majesty",
            cardTypes = listOf(CardType.Planeswalker),
            rules = listOf(PLUS, MINUS, ULTIMATE),
        )

    private fun offered(
        ids: List<String>,
        names: List<String>,
    ) = PlayableObject(objectId = "pw-1", abilityIds = ids, abilityNames = names)
}

private const val PLUS = "+1: Create a 2/2 black Zombie creature token. Mill two cards."
private const val MINUS =
    "-3: Return target creature card from your graveyard to the battlefield. That creature is a black Zombie in addition to its other colors and types."
private const val ULTIMATE = "-7: Destroy all non-Zombie creatures."
