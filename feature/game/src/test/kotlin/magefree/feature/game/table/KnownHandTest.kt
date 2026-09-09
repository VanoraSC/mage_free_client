package magefree.feature.game.table

import magefree.network.game.GameCard
import magefree.network.game.GamePlayer
import magefree.network.game.GameState
import magefree.network.game.GameZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What you have been shown of an opponent's hand.
 *
 * **The server will not tell you twice**, so this is a memory — and a memory is exactly the thing that
 * goes wrong quietly. The assertions are about the two rules that keep it honest: a card leaves the
 * moment it is visible anywhere, and the server's count always wins.
 */
class KnownHandTest {
    @Test
    fun `a revealed card is remembered after the reveal is gone`() {
        // `GameState.revealed` is cleared by the server on the next update. Without this the player
        // has lost the information the moment they look away.
        val seen = SeenCards().fold(stateWith(revealed = listOf("thoughtseize")))

        assertEquals(listOf("thoughtseize"), seen.fold(stateWith()).cards.map { it.id })
    }

    @Test
    fun `a card leaves the moment it is seen anywhere else`() {
        // Played, discarded, exiled, put onto the battlefield — every one of those makes it visible in
        // a zone the board draws, and that is the whole signal. Nothing here reasons about the game.
        val seen = SeenCards().fold(stateWith(revealed = listOf("thoughtseize")))

        assertEquals(
            emptyList<String>(),
            seen.fold(stateWith(theirGraveyard = listOf("thoughtseize"))).cards.map { it.id },
        )
    }

    @Test
    fun `the server's count is the ceiling, always`() {
        // A memory longer than the hand means a card left in a way the board could not see. The count
        // is the thing to believe, so the memory is truncated rather than allowed to claim cards.
        val seen = SeenCards().fold(stateWith(revealed = listOf("a", "b", "c")))

        val hand = seen.knownHandFor(stateWith(theirHandCount = 1), "them")

        assertEquals(1, hand?.cards?.size)
        assertEquals(0, hand?.hidden)
    }

    @Test
    fun `what has not been shown is a number, not a blank card`() {
        val seen = SeenCards().fold(stateWith(revealed = listOf("a")))

        val hand = seen.knownHandFor(stateWith(theirHandCount = 4), "them")

        assertEquals(listOf("a"), hand?.cards?.map { it.id })
        assertEquals(3, hand?.hidden)
        assertEquals(4, hand?.count)
    }

    @Test
    fun `the viewer's own hand is never a guess`() {
        // It is `GameState.hand`, sent in full. Inferring it would be inventing uncertainty.
        assertNull(SeenCards().knownHandFor(stateWith(), "me"))
    }

    @Test
    fun `more than one opponent gets counts, because the inference does not survive it`() {
        // A revealed card the board cannot see is in *a* hand. With one opponent that is theirs; with
        // two it is a guess, and nothing on the wire makes it otherwise.
        val state =
            GameState(
                gameId = "g",
                viewerPlayerId = "me",
                players =
                    listOf(
                        GamePlayer(playerId = "me", name = "Me", isViewer = true),
                        GamePlayer(playerId = "them", name = "Them", handCount = 3),
                        GamePlayer(playerId = "other", name = "Other", handCount = 3),
                    ),
            )

        assertNull(SeenCards().knownHandFor(state, "them"))
    }

    @Test
    fun `a card revealed out of the viewer's own hand is not remembered about the opponent`() {
        // An opponent's Duress reveals *your* hand. Those cards are yours and are already drawn.
        val seen = SeenCards().fold(stateWith(revealed = listOf("mine"), myHand = listOf("mine")))

        assertEquals(emptyList<String>(), seen.cards.map { it.id })
    }

    private fun stateWith(
        revealed: List<String> = emptyList(),
        myHand: List<String> = emptyList(),
        theirGraveyard: List<String> = emptyList(),
        theirHandCount: Int = 3,
    ) = GameState(
        gameId = "g",
        viewerPlayerId = "me",
        hand = myHand.map { GameCard(id = it, name = it) },
        revealed =
            if (revealed.isEmpty()) {
                emptyList()
            } else {
                listOf(
                    GameZone(
                        name = "Duress",
                        cards =
                            revealed.map {
                                GameCard(id = it, name = it)
                            },
                    ),
                )
            },
        players =
            listOf(
                GamePlayer(playerId = "me", name = "Me", isViewer = true),
                GamePlayer(
                    playerId = "them",
                    name = "Them",
                    handCount = theirHandCount,
                    graveyard = theirGraveyard.map { GameCard(id = it, name = it) },
                ),
            ),
    )
}
