package magefree.feature.game.table

import magefree.network.game.CardType
import magefree.network.game.GameCard
import magefree.network.game.GamePermanent
import magefree.network.game.GamePlayer
import magefree.network.game.GameState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which cards moved, and where they fly.
 *
 * Each case is a way to be wrong in front of a player: a card flying to a zone it did not go to, a card
 * flying twice, or a card that did not move flying at all. The destinations are anchors, so these are
 * about the reading of two snapshots and nothing about where anything is drawn.
 */
class ZoneMovesTest {
    @Test
    fun `the first snapshot a board sees moves nothing`() {
        assertTrue(zoneMoves(null, table()).isEmpty())
        assertTrue(zoneMoves(GameState(gameId = "g"), table(myBattlefield = listOf(swamp("s1")))).isEmpty())
    }

    @Test
    fun `a land played from hand flies from its hand card to its own place on the battlefield`() {
        val move = zoneMoves(table(hand = listOf(swamp("s1"))), table(myBattlefield = listOf(swamp("s1")))).single()

        assertEquals(ZoneMoveKind.PlayedFromHand, move.kind)
        assertEquals(handCardAnchorId("s1"), move.from)
        assertEquals(listOf("s1"), move.to)
        assertTrue("its own node is new", move.freshDestination)
    }

    @Test
    fun `a land joining a stack lands on the stack before its own node`() {
        // The stack's front card does not move when a copy joins it, so the new copy's own box may never
        // be measured; the stack's is, and it is where the land goes.
        val move =
            zoneMoves(
                table(hand = listOf(swamp("s2")), myBattlefield = listOf(swamp("s1"))),
                table(myBattlefield = listOf(swamp("s1"), swamp("s2"))),
            ).single()

        assertEquals(listOf("s1", "s2"), move.to)
    }

    @Test
    fun `a card the viewer discards flies from their hand to their graveyard`() {
        val move = zoneMoves(table(hand = listOf(bolt("b1"))), table(myGraveyard = listOf(bolt("b1")))).single()

        assertEquals(ZoneMoveKind.Discarded, move.kind)
        assertEquals(handCardAnchorId("b1"), move.from)
        assertEquals(listOf(graveyardAnchorId(ME)), move.to)
    }

    @Test
    fun `a spell cast from hand is not a zone move, because the stack flies it`() {
        val cast = table(stack = listOf(bolt("ability-of-b1")))

        assertTrue(zoneMoves(table(hand = listOf(bolt("b1"))), cast).isEmpty())
    }

    @Test
    fun `a creature that dies flies from where it stood to its owner's graveyard`() {
        val move = zoneMoves(table(theirBattlefield = listOf(bears("c1"))), table(theirGraveyard = listOf(bears("c1")))).single()

        assertEquals(ZoneMoveKind.ToGraveyard, move.kind)
        assertEquals("c1", move.from)
        assertEquals(listOf(graveyardAnchorId(THEM)), move.to)
    }

    @Test
    fun `a permanent returned to the viewer's hand flies to the card it becomes`() {
        val move = zoneMoves(table(myBattlefield = listOf(bears("c1"))), table(hand = listOf(bears("c1")))).single()

        assertEquals(ZoneMoveKind.ToHand, move.kind)
        assertEquals(listOf(handCardAnchorId("c1")), move.to)
        assertTrue("the hand card is a new node", move.freshDestination)
    }

    @Test
    fun `a permanent returned to an opponent's hand flies to that hand, which is only a count`() {
        val move =
            zoneMoves(
                table(theirBattlefield = listOf(bears("c1")), theirHand = 3),
                table(theirHand = 4),
            ).single()

        assertEquals(ZoneMoveKind.ToHand, move.kind)
        assertEquals(listOf(opponentHandAnchorId(THEM)), move.to)
        assertTrue("the card it becomes is a new last card in that hand", move.freshDestination)
    }

