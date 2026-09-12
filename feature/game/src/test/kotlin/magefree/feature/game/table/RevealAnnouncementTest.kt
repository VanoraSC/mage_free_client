package magefree.feature.game.table

import magefree.network.game.CardType
import magefree.network.game.GameCard
import magefree.network.game.GamePrompt
import magefree.network.game.GameState
import magefree.network.game.GameZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which reveals are news.
 *
 * The rule is small and every branch of it is a way to be wrong in front of a player: announce nothing
 * and a reveal with no question vanishes unseen, which is the bug; announce too much and a Duress with a
 * legal target covers its own question, or a reveal carried across two snapshots pops up twice.
 */
class RevealAnnouncementTest {
    @Test
    fun `a reveal nobody is asked about is announced, named after its effect`() {
        // The case this exists for: Inquisition of Kozilek against a hand with no legal target. The
        // server reveals, finds nothing to choose, and asks nothing.
        val reveals = state(revealed = listOf(inquisition())).revealsToAnnounce(emptySet())

        assertEquals(listOf("Inquisition of Kozilek"), reveals.map { it.name })
        assertEquals(listOf("h-bolt", "h-forest"), reveals.single().cards.map { it.id })
    }

    @Test
    fun `a reveal whose cards the target question already shows is not announced`() {
        // Duress with a legal target: the hand is revealed *and* offered as the question's candidates,
        // so it is already in front of the player. An overlay would cover the question it belongs to.
        val question = GamePrompt.Target(message = "Choose a card", cards = inquisition().cards)

        val reveals = state(revealed = listOf(inquisition()), prompt = question).revealsToAnnounce(emptySet())

        assertEquals(emptyList<RevealAnnouncement>(), reveals)
    }

    @Test
    fun `a reveal the pile question already shows is not announced`() {
        val question =
            GamePrompt.ChoosePile(
                message = "Choose a pile",
                pile1 = listOf(card("h-bolt", "Lightning Bolt")),
                pile2 = listOf(card("h-forest", "Forest")),
            )

        val reveals = state(revealed = listOf(inquisition()), prompt = question).revealsToAnnounce(emptySet())

        assertEquals(emptyList<RevealAnnouncement>(), reveals)
    }

    @Test
    fun `a question showing only some of the cards does not hide the reveal`() {
        // Covering part of a reveal is not showing it. The player would never see the rest.
        val question = GamePrompt.Target(message = "Choose a card", cards = listOf(card("h-bolt", "Lightning Bolt")))

        val reveals = state(revealed = listOf(inquisition()), prompt = question).revealsToAnnounce(emptySet())

        assertEquals(listOf("Inquisition of Kozilek"), reveals.map { it.name })
    }

    @Test
    fun `a reveal already announced is not announced again`() {
        // Upstream can carry one reveal across two pushes. Putting it down must not bring it back.
        val first = state(revealed = listOf(inquisition()))
        val announced = first.revealsToAnnounce(emptySet()).map { it.key }.toSet()

        assertEquals(emptyList<RevealAnnouncement>(), first.revealsToAnnounce(announced))
    }

    @Test
    fun `the same cards revealed on a later turn are a new reveal`() {
        // A second Inquisition next turn is news again, even into an unchanged hand.
        val announced = state(revealed = listOf(inquisition()), turn = 3).revealsToAnnounce(emptySet()).map { it.key }.toSet()

        val later = state(revealed = listOf(inquisition()), turn = 5).revealsToAnnounce(announced)

        assertEquals(listOf("Inquisition of Kozilek"), later.map { it.name })
    }

    @Test
    fun `an empty reveal announces nothing`() {
        val reveals = state(revealed = listOf(GameZone(name = "Thoughtseize"))).revealsToAnnounce(emptySet())

        assertTrue(reveals.isEmpty())
    }

    @Test
    fun `two reveals in one snapshot are both announced, oldest first`() {
        val reveals =
            state(
                revealed =
                    listOf(
                        inquisition(),
                        GameZone(name = "Brainstorm", cards = listOf(card("l-top", "Island"))),
                    ),
            ).revealsToAnnounce(emptySet())

        assertEquals(listOf("Inquisition of Kozilek", "Brainstorm"), reveals.map { it.name })
    }

    private fun inquisition() =
        GameZone(
            name = "Inquisition of Kozilek",
            cards = listOf(card("h-bolt", "Lightning Bolt"), card("h-forest", "Forest")),
        )

    private fun state(
        revealed: List<GameZone>,
        prompt: GamePrompt? = null,
        turn: Int = 3,
    ) = GameState(gameId = "g1", turn = turn, revealed = revealed, prompt = prompt)

    private fun card(
        id: String,
        name: String,
    ) = GameCard(id = id, name = name, cardTypes = listOf(CardType.Instant))
}
