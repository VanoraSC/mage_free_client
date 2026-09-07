package magefree.feature.game.table

import magefree.cards.art.CardArtSize
import magefree.designsystem.card.BoardCardSignal
import magefree.network.game.CardType
import magefree.network.game.GameCard
import magefree.network.game.GamePlayer
import magefree.network.game.GameState
import magefree.network.game.PlayableObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The hand, from the snapshot.
 *
 * Almost all of this is a field copy, and a test that a field was copied is a test of nothing. The one
 * thing worth asserting is where the **playable** mark comes from: `GameState.playable` is upstream's
 * own answer, computed with the cost reductions, alternative costs and static effects the client
 * cannot see. A board that decided for itself which cards were castable would be answering a rules
 * question — and it would be wrong in exactly the cases where the answer matters.
 */
class TableCardTest {
    @Test
    fun `the hand is the server's cards, in the server's order`() {
        val state = stateWith(hand = listOf(card("a", "Forest"), card("b", "Grizzly Bears"), card("c", "Pacifism")))

        assertEquals(listOf("a", "b", "c"), handCards(state).map { it.id })
        assertEquals(listOf("Forest", "Grizzly Bears", "Pacifism"), handCards(state).map { it.card.name })
    }

    @Test
    fun `only the cards the server offered are marked playable`() {
        val state =
            stateWith(hand = listOf(card("a", "Forest"), card("b", "Shivan Dragon")))
                .copy(playable = listOf(PlayableObject(objectId = "a")))

        val signals = handCards(state).associate { it.id to it.signal }

        assertEquals(BoardCardSignal.Playable, signals["a"])
        assertNull("an unplayable card carries no signal at all", signals["b"])
    }

    @Test
    fun `nothing in hand is playable when the server offered nothing`() {
        // The ordinary state for most of a game: it is not your turn, so nothing is castable. A board
        // that highlighted "castable if you had the mana" would be lighting the whole hand up all game.
        val state = stateWith(hand = listOf(card("a", "Forest"), card("b", "Shivan Dragon")))

        assertEquals(listOf(null, null), handCards(state).map { it.signal })
    }

    @Test
    fun `a card the server named a printing for carries it, and one it did not carries none`() {
        val state =
            stateWith(
                hand =
                    listOf(
                        card("a", "Forest").copy(setCode = "10E", collectorNumber = "380"),
                        card("b", "Saproling"),
                    ),
            )

        val art = handCards(state).associate { it.id to it.art }

        assertEquals("10E", art["a"]?.setCode)
        assertNull("a card with no printing falls back to the placeholder", art["b"])
    }

    @Test
    fun `a spectator holds nothing`() {
        val state = GameState(gameId = "g", isWatching = true)

        assertEquals(emptyList<TableCard>(), handCards(state))
    }
}

private fun stateWith(hand: List<GameCard>) =
    GameState(
        gameId = "g",
        viewerPlayerId = "me",
        hand = hand,
        players = listOf(GamePlayer(playerId = "me", name = "Me", isViewer = true)),
    )

private fun card(
    id: String,
    name: String,
) = GameCard(id = id, name = name)

/**
 * What a hand card offers, and what it is called.
 *
 * Two decisions live here and only one of them is the client's. **Whether** a card can be acted on is
 * the server's — `GameState.playable` — and a preview that offered a button on a card the server had
 * not offered would submit an action nothing had agreed to. **What to call it** is a wording choice,
 * and lands are played while everything else is cast.
 */
class HandCardActionTest {
    @Test
    fun `a playable land offers Play and a playable spell offers Cast`() {
        val state =
            stateWith(
                hand = listOf(land("forest"), spell("bears")),
            ).copy(playable = listOf(PlayableObject(objectId = "forest"), PlayableObject(objectId = "bears")))

        val actions = handCards(state).associate { it.id to tableCardPreview(it, onAct = {}).action?.label }

        assertEquals(PLAY_LABEL, actions["forest"])
        assertEquals(CAST_LABEL, actions["bears"])
    }

    @Test
    fun `a card the server has not offered gets no button at all`() {
        // Not a disabled one: a greyed-out Cast invites the player to work out why, and the answer is
        // a rules question the client cannot answer. Nothing is the honest control.
        val state = stateWith(hand = listOf(spell("bears")))

        assertNull(tableCardPreview(handCards(state).single(), onAct = {}).action)
    }

    @Test
    fun `no handler means no button, however playable the card is`() {
        // A board that is being looked at rather than played offers nothing to press.
        val state = stateWith(hand = listOf(land("forest"))).copy(playable = listOf(PlayableObject(objectId = "forest")))

        assertNull(tableCardPreview(handCards(state).single(), onAct = null).action)
    }

    @Test
    fun `the panel carries the server's own text and current size`() {
        val state =
            stateWith(
                hand =
                    listOf(
                        spell("bears").copy(
                            power = "4",
                            toughness = "4",
                            rules = listOf("Flying", "{T}: Add {G}."),
                        ),
                    ),
            )

        val preview = tableCardPreview(handCards(state).single())

        assertEquals("4", preview.power)
        assertEquals("4", preview.toughness)
        assertEquals(listOf("Flying", "{T}: Add {G}."), preview.abilities)
    }
}

private fun land(id: String) = GameCard(id = id, name = "Forest", cardTypes = listOf(CardType.Land))

private fun spell(id: String) =
    GameCard(id = id, name = "Grizzly Bears", manaCost = "{1}{G}", cardTypes = listOf(CardType.Creature), isCreature = true)