    @Test
    fun `an exiled permanent flies to its owner's count panel`() {
        val move = zoneMoves(table(theirBattlefield = listOf(bears("c1"))), table(theirExile = listOf(bears("c1")))).single()

        assertEquals(ZoneMoveKind.ToOther, move.kind)
        assertEquals(listOf(zoneCountsAnchorId(THEM)), move.to)
    }

    @Test
    fun `a permanent put somewhere hidden while no hand grew flies to the count panel`() {
        // Tucked into a library: no id anywhere, and no hand took it.
        val move = zoneMoves(table(theirBattlefield = listOf(bears("c1")), theirHand = 3), table(theirHand = 3)).single()

        assertEquals(ZoneMoveKind.ToOther, move.kind)
        assertEquals(listOf(zoneCountsAnchorId(THEM)), move.to)
    }

    @Test
    fun `one card of growth sends one card to the hand, and the rest elsewhere`() {
        val moves =
            zoneMoves(
                table(theirBattlefield = listOf(bears("c1"), bears("c2")), theirHand = 3),
                table(theirHand = 4),
            )

        assertEquals(listOf(ZoneMoveKind.ToHand, ZoneMoveKind.ToOther), moves.map { it.kind })
    }

    @Test
    fun `a token that leaves the battlefield does not fly`() {
        val token = bears("t1").copy(isToken = true)

        assertTrue(zoneMoves(table(myBattlefield = listOf(token)), table()).isEmpty())
    }

    @Test
    fun `an opponent's discard flies from their hand to their graveyard`() {
        val move = zoneMoves(table(theirHand = 5), table(theirHand = 4, theirGraveyard = listOf(bolt("b9")))).single()

        assertEquals(ZoneMoveKind.Discarded, move.kind)
        assertEquals(opponentHandAnchorId(THEM), move.from)
        assertEquals(listOf(graveyardAnchorId(THEM)), move.to)
    }

    @Test
    fun `a card milled into an opponent's graveyard while their hand stays put is not a discard`() {
        assertTrue(zoneMoves(table(theirHand = 5), table(theirHand = 5, theirGraveyard = listOf(bolt("b9")))).isEmpty())
    }

    @Test
    fun `a snapshot of a different game moves nothing`() {
        val other = table(myGraveyard = listOf(bolt("b1"))).copy(gameId = "another")

        assertTrue(zoneMoves(table(hand = listOf(bolt("b1"))), other).isEmpty())
    }

    private fun table(
        hand: List<GameCard> = emptyList(),
        myBattlefield: List<GameCard> = emptyList(),
        myGraveyard: List<GameCard> = emptyList(),
        theirBattlefield: List<GameCard> = emptyList(),
        theirGraveyard: List<GameCard> = emptyList(),
        theirExile: List<GameCard> = emptyList(),
        theirHand: Int = 5,
        stack: List<GameCard> = emptyList(),
    ) = GameState(
        gameId = "g",
        hasSnapshot = true,
        viewerPlayerId = ME,
        hand = hand,
        stack = stack,
        players =
            listOf(
                GamePlayer(
                    playerId = THEM,
                    name = "Them",
                    handCount = theirHand,
                    battlefield = theirBattlefield.map { GamePermanent(card = it) },
                    graveyard = theirGraveyard,
                    exile = theirExile,
                ),
                GamePlayer(
                    playerId = ME,
                    name = "Me",
                    isViewer = true,
                    handCount = hand.size,
                    battlefield = myBattlefield.map { GamePermanent(card = it) },
                    graveyard = myGraveyard,
                ),
            ),
    )

    private fun swamp(id: String) =
        GameCard(id = id, name = "Swamp", setCode = "10E", collectorNumber = "371", cardTypes = listOf(CardType.Land))

    private fun bolt(id: String) =
        GameCard(id = id, name = "Lightning Bolt", setCode = "10E", collectorNumber = "203", cardTypes = listOf(CardType.Instant))

    private fun bears(id: String) =
        GameCard(
            id = id,
            name = "Grizzly Bears",
            setCode = "10E",
            collectorNumber = "268",
            cardTypes = listOf(CardType.Creature),
            isCreature = true,
        )
}

private const val ME = "me"
private const val THEM = "them"
