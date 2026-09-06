package magefree.feature.game.table

import magefree.network.game.CardType
import magefree.network.game.GameCard
import magefree.network.game.GamePlayer
import magefree.network.game.GameState
import magefree.network.game.PlayableObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A graveyard, as the rail reads it.
 *
 * The assertions worth having are about *order* and *emptiness*, because those are the two things a
 * client is tempted to decide for itself. A graveyard's order is the order things died in and the top
 * of it is the server's last entry; an empty one is a real state that has to survive as far as the
 * rail, which draws a placeholder rather than nothing.
 */
class TableGraveyardTest {
    @Test
    fun `the card on top is the one the server listed last`() {
        val zone = tableGraveyards(state()).first { it.playerId == "me" }

        assertEquals("Serra Angel", zone.topCard?.card?.name)
        assertEquals(3, zone.count)
    }

    @Test
    fun `nothing is sorted — the order is the order things died in`() {
        val zone = tableGraveyards(state()).first { it.playerId == "me" }

        assertEquals(listOf("Llanowar Elves", "Rod of Ruin", "Serra Angel"), zone.cards.map { it.card.name })
    }

    @Test
    fun `an empty graveyard has no top card, and is still a graveyard`() {
        // The rail draws a placeholder from this, so an empty zone has to arrive rather than being
        // filtered out on the way — a seat whose graveyard vanished would move its life total the
        // first time one of its creatures died.
        val zone = tableGraveyards(state()).first { it.playerId == "them" }

        assertNull(zone.topCard)
        assertEquals(0, zone.count)
    }

    @Test
    fun `every seat gets one, opponents before the viewer`() {
        val zones = tableGraveyards(state())

        assertEquals(listOf("them", "me"), zones.map { it.playerId })
        assertTrue("the viewer should be last", zones.last().isViewer)
    }

    @Test
    fun `a card the server is offering carries the playable signal, wherever it is`() {
        // Flashback, escape and their relatives. The board says what the server says: it does not
        // decide for itself that a card in a graveyard cannot be cast.
        val zone = tableGraveyards(state(playable = "gy-angel")).first { it.playerId == "me" }

        assertTrue("the offered card should be marked playable", zone.topCard?.isPlayable == true)
        assertTrue(
            "the others should not be",
            zone.cards
                .first()
                .isPlayable
                .not(),
        )
    }

    private fun state(playable: String? = null) =
        GameState(
            gameId = "g1",
            playable = listOfNotNull(playable?.let { PlayableObject(objectId = it) }),
            players =
                listOf(
                    GamePlayer(
                        playerId = "me",
                        name = "You",
                        isViewer = true,
                        graveyardCount = 3,
                        graveyard =
                            listOf(
                                card("gy-elves", "Llanowar Elves"),
                                card("gy-rod", "Rod of Ruin"),
                                card("gy-angel", "Serra Angel"),
                            ),
                    ),
                    GamePlayer(playerId = "them", name = "Opponent"),
                ),
        )

    private fun card(
        id: String,
        name: String,
    ) = GameCard(id = id, name = name, cardTypes = listOf(CardType.Creature), isCreature = true)
}
