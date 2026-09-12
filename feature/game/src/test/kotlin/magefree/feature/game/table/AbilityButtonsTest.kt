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
 * A planeswalker's offered abilities, as buttons.
 *
 * What the server offers is not decided here, so what is worth pinning is the drawing of it: every
 * offered ability gets a button in the server's order, each reads as the ability it activates — through
 * upstream's fifty-character clip — and nothing is a button that the server did not list.
 */
class AbilityButtonsTest {
    @Test
    fun `a planeswalker gets a button per offered ability, in the server's order, named by its rules line`() {
        val buttons = abilityButtons(liliana(), listOf(offered(listOf("minus-2", "plus-1"), listOf(MINUS, PLUS))))

        assertEquals(listOf("minus-2", "plus-1"), buttons.map { it.abilityId })
        assertEquals(listOf(MINUS, PLUS), buttons.map { it.label })
    }

    @Test
    fun `a name upstream clipped at fifty characters reads as the whole line`() {
        // `PlayableObjectStats.load`: longer than fifty, and it is cut to forty-nine and `...`.
        val buttons = abilityButtons(liliana(), listOf(offered(listOf("minus-6"), listOf(ULTIMATE.take(49) + "..."))))

        assertEquals(ULTIMATE, buttons.single().label)
        assertEquals(ULTIMATE, buttons.single().rule)
    }

    @Test
    fun `a name that matches no rules line keeps the name, and claims no line`() {
        val buttons = abilityButtons(liliana(), listOf(offered(listOf("x"), listOf("+2: Something granted"))))

        assertEquals("+2: Something granted", buttons.single().label)
        assertNull(buttons.single().rule)
    }

    @Test
    fun `an ability with no name at all is numbered`() {
        // An older bridge sends ids without names.
        val buttons = abilityButtons(liliana(), listOf(offered(listOf("a", "b"), emptyList())))

        assertEquals(listOf("Ability 1", "Ability 2"), buttons.map { it.label })
    }

    @Test
    fun `a permanent that is not a planeswalker gets no buttons`() {
        val bears = liliana().copy(cardTypes = listOf(CardType.Creature))

        assertTrue(abilityButtons(bears, listOf(offered(listOf("a"), listOf(PLUS)))).isEmpty())
    }

    @Test
    fun `a planeswalker the server is not offering gets no buttons`() {
        assertTrue(abilityButtons(liliana(), listOf(PlayableObject(objectId = "someone-else", abilityIds = listOf("a")))).isEmpty())
    }

    @Test
    fun `the buttons replace the action, and a line that became a button is not also read as text`() {
        val preview =
            CardPreviewState(
                card = CardDisplay(name = "Liliana of the Veil"),
                abilities = listOf(PLUS, MINUS),
                action = CardPreviewAction(label = "Play") {},
            )
        val pressed = mutableListOf<String>()

        val shown = preview.withAbilityButtons(listOf(AbilityButton("plus-1", PLUS, PLUS))) { pressed += it }
        shown.abilityActions.single().onAct()

        assertNull(shown.action)
        assertEquals(listOf(MINUS), shown.abilities)
        assertEquals(listOf("plus-1"), pressed)
    }

    @Test
    fun `no buttons leaves the preview exactly as it was`() {
        val preview = CardPreviewState(card = CardDisplay(name = "Grizzly Bears"), action = CardPreviewAction(label = "Play") {})

        assertEquals(preview, preview.withAbilityButtons(emptyList()) {})
    }

    private fun liliana() =
        GameCard(
            id = "pw-1",
            name = "Liliana of the Veil",
            cardTypes = listOf(CardType.Planeswalker),
            rules = listOf(PLUS, MINUS, ULTIMATE),
        )

    private fun offered(
        ids: List<String>,
        names: List<String>,
    ) = PlayableObject(objectId = "pw-1", abilityIds = ids, abilityNames = names)
}

private const val PLUS = "+1: Each player discards a card."
private const val MINUS = "−2: Target player sacrifices a creature."
private const val ULTIMATE =
    "−6: Separate all permanents target player controls into two piles. That player sacrifices all permanents in the pile of their choice."
