package magefree.feature.game.table

import magefree.network.game.CardType
import magefree.network.game.GameCard
import magefree.network.game.GamePlayer
import magefree.network.game.GameState
import magefree.network.game.GameZone
import magefree.network.game.PlayableObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The piles, as the rail reads them.
 *
 * The assertions worth having are about *order*, *emptiness* and *which exile a card is in*, because
 * those are the three things a client is tempted to decide for itself. A graveyard's order is the
 * order things died in and the top of it is the server's last entry; an empty pile is a real state
 * that has to survive as far as the rail, which draws a placeholder rather than nothing; and the
 * special exile is the one judgement here, made from two signals the server gives and no others.
 */
class TableZonesTest {
    @Test
    fun `the card on top is the one the server listed last`() {
        val zone = graveyardOf("me")

        assertEquals("Serra Angel", zone.topCard?.card?.name)
        assertEquals(3, zone.count)
    }

    @Test
    fun `nothing is sorted — the order is the order things died in`() {
        assertEquals(
            listOf("Llanowar Elves", "Rod of Ruin", "Serra Angel"),
            graveyardOf("me").cards.map { it.card.name },
        )
    }

    @Test
    fun `an empty graveyard has no top card, and is still a pile`() {
        // The rail draws a placeholder from this, so an empty zone has to arrive rather than being
        // filtered out on the way — a seat whose graveyard vanished would move its life total the
        // first time one of its creatures died.
        val zone = graveyardOf("them")

        assertNull(zone.topCard)
        assertEquals(0, zone.count)
    }

    @Test
    fun `every seat gets every pile, opponents before the viewer`() {
        val zones = tableZones(state())

        assertEquals(listOf("them", "them", "them", "me", "me", "me"), zones.map { it.playerId })
        assertEquals(
            listOf(TableZoneKind.Graveyard, TableZoneKind.SpecialExile, TableZoneKind.Exile),
            zones.filter { it.playerId == "me" }.map { it.kind },
        )
    }

    @Test
    fun `a card exiled into a named zone is in the special pile, not the ordinary one`() {
        // A plotted card. It is coming back, and the whole reason to separate the piles is that a
        // card which is coming back is not the same fact as a card which is gone.
        val special = pile("me", TableZoneKind.SpecialExile)
        val ordinary = pile("me", TableZoneKind.Exile)

        assertEquals(listOf("Shivan Dragon"), special.cards.map { it.card.name })
        assertEquals(listOf("Air Elemental"), ordinary.cards.map { it.card.name })
    }

    @Test
    fun `a card the server is offering from exile is special even with no zone name`() {
        // Airbend: no named zone, castable only while the server says so. The name signal misses it
        // entirely, which is why the playable signal is read as well.
        val zones = tableZones(state(playable = "x-air"))

        assertEquals(
            listOf("Air Elemental", "Shivan Dragon"),
            zones
                .first { it.playerId == "me" && it.kind == TableZoneKind.SpecialExile }
                .cards
                .map { it.card.name }
                .sorted(),
        )
    }

    @Test
    fun `upstream's own default pile name does not make a card special`() {
        val zones = tableZones(state(defaultZoneName = "Permanent"))

        assertTrue(
            "a card in the default exile pile is only exiled",
            zones.first { it.playerId == "me" && it.kind == TableZoneKind.Exile }.cards.any { it.card.name == "Air Elemental" },
        )
    }

    @Test
    fun `a card the server is offering carries the playable signal, wherever it is`() {
        // Flashback, escape and their relatives. The board says what the server says: it does not
        // decide for itself that a card in a graveyard cannot be cast.
        val zones = tableZones(state(playable = "gy-angel"))
        val graveyard = zones.first { it.playerId == "me" && it.kind == TableZoneKind.Graveyard }

        assertTrue("the offered card should be marked playable", graveyard.topCard?.isPlayable == true)
        assertTrue(
            "the others should not be",
            graveyard.cards
                .first()
                .isPlayable
                .not(),
        )
    }

    private fun graveyardOf(playerId: String) = pile(playerId, TableZoneKind.Graveyard)

    private fun pile(
        playerId: String,
        kind: TableZoneKind,
    ) = tableZones(state()).first { it.playerId == playerId && it.kind == kind }

    private fun state(
        playable: String? = null,
        defaultZoneName: String = "Plots of You - Exile",
    ) = GameState(
        gameId = "g1",
        playable = listOfNotNull(playable?.let { PlayableObject(objectId = it) }),
        // The pile the plotted card sits in, named by the effect that made it. The general exile pile
        // is not listed at all, which is the ordinary case: `GamePlayer.exile` still carries the card.
        exile = listOf(GameZone(name = defaultZoneName, cards = listOf(card("x-dragon", "Shivan Dragon")))),
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
                    exileCount = 2,
                    exile = listOf(card("x-air", "Air Elemental"), card("x-dragon", "Shivan Dragon")),
                ),
                GamePlayer(playerId = "them", name = "Opponent"),
            ),
    )

    private fun card(
        id: String,
        name: String,
    ) = GameCard(id = id, name = name, cardTypes = listOf(CardType.Creature), isCreature = true)
}
