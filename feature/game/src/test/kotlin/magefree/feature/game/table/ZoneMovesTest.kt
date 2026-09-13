package magefree.feature.game.table

import magefree.network.game.CardType
import magefree.network.game.GameCard
import magefree.network.game.GamePermanent
import magefree.network.game.GamePlayer
import magefree.network.game.GameState
import magefree.network.game.MageObjectType
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

    @Test
    fun `a spell that resolves flies from the stack to its owner's graveyard, once the stack lets it go`() {
        // `Spell.getId()` is its ability's id, not its card's, so the card it becomes is found by name.
        val move =
            zoneMoves(table(stack = listOf(spell("ability-of-b1"))), table(theirGraveyard = listOf(bolt("b1")))).single()

        assertEquals(ZoneMoveKind.SpellToGraveyard, move.kind)
        assertEquals("b1", move.cardId)
        assertEquals("ability-of-b1", move.from)
        assertEquals("ability-of-b1", move.leavesStack)
        assertEquals(listOf(graveyardAnchorId(THEM)), move.to)
    }

    @Test
    fun `a spell exiled as it resolves flies to its owner's count panel`() {
        val move = zoneMoves(table(stack = listOf(spell("s1"))), table(theirExile = listOf(bolt("b1")))).single()

        assertEquals(ZoneMoveKind.SpellToExile, move.kind)
        assertEquals(listOf(zoneCountsAnchorId(THEM)), move.to)
    }

    @Test
    fun `a creature spell that resolves flies from the stack to its new place on the battlefield`() {
        // `PermanentCard` is built with its card's id, which is not the spell's.
        val move = zoneMoves(table(stack = listOf(creatureSpell("s1"))), table(myBattlefield = listOf(bears("c1")))).single()

        assertEquals(ZoneMoveKind.SpellToBattlefield, move.kind)
        assertEquals("c1", move.cardId)
        assertEquals("s1", move.from)
        assertEquals("s1", move.leavesStack)
        assertEquals(listOf("c1"), move.to)
        assertTrue("a permanent drawn for the first time is waited for", move.freshDestination)
    }

    @Test
    fun `an Aura that resolves lands on the permanent it is drawn on`() {
        val aura = GameCard(id = "a1", name = "Pacifism", cardTypes = listOf(CardType.Enchantment))
        val host = GamePermanent(card = bears("h1"))
        val before = table(myPermanents = listOf(host), stack = listOf(aura.copy(id = "s1", objectType = MageObjectType.Spell)))
        val after =
            table(
                myPermanents =
                    listOf(
                        host.copy(attachments = listOf("a1")),
                        GamePermanent(card = aura, attachedTo = "h1", isAttachedToPermanent = true),
                    ),
            )

        assertEquals(listOf("h1", "a1"), zoneMoves(before, after).single().to)
    }

    @Test
    fun `a token copy of a spell joining a pile lands on the pile`() {
        val move =
            zoneMoves(
                table(myBattlefield = listOf(token("t1")), stack = listOf(creatureSpell("s1"))),
                table(myBattlefield = listOf(token("t1"), token("t2"))),
            ).single()

        assertEquals("t2", move.cardId)
        assertEquals(listOf("t1", "t2"), move.to)
    }

    @Test
    fun `a permanent already on the table is not the spell that left the stack`() {
        // Countered, and exiled by the counterspell — nothing visible arrived.
        val before = table(myBattlefield = listOf(bears("c0")), stack = listOf(creatureSpell("s1")))

        assertTrue(zoneMoves(before, table(myBattlefield = listOf(bears("c0")))).isEmpty())
    }

    @Test
    fun `an ability leaving the stack goes nowhere`() {
        val ability = GameCard(id = "a1", name = "Lightning Bolt", objectType = MageObjectType.AbilityOnStackFromCard, sourceId = "src")

        assertTrue(zoneMoves(table(stack = listOf(ability)), table(theirGraveyard = listOf(bolt("b1")))).isEmpty())
    }

    @Test
    fun `a spell still on the stack has not moved, whatever reached a graveyard`() {
        val before = table(stack = listOf(spell("s1")))
        val after = table(stack = listOf(spell("s1")), theirGraveyard = listOf(bolt("b1")))

        assertTrue(zoneMoves(before, after).isEmpty())
    }

    @Test
    fun `a card of another name reaching a graveyard is not the spell`() {
        assertTrue(zoneMoves(table(stack = listOf(spell("s1"))), table(theirGraveyard = listOf(bears("c9")))).isEmpty())
    }

    @Test
    fun `the card a resolved spell became is not also read as a discard`() {
        // Their hand fell in the same snapshot — they cast something else — and the spell's card reached
        // their graveyard. It is one card, and it came off the stack.
        val moves =
            zoneMoves(
                table(stack = listOf(spell("s1")), theirHand = 5),
                table(theirHand = 4, theirGraveyard = listOf(bolt("b1"))),
            )

        assertEquals(listOf(ZoneMoveKind.SpellToGraveyard), moves.map { it.kind })
    }

    @Test
    fun `an opponent's land played flies from their hand to where it lands`() {
        val move = zoneMoves(table(theirHand = 5), table(theirHand = 4, theirBattlefield = listOf(swamp("s9")))).single()

        assertEquals(ZoneMoveKind.PlayedFromHand, move.kind)
        assertEquals(opponentHandAnchorId(THEM), move.from)
        assertEquals(listOf("s9"), move.to)
        assertTrue(move.freshDestination)
    }

    @Test
    fun `an opponent's land joining a stack lands on the stack`() {
        val move =
            zoneMoves(
                table(theirHand = 5, theirBattlefield = listOf(swamp("s1"))),
                table(theirHand = 4, theirBattlefield = listOf(swamp("s1"), swamp("s9"))),
            ).single()

        assertEquals(listOf("s1", "s9"), move.to)
    }

    @Test
    fun `a land an opponent puts onto the battlefield while their hand stands still does not fly`() {
        // Fetched from their library: nothing left their hand.
        assertTrue(zoneMoves(table(theirHand = 5), table(theirHand = 5, theirBattlefield = listOf(swamp("s9")))).isEmpty())
    }

    @Test
    fun `a creature arriving while an opponent's hand shrank is not a land played`() {
        // Cast and resolved between two snapshots: it went by the stack, and only lands are played.
        assertTrue(zoneMoves(table(theirHand = 5), table(theirHand = 4, theirBattlefield = listOf(bears("c9")))).isEmpty())
    }

    @Test
    fun `a land and a discard in one snapshot take one card of the hand each`() {
        val moves =
            zoneMoves(
                table(theirHand = 5),
                table(theirHand = 3, theirBattlefield = listOf(swamp("s9")), theirGraveyard = listOf(bolt("b9"))),
            )

        assertEquals(listOf(ZoneMoveKind.PlayedFromHand, ZoneMoveKind.Discarded), moves.map { it.kind })
    }

    private fun spell(id: String) = bolt(id).copy(objectType = MageObjectType.Spell)

    private fun creatureSpell(id: String) = bears(id).copy(objectType = MageObjectType.Spell)

    private fun token(id: String) = bears(id).copy(isToken = true)

    private fun table(
        hand: List<GameCard> = emptyList(),
        myBattlefield: List<GameCard> = emptyList(),
        myPermanents: List<GamePermanent> = emptyList(),
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
                    battlefield = myBattlefield.map { GamePermanent(card = it) } + myPermanents,
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