/**
 * Which image a card asks for, and why it is not always the same one.
 *
 * §7.5 gives full-resolution art to the Full tier and nothing else, for memory and for the first turn
 * after an install. The failure this guards is quiet in both directions: request the small image for an
 * inspected card and the printed text is soft exactly where somebody is reading it; request the large
 * one for every hand tile and the board spends the bandwidth on cards nobody has looked at.
 */
class HandCardArtSizeTest {
    @Test
    fun `a hand tile asks for the downsampled image and an inspected card for the full one`() {
        val state = stateWith(hand = listOf(GameCard(id = "a", name = "Forest", setCode = "10E", collectorNumber = "380")))

        val card = handCards(state).single()

        assertEquals(CardArtSize.SMALL, card.art?.size)
        assertEquals(CardArtSize.LARGE, card.fullArt?.size)
    }

    @Test
    fun `both name the same printing, because it is the same card`() {
        val state = stateWith(hand = listOf(GameCard(id = "a", name = "Forest", setCode = "10E", collectorNumber = "380")))

        val card = handCards(state).single()

        assertEquals(card.art?.setCode, card.fullArt?.setCode)
        assertEquals(card.art?.collectorNumber, card.fullArt?.collectorNumber)
    }

    @Test
    fun `a card the server named no printing for asks for nothing at either size`() {
        val state = stateWith(hand = listOf(GameCard(id = "t", name = "Saproling")))

        val card = handCards(state).single()

        assertNull(card.art)
        assertNull(card.fullArt)
    }
}

/**
 * What the viewer may cast that is **not in their hand**.
 *
 * Flashback, escape, plot, adventure and their many relatives, which the reference client shows only
 * as a badge on the card inside its own zone window — so noticing one means opening the window first.
 * The assertions worth having are about the two halves a client could get wrong on its own: *which*
 * cards these are, which is the server's list and not a search for flashback text, and *where* each
 * one is, which is the difference between a card you hold and a card you do not.
 */
class PlayableElsewhereTest {
    @Test
    fun `a card the server offers from a graveyard is offered beside the hand`() {
        val elsewhere = playableElsewhere(board(offers = listOf("gy-angel")))

        assertEquals(listOf("gy-angel"), elsewhere.map { it.id })
        assertEquals(TableCardZone.Graveyard, elsewhere.single().zone)
    }

    @Test
    fun `and one from exile, with the pile it is in`() {
        val elsewhere = playableElsewhere(board(offers = listOf("x-djinn")))

        assertEquals(TableCardZone.Exile, elsewhere.single().zone)
    }

    @Test
    fun `a card in the hand is not in the group, however offered it is`() {
        // It is in the hand, so the hand draws it. Drawing it twice would have the player counting a
        // card they hold once as two things they can do.
        val elsewhere = playableElsewhere(board(offers = listOf("h-forest", "gy-angel")))

        assertEquals(listOf("gy-angel"), elsewhere.map { it.id })
    }

    @Test
    fun `a graveyard card the server is not offering is not in the group`() {
        // The whole rule: this is `canPlayObjects` filtered by where the card is, and never a guess
        // about what flashback means.
        assertEquals(emptyList<String>(), playableElsewhere(board(offers = emptyList())).map { it.id })
    }

    @Test
    fun `the reasons are the server's own, carried through untouched`() {
        val card = playableElsewhere(board(offers = listOf("gy-angel"))).single()

        assertEquals(listOf("Flashback {3}{W}{W}"), card.playableAbilities)
    }

    @Test
    fun `reading one says where it is and what is offering it`() {
        val preview = tableCardPreview(playableElsewhere(board(offers = listOf("gy-angel"))).single())

        assertEquals("Graveyard", preview.provenance?.zone)
        assertEquals(listOf("Flashback {3}{W}{W}"), preview.provenance?.reasons)
    }

    @Test
    fun `reading a card in hand says none of that, because there is nothing to say`() {
        val preview = tableCardPreview(handCards(board(offers = listOf("h-forest"))).single())

        assertNull("a card in hand has no provenance to report", preview.provenance)
    }

    @Test
    fun `a wire that carries no ability names still says where the card is`() {
        // An older bridge sends ids and no names. Half the answer is still the half that matters most.
        val nameless = board(offers = listOf("gy-angel"), withNames = false)
        val preview = tableCardPreview(playableElsewhere(nameless).single())

        assertEquals("Graveyard", preview.provenance?.zone)
        assertEquals(emptyList<String>(), preview.provenance?.reasons)
    }

    private fun board(
        offers: List<String>,
        withNames: Boolean = true,
    ) = GameState(
        gameId = "g",
        viewerPlayerId = "me",
        hand = listOf(GameCard(id = "h-forest", name = "Forest")),
        playable =
            offers.map { id ->
                PlayableObject(
                    objectId = id,
                    abilityIds = listOf("ab-$id"),
                    abilityNames = if (withNames) listOf(REASONS.getValue(id)) else emptyList(),
                )
            },
        players =
            listOf(
                GamePlayer(
                    playerId = "me",
                    name = "Me",
                    isViewer = true,
                    graveyard = listOf(GameCard(id = "gy-rod", name = "Rod of Ruin"), GameCard(id = "gy-angel", name = "Serra Angel")),
                    exile = listOf(GameCard(id = "x-djinn", name = "Mahamoti Djinn")),
                ),
            ),
    )

    private companion object {
        val REASONS =
            mapOf(
                "h-forest" to "Play Forest",
                "gy-angel" to "Flashback {3}{W}{W}",
                "x-djinn" to "You may cast this card from exile (plotted)",
            )
    }
}
