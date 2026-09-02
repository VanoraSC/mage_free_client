package magefree.bridge.mapping

import mage.abilities.icon.CardIconImpl
import mage.abilities.icon.CardIconType
import mage.constants.CardType
import mage.constants.MageObjectType
import mage.constants.PhaseStep
import mage.constants.SubType
import mage.constants.TurnPhase
import mage.players.PlayableObjectStats
import mage.view.CardView
import mage.view.CommandObjectView
import mage.view.GameView
import mage.view.PlayerView
import magefree.protocol.CardIconTypeCode
import magefree.protocol.CardTypeCode
import magefree.protocol.CommandObjectKind
import magefree.protocol.MageObjectTypeCode
import magefree.protocol.PhaseStepCode
import magefree.protocol.TurnPhaseCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Hermetic tests for [GameViewMapper] over crafted `mage.view.GameView`s (see [GameViews] for why they
 * are built the way they are).
 *
 * The centre of gravity is the **reachability contract** (verification standard 2): the four things the
 * app gates on — whose turn it is, whether the viewer has priority, what the viewer may play, and
 * whether the viewer is a spectator — each get a test naming the upstream field they come from, so a
 * future change that quietly stops carrying one fails here rather than in a silent, empty UI.
 */
class GameViewMapperTest {
    private val alice = UUID.randomUUID()
    private val computer = UUID.randomUUID()

    @Test
    fun `whose turn it is comes from activePlayerId and the per-player active flag`() {
        val view =
            GameViews.game(
                turn = 4,
                activePlayerId = computer,
                activePlayerName = "Computer",
                players =
                    listOf(
                        GameViews.player(playerId = alice, name = "alice", active = false, hasPriority = false),
                        GameViews.player(
                            playerId = computer,
                            name = "Computer",
                            controlled = false,
                            active = true,
                            hasPriority = true,
                            human = false,
                        ),
                    ),
            )

        val state = GameViewMapper.map(view)

        assertEquals(4, state.turn)
        assertEquals(computer.toString(), state.activePlayerId, "GameView.getActivePlayerId() is the turn holder")
        assertEquals("Computer", state.activePlayerName)
        assertEquals(
            listOf(false, true),
            state.players.map { it.active },
            "PlayerView.isActive() must survive per player, so the app can highlight the turn holder",
        )
    }

    @Test
    fun `viewer priority comes from the viewer's own PlayerView, not from the priority player name`() {
        // GameView exposes getPriorityPlayerName() but NO priority player *id*, so a name comparison
        // would be the only alternative — and two seats may share a display name. The viewer's own
        // PlayerView.hasPriority() is the id-safe source, which is what this asserts.
        val opponentHasPriority =
            GameViews.game(
                priorityPlayerName = "Computer",
                myPlayerId = alice,
                players =
                    listOf(
                        GameViews.player(playerId = alice, name = "alice", hasPriority = false),
                        GameViews.player(playerId = computer, name = "Computer", controlled = false, hasPriority = true),
                    ),
            )

        val state = GameViewMapper.map(opponentHasPriority)

        assertFalse(state.viewerHasPriority, "the viewer does not hold priority in this snapshot")
        assertEquals("Computer", state.priorityPlayerName, "the name is still carried, for display only")
        assertEquals(alice.toString(), state.viewerPlayerId, "GameView.getMyPlayer() identifies the seat")
        assertEquals(listOf(false, true), state.players.map { it.hasPriority })
    }

    @Test
    fun `the viewer holding priority is reported as such`() {
        val view =
            GameViews.game(
                priorityPlayerName = "alice",
                myPlayerId = alice,
                players = listOf(GameViews.player(playerId = alice, name = "alice", hasPriority = true)),
            )

        assertTrue(GameViewMapper.map(view).viewerHasPriority)
    }

    @Test
    fun `playability is the server's canPlayObjects, mapped straight through`() {
        val land = UUID.randomUUID()
        val ability = UUID.randomUUID()
        val view =
            GameViews.game(
                myPlayerId = alice,
                players = listOf(GameViews.player(playerId = alice)),
                canPlayObjects = GameViews.playableObjects(mapOf(land to listOf(ability))),
            )

        val state = GameViewMapper.map(view)

        assertEquals(1, state.playable.size)
        assertEquals(land.toString(), state.playable.single().objectId)
        assertEquals(listOf(ability.toString()), state.playable.single().abilityIds)
    }

    @Test
    fun `a null canPlayObjects means nothing is playable, never a guess`() {
        // The server fills canPlayObjects ONLY on the view built for the player holding priority
        // (GameSessionPlayer.prepareGameView); for everyone else it is null. Mapping that to an empty
        // list is the whole of our playability logic — the bridge must never infer legality.
        val view = GameViews.game(myPlayerId = alice, players = listOf(GameViews.player(playerId = alice)), canPlayObjects = null)

        assertTrue(GameViewMapper.map(view).playable.isEmpty())
    }

    @Test
    fun `a spectator snapshot has no viewer id, no hand and no priority`() {
        val view =
            GameViews.game(
                myPlayerId = null,
                players =
                    listOf(
                        GameViews.player(playerId = alice, name = "alice", controlled = false, hasPriority = true),
                        GameViews.player(playerId = computer, name = "Computer", controlled = false, hasPriority = false),
                    ),
            )

        val state = GameViewMapper.map(view)

        assertNull(state.viewerPlayerId, "a watcher's GameView has a null myPlayerId")
        assertFalse(state.viewerHasPriority, "a watcher is never prompted, so never holds priority")
        assertTrue(state.hand.isEmpty(), "a watcher's myHand is empty")
        assertEquals(2, state.players.size, "a watcher still sees both seats")
    }

    @Test
    fun `the viewer's hand, the stack, exile, revealed and combat are all carried`() {
        val forest = GameViews.card(name = "Forest")
        val bolt =
            GameViews.card(
                name = "Lightning Bolt",
                setCode = "M10",
                collectorNumber = "146",
                manaCost = listOf("{R}"),
                cardTypes = listOf(CardType.INSTANT),
                superTypes = emptyList(),
                subTypes = emptyList(),
                rules = listOf("Lightning Bolt deals 3 damage to any target."),
            )
        val bear = GameViews.permanent(card = GameViews.card(name = "Grizzly Bears", power = "2", toughness = "2"), tapped = true)
        val blocker = GameViews.permanent(card = GameViews.card(name = "Wall"), controlled = false)
        val view =
            GameViews.game(
                myPlayerId = alice,
                players = listOf(GameViews.player(playerId = alice, battlefield = listOf(bear))),
                hand = listOf(forest, bolt),
                stack = listOf(bolt),
                exiles = listOf(GameViews.exileZone(name = "Bolt exile", cards = listOf(forest))),
                revealed = listOf(GameViews.revealed(name = "Peek", cards = listOf(bolt))),
                combat =
                    listOf(
                        GameViews.combatGroup(
                            defenderId = computer,
                            defenderName = "Computer",
                            blocked = true,
                            attackers = listOf(bear),
                            blockers = listOf(blocker),
                        ),
                    ),
            )

        val state = GameViewMapper.map(view)

        assertEquals(listOf("Forest", "Lightning Bolt"), state.hand.map { it.name }, "myHand order must be preserved")
        assertEquals(listOf("Lightning Bolt"), state.stack.map { it.name })
        assertEquals(listOf("Bolt exile"), state.exile.map { it.name })
        assertEquals(
            listOf("Forest"),
            state.exile
                .single()
                .cards
                .map { it.name },
        )
        assertEquals(listOf("Peek"), state.revealed.map { it.name })
        assertEquals(
            listOf("Lightning Bolt"),
            state.revealed
                .single()
                .cards
                .map { it.name },
        )

        val group = state.combat.single()
        assertEquals(computer.toString(), group.defenderId)
        assertEquals("Computer", group.defenderName)
        assertTrue(group.blocked)
        assertEquals(listOf(bear.id.toString()), group.attackerIds)
        assertEquals(listOf(blocker.id.toString()), group.blockerIds)
    }

    @Test
    fun `a card carries its printing, cost, type line and rules`() {
        val bolt =
            GameViews.card(
                name = "Lightning Bolt",
                setCode = "M10",
                collectorNumber = "146",
                manaCost = listOf("{R}"),
                cardTypes = listOf(CardType.INSTANT),
                superTypes = emptyList(),
                subTypes = emptyList(),
                power = "",
                toughness = "",
                rules = listOf("Lightning Bolt deals 3 damage to any target."),
            )

        val card = GameViewMapper.mapCard(bolt)

        assertEquals(bolt.id.toString(), card.id)
        assertEquals("Lightning Bolt", card.name)
        assertEquals("M10", card.setCode, "(setCode, collectorNumber) is how the catalog resolves the printing")
        assertEquals("146", card.collectorNumber)
        assertEquals("{R}", card.manaCost)
        assertEquals("Instant", card.typeLine)
        assertEquals(listOf("Lightning Bolt deals 3 damage to any target."), card.rules)
        assertNull(card.power, "an empty upstream power is 'the server said nothing', not \"\"")
        assertNull(card.toughness)
        assertFalse(card.faceDown)
    }

    @Test
    fun `a permanent carries its battlefield state`() {
        val attachedTo = UUID.randomUUID()
        val bearCard =
            GameViews.card(
                name = "Grizzly Bears",
                cardTypes = listOf(CardType.CREATURE),
                superTypes = emptyList(),
                subTypes = listOf(SubType.BEAR),
                power = "2",
                toughness = "2",
            )
        val bear =
            GameViews.permanent(
                card = bearCard,
                tapped = true,
                summoningSickness = true,
                damage = 1,
                attachedTo = attachedTo,
                controlled = true,
            )
        val view =
            GameViews.game(
                myPlayerId = alice,
                players = listOf(GameViews.player(playerId = alice, battlefield = listOf(bear))),
            )

        val mapped =
            GameViewMapper
                .map(view)
                .players
                .single()
                .battlefield
                .single()

        assertEquals("Grizzly Bears", mapped.card.name)
        assertEquals("2", mapped.card.power)
        assertEquals("2", mapped.card.toughness)
        assertTrue(mapped.tapped)
        assertTrue(mapped.summoningSickness)
        assertEquals(1, mapped.damage)
        assertEquals(attachedTo.toString(), mapped.attachedTo)
        assertTrue(mapped.controlledByViewer)
    }

    @Test
    fun `a host permanent lists what is attached to it, in upstream's order`() {
        // The reverse direction is the whole point: without it, rendering an Aura on its host means
        // every permanent scanning every battlefield, every frame, for anything pointing at it.
        val rancor = UUID.randomUUID()
        val pacifism = UUID.randomUUID()
        val bear =
            GameViews.permanent(
                card = GameViews.card(name = "Grizzly Bears", cardTypes = listOf(CardType.CREATURE), superTypes = emptyList()),
                attachments = listOf(rancor, pacifism),
            )
        val view = GameViews.game(myPlayerId = alice, players = listOf(GameViews.player(playerId = alice, battlefield = listOf(bear))))

        val mapped =
            GameViewMapper
                .map(view)
                .players
                .single()
                .battlefield
                .single()

        assertEquals(listOf(rancor.toString(), pacifism.toString()), mapped.attachments)
    }

    @Test
    fun `your aura on their creature is flagged as such, and on your own creature is not`() {
        // `attachedControllerDiffers` is the easily-missed board state: you control the Aura,
        // they control the creature. Read from upstream's `isAttachedToDifferentlyControlledPermanent()`
        // -- note the accessor name, which is nothing like the field's.
        val theirCreature = UUID.randomUUID()
        val myAura =
            GameViews.permanent(
                card = GameViews.card(name = "Rancor", cardTypes = listOf(CardType.ENCHANTMENT), superTypes = emptyList()),
                attachedTo = theirCreature,
                attachedToPermanent = true,
                attachedControllerDiffers = true,
            )
        val myOtherAura =
            GameViews.permanent(
                card = GameViews.card(name = "Rancor", cardTypes = listOf(CardType.ENCHANTMENT), superTypes = emptyList()),
                attachedTo = UUID.randomUUID(),
                attachedToPermanent = true,
                attachedControllerDiffers = false,
            )
        val view =
            GameViews.game(
                myPlayerId = alice,
                players = listOf(GameViews.player(playerId = alice, battlefield = listOf(myAura, myOtherAura))),
            )

        val mapped =
            GameViewMapper
                .map(view)
                .players
                .single()
                .battlefield

        assertTrue(mapped[0].attachedToPermanent)
        assertTrue(mapped[0].attachedControllerDiffers, "an Aura on an opponent's creature must say so")
        assertTrue(mapped[1].attachedToPermanent)
        assertFalse(mapped[1].attachedControllerDiffers, "an Aura on your own creature must not")
    }

    @Test
    fun `an unattached permanent carries no attachment state at all`() {
        // Both flags default to false and the list to empty. Upstream computes `attachedToPermanent`
        // by actually resolving the host, so "not attached" and "attached to a player" both land here.
        val forest = GameViews.permanent(card = GameViews.card(name = "Forest"))
        val view =
            GameViews.game(myPlayerId = alice, players = listOf(GameViews.player(playerId = alice, battlefield = listOf(forest))))

        val mapped =
            GameViewMapper
                .map(view)
                .players
                .single()
                .battlefield
                .single()

        assertNull(mapped.attachedTo)
        assertTrue(mapped.attachments.isEmpty(), "a sparse upstream view leaves `attachments` null; empty is the answer")
        assertFalse(mapped.attachedToPermanent)
        assertFalse(mapped.attachedControllerDiffers)
    }

    @Test
    fun `a player carries its counts, mana pool and match score`() {
        val view =
            GameViews.game(
                myPlayerId = alice,
                players =
                    listOf(
                        GameViews.player(
                            playerId = alice,
                            name = "alice",
                            life = 17,
                            libraryCount = 42,
                            handCount = 5,
                            graveyard = GameViews.cardsView(listOf(GameViews.card(), GameViews.card())),
                            exile = GameViews.cardsView(listOf(GameViews.card())),
                            wins = 1,
                            winsNeeded = 2,
                            manaPool = GameViews.manaPool(green = 2, colorless = 1),
                        ),
                    ),
            )

        val player = GameViewMapper.map(view).players.single()

        assertEquals("alice", player.name)
        assertEquals(17, player.life)
        assertEquals(42, player.libraryCount)
        assertEquals(5, player.handCount)
        assertEquals(2, player.graveyardCount)
        assertEquals(1, player.exileCount)
        assertEquals(1, player.wins)
        assertEquals(2, player.winsNeeded)
        assertTrue(player.viewer)
        assertEquals(2, player.manaPool.green)
        assertEquals(1, player.manaPool.colorless)
    }

    @Test
    fun `a player carries the counters on it, poison included`() {
        // Poison at ten is a loss, so this is win-condition state rather than decoration. Looked up by
        // name, never by index: upstream builds the list from `getCountersAsCopy()`, and
        // `mage.counters.Counters` extends HashMap -- the order that arrives means nothing.
        val view =
            GameViews.game(
                myPlayerId = alice,
                players = listOf(GameViews.player(playerId = alice, counters = listOf("poison" to 3, "energy" to 2))),
            )

        val counters =
            GameViewMapper
                .map(view)
                .players
                .single()
                .counters
                .associate { it.name to it.count }

        assertEquals(mapOf("poison" to 3, "energy" to 2), counters)
    }

    @Test
    fun `monarch, initiative and designations are carried per player`() {
        val view =
            GameViews.game(
                myPlayerId = alice,
                players =
                    listOf(
                        GameViews.player(
                            playerId = alice,
                            monarch = true,
                            initiative = true,
                            designationNames = listOf("City's Blessing"),
                        ),
                        GameViews.player(playerId = computer, name = "Computer", controlled = false),
                    ),
            )

        val mapped = GameViewMapper.map(view).players

        assertTrue(mapped[0].monarch)
        assertTrue(mapped[0].initiative)
        assertEquals(listOf("City's Blessing"), mapped[0].designationNames)
        assertFalse(mapped[1].monarch, "the crown belongs to exactly one seat")
        assertFalse(mapped[1].initiative)
        assertTrue(mapped[1].designationNames.isEmpty())
    }

    @Test
    fun `a player with no counters or designations maps to empty lists, never a crash`() {
        // The serialization constructor leaves both null, standing in for a sparse upstream view.
        val view = GameViews.game(myPlayerId = alice, players = listOf(GameViews.player(playerId = alice)))

        val player = GameViewMapper.map(view).players.single()

        assertTrue(player.counters.isEmpty())
        assertTrue(player.designationNames.isEmpty())
        assertFalse(player.monarch)
        assertFalse(player.initiative)
    }

    @Test
    fun `a player's command zone carries all four upstream kinds`() {
        val emblem = GameViews.emblem()
        val commander = GameViews.commander()
        val dungeon = GameViews.dungeon()
        val plane = GameViews.plane()
        val view =
            GameViews.game(
                myPlayerId = alice,
                players =
                    listOf(
                        GameViews.player(playerId = alice, commandList = listOf(emblem, commander, dungeon, plane)),
                    ),
            )

        val mapped =
            GameViewMapper
                .map(view)
                .players
                .single()
                .commandList

        assertEquals(
            listOf(CommandObjectKind.EMBLEM, CommandObjectKind.COMMANDER, CommandObjectKind.DUNGEON, CommandObjectKind.PLANE),
            mapped.map { it.kind },
            "the kind is read off the concrete view type, which only the mapper sees",
        )
        assertEquals(listOf(emblem.id, commander.id, dungeon.id, plane.id).map { it.toString() }, mapped.map { it.id })
        assertEquals("Emblem Jace, Unraveler of Secrets", mapped[0].name)
        assertEquals(listOf("Whenever an opponent casts a spell, exile it."), mapped[0].rules)
    }

    @Test
    fun `only the kinds that have a card number carry one`() {
        // `getCardNumber()` is not on `CommandObjectView`. A commander has one because it extends
        // CardView; an emblem has one only when it was printed on a card; a dungeon and a plane have
        // no card number at all, and a blank one is "the server said nothing" rather than "".
        val view =
            GameViews.game(
                myPlayerId = alice,
                players =
                    listOf(
                        GameViews.player(
                            playerId = alice,
                            commandList = listOf(GameViews.emblem(), GameViews.commander(), GameViews.dungeon(), GameViews.plane()),
                        ),
                    ),
            )

        val mapped =
            GameViewMapper
                .map(view)
                .players
                .single()
                .commandList

        assertNull(mapped[0].collectorNumber, "an emblem not printed on a card has a blank number upstream")
        assertEquals("28", mapped[1].collectorNumber)
        assertEquals("C16", mapped[1].setCode)
        assertNull(mapped[2].collectorNumber)
        assertNull(mapped[3].collectorNumber)
        assertEquals("AFR", mapped[2].setCode, "a dungeon still names a set even with no number")
    }

    @Test
    fun `an emblem printed on a card keeps its number`() {
        val view =
            GameViews.game(
                myPlayerId = alice,
                players =
                    listOf(
                        GameViews.player(playerId = alice, commandList = listOf(GameViews.emblem(setCode = "WAR", cardNumber = "207"))),
                    ),
            )

        val mapped =
            GameViewMapper
                .map(view)
                .players
                .single()
                .commandList
                .single()

        assertEquals("WAR", mapped.setCode)
        assertEquals("207", mapped.collectorNumber)
    }

    @Test
    fun `a command object implementation the mapper does not know keeps its fields`() {
        // The branch is on the concrete type, so a fifth implementation would fall through it. It must
        // arrive as UNKNOWN with everything the interface exposes intact -- never dropped.
        val view =
            GameViews.game(
                myPlayerId = alice,
                players = listOf(GameViews.player(playerId = alice, commandList = listOf(UnknownCommandObject))),
            )

        val mapped =
            GameViewMapper
                .map(view)
                .players
                .single()
                .commandList
                .single()

        assertEquals(CommandObjectKind.UNKNOWN, mapped.kind)
        assertEquals("Something New", mapped.name)
        assertEquals(listOf("It does a thing."), mapped.rules)
        assertEquals("XYZ", mapped.setCode)
    }

    @Test
    fun `a player with an empty command zone maps to an empty list, never a crash`() {
        val view = GameViews.game(myPlayerId = alice, players = listOf(GameViews.player(playerId = alice)))

        assertTrue(
            GameViewMapper
                .map(view)
                .players
                .single()
                .commandList
                .isEmpty(),
        )
    }

    @Test
    fun `a player carries the cards in its graveyard and exile, not only how many`() {
        val bolt = GameViews.card(name = "Lightning Bolt", cardTypes = listOf(CardType.INSTANT), superTypes = emptyList())
        val forest = GameViews.card(name = "Forest")
        val bear = GameViews.card(name = "Grizzly Bears", cardTypes = listOf(CardType.CREATURE), superTypes = emptyList())
        val view =
            GameViews.game(
                myPlayerId = alice,
                players =
                    listOf(
                        GameViews.player(
                            playerId = alice,
                            graveyard = GameViews.cardsView(listOf(bolt, forest)),
                            exile = GameViews.cardsView(listOf(bear)),
                        ),
                    ),
            )

        val mapped = GameViewMapper.map(view).players.single()

        assertEquals(
            listOf("Lightning Bolt", "Forest"),
            mapped.graveyard.map { it.name },
            "CardsView is a LinkedHashMap, so the server's order is the pile's order and nothing sorts it",
        )
        assertEquals(listOf("Grizzly Bears"), mapped.exile.map { it.name })
    }

    @Test
    fun `the counts stay, and agree with the lists`() {
        // Both are kept: a collapsed vitals row wants the number without the cards. They are derived
        // from the same upstream CardsView, so a disagreement would mean the mapper read two things.
        val graveyard = GameViews.cardsView(List(4) { GameViews.card(name = "Forest") })
        val view =
            GameViews.game(
                myPlayerId = alice,
                players = listOf(GameViews.player(playerId = alice, graveyard = graveyard, exile = GameViews.cardsView(emptyList()))),
            )

        val mapped = GameViewMapper.map(view).players.single()

        assertEquals(4, mapped.graveyardCount)
        assertEquals(mapped.graveyard.size, mapped.graveyardCount)
        assertEquals(0, mapped.exileCount)
        assertEquals(mapped.exile.size, mapped.exileCount)
    }

    @Test
    fun `a sparse view with no graveyard or exile maps to empty lists and zero counts`() {
        val view = GameViews.game(myPlayerId = alice, players = listOf(GameViews.player(playerId = alice).nullOutZones()))

        val mapped = GameViewMapper.map(view).players.single()

        assertTrue(mapped.graveyard.isEmpty())
        assertTrue(mapped.exile.isEmpty())
        assertEquals(0, mapped.graveyardCount)
        assertEquals(0, mapped.exileCount)
    }

    @Test
    fun `a token says so, and an ordinary permanent does not`() {
        val token =
            GameViews.permanent(
                card =
                    GameViews.card(
                        name = "Soldier",
                        cardTypes = listOf(CardType.CREATURE),
                        superTypes = emptyList(),
                        subTypes = listOf(SubType.SOLDIER),
                        token = true,
                        objectType = MageObjectType.TOKEN,
                    ),
            )
        val land = GameViews.permanent(card = GameViews.card(name = "Forest", objectType = MageObjectType.PERMANENT))
        val view =
            GameViews.game(
                myPlayerId = alice,
                players = listOf(GameViews.player(playerId = alice, battlefield = listOf(token, land))),
            )

        val mapped =
            GameViewMapper
                .map(view)
                .players
                .single()
                .battlefield

        assertTrue(mapped[0].card.token, "a token permanent must say it is one — it ceases to exist off the battlefield")
        assertEquals(MageObjectTypeCode.TOKEN, mapped[0].card.objectType)
        assertFalse(mapped[1].card.token)
        assertEquals(MageObjectTypeCode.PERMANENT, mapped[1].card.objectType)
    }

    @Test
    fun `a copy of a card is distinguishable from the card itself`() {
        // The question `token` cannot answer. Both are cards, neither is a token, and only
        // `mageObjectType` separates them.
        val copy = GameViews.card(name = "Grizzly Bears", objectType = MageObjectType.COPY_CARD)
        val original = GameViews.card(name = "Grizzly Bears", objectType = MageObjectType.CARD)

        assertEquals(MageObjectTypeCode.COPY_CARD, GameViewMapper.mapCard(copy).objectType)
        assertEquals(MageObjectTypeCode.CARD, GameViewMapper.mapCard(original).objectType)
        assertFalse(GameViewMapper.mapCard(copy).token)
    }

    @Test
    fun `every upstream object type has a code of its own`() {
        // The guard on the one place upstream's set and ours meet. A type added upstream fails here,
        // naming itself, rather than quietly arriving as UNKNOWN.
        MageObjectType.values().forEach { upstream ->
            val mapped = GameViewMapper.mapCard(GameViews.card(objectType = upstream)).objectType

            assertEquals(upstream.name, mapped.name, "upstream's $upstream has no code of its own")
        }
    }

    @Test
    fun `an object with no type set carries upstream's own NULL, not UNKNOWN`() {
        // NULL is upstream's default and a real answer: "the server set no type". UNKNOWN means this
        // build did not recognise what arrived. Collapsing the two would lose that distinction.
        assertEquals(MageObjectTypeCode.NULL, GameViewMapper.mapCard(GameViews.card()).objectType)
    }

    @Test
    fun `every upstream phase and step maps to a distinct code`() {
        TurnPhase.entries.forEach { phase ->
            val mapped = GameViewMapper.map(GameViews.game(phase = phase)).phase
            assertEquals(phase.name, mapped.name, "TurnPhase.$phase should map to the same-named code")
        }
        PhaseStep.entries.forEach { step ->
            val mapped = GameViewMapper.map(GameViews.game(step = step)).step
            assertEquals(step.name, mapped.name, "PhaseStep.$step should map to the same-named code")
        }
    }

    @Test
    fun `an absent phase or step maps to UNKNOWN rather than throwing`() {
        val state = GameViewMapper.map(GameViews.game(phase = null, step = null))

        assertEquals(TurnPhaseCode.UNKNOWN, state.phase)
        assertEquals(PhaseStepCode.UNKNOWN, state.step)
    }

    @Test
    fun `a sparse view with every collection unset maps to an empty snapshot instead of throwing`() {
        // The serialization-constructed view leaves every collection field null — the shape a drifted or
        // partially populated upstream view would have. The mapper must survive it: a snapshot the app
        // can render is always better than an exception that costs it the whole push (the never-throw invariant).
        val sparse = GameViews.game(phase = null, step = null, activePlayerName = "", priorityPlayerName = "")
        sparse.setEveryCollectionNull()

        val state = GameViewMapper.map(sparse)

        assertTrue(state.players.isEmpty())
        assertTrue(state.hand.isEmpty())
        assertTrue(state.stack.isEmpty())
        assertTrue(state.exile.isEmpty())
        assertTrue(state.revealed.isEmpty())
        assertTrue(state.combat.isEmpty())
        assertTrue(state.playable.isEmpty())
        assertNull(state.activePlayerName, "a blank upstream name is 'nothing', not \"\"")
        assertNull(state.priorityPlayerName)
        assertNull(state.viewerPlayerId)
    }

    @Test
    fun `a card whose composed text getters blow up costs one field, not the whole snapshot`() {
        // getTypeText()/getManaCostStr() walk collections upstream populates only for some card kinds;
        // a null one throws inside the getter. That must not lose the card, let alone the snapshot.
        val broken = GameViews.card(name = "Mystery")
        broken.breakComposedText()
        val view = GameViews.game(myPlayerId = alice, players = listOf(GameViews.player(playerId = alice)), hand = listOf(broken))

        val card = GameViewMapper.map(view).hand.single()

        assertEquals("Mystery", card.name, "the card itself must still arrive")
        assertNull(card.typeLine, "the composed type line degrades to null")
        assertNull(card.manaCost, "the composed mana cost degrades to null")
    }

    // ---- what a card currently *is* -----------------------------------------------------

    @Test
    fun `a land that an effect has animated is carried as the creature it currently is`() {
        // Earthbend on a Mountain. The card is still a land AND is now a creature — upstream recomputes
        // `cardTypes` for every snapshot, and `isCreature()` reads exactly that list. Nothing here (and
        // nothing downstream) may decide this from the printing or from the composed type line.
        val earthbentMountain =
            GameViews.card(
                name = "Mountain",
                setCode = "M21",
                collectorNumber = "269",
                cardTypes = listOf(CardType.LAND, CardType.CREATURE),
                subTypes = listOf(SubType.MOUNTAIN),
                power = "0",
                toughness = "3",
            )

        val card = GameViewMapper.mapCard(earthbentMountain)

        assertTrue(card.creature, "the server says this land is a creature right now")
        assertEquals(listOf(CardTypeCode.LAND, CardTypeCode.CREATURE), card.cardTypes)
        assertEquals("0", card.power)
        assertEquals("3", card.toughness)
    }

    @Test
    fun `a land that is only a land is never reported as a creature`() {
        // The mirror of the test above, and the reason both exist: a mapper that hardcoded "lands are
        // not creatures" would pass one and fail the other.
        val mountain =
            GameViews.card(
                name = "Mountain",
                cardTypes = listOf(CardType.LAND),
                subTypes = listOf(SubType.MOUNTAIN),
                // Upstream sends a permanent's *current* P/T, and a noncreature's is "0" — not blank.
                // This is the payload that produced "0/0 · Summoning sick" under a Mountain on device.
                power = "0",
                toughness = "0",
            )

        val card = GameViewMapper.mapCard(mountain)

        assertFalse(card.creature)
        assertEquals(listOf(CardTypeCode.LAND), card.cardTypes)
        assertEquals("0", card.power, "the mapper carries what the server sent; suppressing it is the board's job")
    }

    @Test
    fun `a creature is carried with its current power and toughness, star and all`() {
        val goyf =
            GameViews.card(
                name = "Tarmogoyf",
                cardTypes = listOf(CardType.CREATURE),
                superTypes = emptyList(),
                subTypes = listOf(SubType.LHURGOYF),
                power = "*",
                toughness = "1+*",
            )

        val card = GameViewMapper.mapCard(goyf)

        assertTrue(card.creature)
        assertEquals("*", card.power, "`*` is a real power — it is carried as the string upstream sent")
        assertEquals("1+*", card.toughness)
    }

    @Test
    fun `a permanent carries every counter the server put on it, whatever the kind`() {
        val elder =
            GameViews.permanent(
                card =
                    GameViews.card(
                        name = "Walking Ballista",
                        cardTypes = listOf(CardType.ARTIFACT, CardType.CREATURE),
                        superTypes = emptyList(),
                        subTypes = emptyList(),
                        power = "2",
                        toughness = "2",
                        // Deliberately not just +1/+1: the mapping must not know any counter kind.
                        counters = listOf("+1/+1" to 2, "stun" to 1, "oil" to 3),
                    ),
            )

        val card = GameViewMapper.mapCard(elder)

        assertEquals(listOf("+1/+1", "stun", "oil"), card.counters.map { it.name })
        assertEquals(listOf(2, 1, 3), card.counters.map { it.count })
    }

    @Test
    fun `a permanent with no counters carries an empty list, never a fabricated one`() {
        val bear =
            GameViews.permanent(
                card =
                    GameViews.card(
                        name = "Grizzly Bears",
                        cardTypes = listOf(CardType.CREATURE),
                        superTypes = emptyList(),
                        subTypes = listOf(SubType.BEAR),
                        power = "2",
                        toughness = "2",
                    ),
            )

        assertTrue(GameViewMapper.mapCard(bear).counters.isEmpty())
    }

    @Test
    fun `transformed survives mapping, the real signal for which face is currently up`() {
        // Kytheon, Hero of Akros transformed into Gideon,
        // Battle-Forged, but the board kept showing Kytheon's art. Confirmed against upstream source:
        // CardView.isTransformed() is set correctly for any permanent by CardView's own constructor
        // (`if (permanent.isTransformed()) transformed = true`), inherited unchanged by PermanentView
        // via its super() call -- it was never actually unavailable, just unread.
        val transformed =
            GameViews.permanent(
                card =
                    GameViews.card(
                        name = "Gideon, Battle-Forged",
                        cardTypes = listOf(CardType.PLANESWALKER),
                        superTypes = emptyList(),
                        subTypes = emptyList(),
                    ),
                transformed = true,
            )

        assertTrue(GameViewMapper.mapCard(transformed).transformed)
    }

    @Test
    fun `transformed is false for an untransformed permanent even though alternateName is set`() {
        // Ajani, Nacatl Pariah on the battlefield, UNTRANSFORMED,
        // showed Ajani, Nacatl Avenger's (the back face's) art -- and the manual flip control
        // never appeared until it actually transformed. Root cause: alternateName was being
        // treated as the "currently transformed" signal, but upstream sets it unconditionally on any
        // transformable permanent regardless of state -- it means "has another face", not "is
        // showing it". `transformed` is upstream's own dedicated, correctly-computed field for that.
        val untransformed =
            GameViews.permanent(
                card =
                    GameViews.card(
                        name = "Ajani, Nacatl Pariah",
                        cardTypes = listOf(CardType.PLANESWALKER),
                        superTypes = emptyList(),
                        subTypes = emptyList(),
                        // Mirrors upstream's own unconditional assignment: the OTHER face's name, set
                        // even though this permanent has never transformed.
                        alternateName = "Ajani, Nacatl Avenger",
                    ),
                transformed = false,
            )

        val mapped = GameViewMapper.mapCard(untransformed)
        assertFalse(mapped.transformed, "an untransformed permanent's art must stay on the front face")
        assertEquals(
            "Ajani, Nacatl Avenger",
            mapped.alternateName,
            "alternateName is a catalog fact (has another face, what it's called) and is threaded through as-is",
        )
    }

    @Test
    fun `alternateName is null for an ordinary card, never fabricated`() {
        val bear = GameViews.card(name = "Grizzly Bears")

        assertNull(GameViewMapper.mapCard(bear).alternateName)
    }

    @Test
    fun `a plain CardView's alternateName is threaded through unchanged`() {
        // An untransformed Kytheon sitting in hand showed Gideon's
        // art -- from treating a non-null alternateName as a face signal. Upstream sets alternateName
        // unconditionally for any transformable card, to the name of its OTHER face, regardless of
        // which face is showing; a hand card's `transformed` is simply false (only permanents
        // transform), which is the correct signal the board now reads instead.
        val kytheonInHand =
            GameViews.card(
                name = "Kytheon, Hero of Akros",
                cardTypes = listOf(CardType.CREATURE),
                superTypes = emptyList(),
                subTypes = emptyList(),
                alternateName = "Gideon, Battle-Forged",
            )

        val mapped = GameViewMapper.mapCard(kytheonInHand)
        assertEquals("Gideon, Battle-Forged", mapped.alternateName)
        assertFalse(mapped.transformed)
    }

    @Test
    fun `a spell on the stack carries every target it chose, in upstream's order`() {
        // `CardView.addTargets` de-duplicates through a LinkedHashSet ("use linked, so it will use
        // stable sort order"), so the order the server sends is meaningful and the mapper preserves it
        // rather than sorting or set-ifying.
        val creature = UUID.randomUUID()
        val opponent = UUID.randomUUID()
        val bolt =
            GameViews.card(
                name = "Lightning Bolt",
                cardTypes = listOf(CardType.INSTANT),
                superTypes = emptyList(),
                subTypes = emptyList(),
                targets = listOf(creature, opponent),
            )

        val mapped = GameViewMapper.mapCard(bolt)

        assertEquals(listOf(creature.toString(), opponent.toString()), mapped.targets)
    }

    @Test
    fun `an ability on the stack carries its target too, not only a spell`() {
        // StackAbilityView inherits `targets` from CardView and fills it in `updateTargets`, so the
        // mapper's single read covers both kinds of stack object -- no per-type branching, matching
        // upstream's own flat list.
        val target = UUID.randomUUID()
        val trigger = GameViews.stackAbilityView(targets = listOf(target))

        assertEquals(listOf(target.toString()), GameViewMapper.mapCard(trigger).targets)
    }

    @Test
    fun `a target that is itself a stack entry arrives as a plain id like any other`() {
        // Upstream resolves every target through `game.getObject(uuid)` and puts the ids in one flat
        // list, so countering a spell targets a *stack* object and looks no different on the wire. The
        // renderer joins the id against the snapshot; the mapper does not branch on what it points at.
        val bolt =
            GameViews.card(
                name = "Lightning Bolt",
                cardTypes = listOf(CardType.INSTANT),
                superTypes = emptyList(),
                subTypes = emptyList(),
            )
        val counterspell =
            GameViews.card(
                name = "Counterspell",
                cardTypes = listOf(CardType.INSTANT),
                superTypes = emptyList(),
                subTypes = emptyList(),
                targets = listOf(bolt.id),
            )

        val snapshot = GameViewMapper.map(GameViews.game(stack = listOf(bolt, counterspell)))
        val mappedCounterspell = snapshot.stack.single { it.name == "Counterspell" }

        assertEquals(listOf(bolt.id.toString()), mappedCounterspell.targets)
        assertTrue(
            snapshot.stack.any { it.id == mappedCounterspell.targets.single() },
            "the target id must resolve against the same snapshot -- that is the whole contract",
        )
    }

    @Test
    fun `a card that never targeted anything carries an empty target list, never a crash`() {
        // Upstream allocates `targets` only inside `addTargets`, which it calls only while building a
        // stack object -- so null is the ordinary case for a hand card or a battlefield permanent, and
        // `orEmpty()` in the mapper is load-bearing rather than defensive decoration.
        val forest = GameViews.card(name = "Forest")

        assertNull(forest.targets, "the fixture must reproduce upstream's null, or this proves nothing")
        assertTrue(GameViewMapper.mapCard(forest).targets.isEmpty())
    }

    @Test
    fun `a counter list upstream never populated maps to empty rather than throwing`() {
        // `CardView.counters` is left null unless the object actually has counters (upstream only
        // allocates the list when `Card.getCounters(game)` is non-empty), so null is the ordinary case
        // for most cards — not a drifted view.
        val sparse = GameViews.card(name = "Forest").apply { nullOutCounters() }

        assertTrue(GameViewMapper.mapCard(sparse).counters.isEmpty())
    }

    @Test
    fun `the icons the server computed cross the wire`() {
        // The whole reason this field exists: whether a creature has flying *right now* — after layers,
        // after a granted or removed ability — is a question only the server can answer. A bridge that
        // drops the answer leaves the client to parse rules text, which makes it a rules engine.
        val flier =
            GameViews.permanent(
                card = GameViews.card(name = "Serra Angel", icons = listOf(CardIconImpl.ABILITY_FLYING)),
            )

        val icon = GameViewMapper.mapCard(flier).icons.single()

        assertEquals(CardIconTypeCode.ABILITY_FLYING, icon.type)
        assertEquals("Flying", icon.hint)
    }

    @Test
    fun `an icon's text and hint both survive, because each carries something the other does not`() {
        // Upstream's `CardIconImpl(type, hint, text)` — hint first. For an announced X the text is the
        // value drawn on the icon and the hint is the sentence; dropping either loses information the
        // board or the inspect view needs.
        val announced = GameViews.card(name = "Fireball", icons = listOf(CardIconImpl.variableCost(3)))

        val icon = GameViewMapper.mapCard(announced).icons.single()

        assertEquals(CardIconTypeCode.OTHER_COST_X, icon.type)
        assertEquals("x=3", icon.text)
        assertEquals("Announced X = 3", icon.hint)
    }

    @Test
    fun `shroud arrives as the hexproof icon, told apart only by its hint`() {
        // Upstream's own doing: `CardIconImpl.ABILITY_SHROUD` is built on `CardIconType.ABILITY_HEXPROOF`.
        // Carrying the hint is what keeps the two distinguishable at all downstream.
        val shrouded = GameViews.card(name = "Thrun", icons = listOf(CardIconImpl.ABILITY_SHROUD))

        val icon = GameViewMapper.mapCard(shrouded).icons.single()

        assertEquals(CardIconTypeCode.ABILITY_HEXPROOF, icon.type)
        assertEquals("Shroud", icon.hint)
    }

    @Test
    fun `an icon list upstream never populated maps to empty rather than throwing`() {
        // `CardView` initialises `cardIcons` inline, but its own copy constructor still null-checks it
        // (`if (cardView.cardIcons != null)`), so null is a state upstream itself expects to meet.
        val sparse = GameViews.card(name = "Forest").apply { nullOutCardIcons() }

        assertTrue(GameViewMapper.mapCard(sparse).icons.isEmpty())
    }

    @Test
    fun `every icon type upstream defines has a code of its own`() {
        // The guard where upstream's set and ours meet. If a future mage-common adds an icon, this fails
        // naming it, rather than silently mapping a real ability to UNKNOWN and hiding it from the board.
        CardIconType.values().forEach { upstream ->
            val mapped =
                GameViewMapper
                    .mapCard(GameViews.card(icons = listOf(CardIconImpl(upstream, "hint"))))
                    .icons
                    .single()

            assertEquals(upstream.name, mapped.type.name, "upstream's $upstream has no code of its own")
        }
    }

    @Test
    fun `every card type upstream defines has a code of its own`() {
        // The guard on the one place upstream's set and ours meet. If a future mage-common adds a card
        // type, this fails here — naming the type — rather than silently mapping it to UNKNOWN and
        // making an animated whatever-it-is look like a noncreature to the board.
        CardType.values().forEach { upstream ->
            val mapped =
                GameViewMapper
                    .mapCard(
                        GameViews.card(cardTypes = listOf(upstream), superTypes = emptyList(), subTypes = emptyList()),
                    ).cardTypes
                    .single()

            assertEquals(upstream.name, mapped.name, "upstream's $upstream has no code of its own")
        }
    }

    @Test
    fun `mapCard names an AbilityView from its source card, not upstream's literal placeholder`() {
        // AbilityView.java (mage-common) hardcodes `this.name = "Ability"` for the ordinary case --
        // confirmed by reading the constructor directly, not assumed. The real name is only on the
        // nested getSourceCard(). Prove the fix reads it, and that `rules` (already correct) survives.
        val abilityView =
            GameViews.abilityView(
                sourceCard = GameViews.card(name = "Guide of Souls"),
                rules = listOf("Whenever another creature enters, gain 1 life."),
            )

        val mapped = GameViewMapper.mapCard(abilityView)

        assertEquals("Ability", abilityView.name, "sanity check: upstream's own placeholder is literally this")
        assertEquals("Guide of Souls", mapped.name, "the mapper must read the source card's name, not the placeholder")
        assertEquals(listOf("Whenever another creature enters, gain 1 life."), mapped.rules)
    }

    @Test
    fun `mapCard falls back to the placeholder if an AbilityView somehow has no source card`() {
        assertEquals("Ability", GameViewMapper.mapCard(GameViews.abilityView(sourceCard = null)).name)
    }

    @Test
    fun `mapCard names a StackAbilityView from its source card too`() {
        // StackAbilityView is a *separate* sibling class from AbilityView (does not extend it) --
        // upstream's own comment on the field says its view "will be replaced by sourceCard" in the
        // GUI, confirming this is the sanctioned way to read it, not a guess.
        val stackAbilityView = GameViews.stackAbilityView(sourceCard = GameViews.card(name = "Guide of Souls"))

        assertEquals("Guide of Souls", GameViewMapper.mapCard(stackAbilityView).name)
    }

    @Test
    fun `mapCard resolves art for an AbilityView from its source card, not its own (blank) identity`() {
        // after the naming fix above shipped: Soul Warden's own
        // triggered ability had no art at all. AbilityView never sets expansionSetCode/cardNumber on
        // itself -- only the nested sourceCard carries the real printing, which the art loader keys
        // requests on. Distinct defect from the naming one: this stays broken even once the name
        // renders correctly, since it's a different pair of fields.
        val abilityView = GameViews.abilityView(sourceCard = GameViews.card(setCode = "SOI", collectorNumber = "17"))

        val mapped = GameViewMapper.mapCard(abilityView)

        assertEquals("SOI", mapped.setCode)
        assertEquals("17", mapped.collectorNumber)
    }

    @Test
    fun `mapCard resolves art for a StackAbilityView from its source card too`() {
        val stackAbilityView = GameViews.stackAbilityView(sourceCard = GameViews.card(setCode = "SOI", collectorNumber = "17"))

        val mapped = GameViewMapper.mapCard(stackAbilityView)

        assertEquals("SOI", mapped.setCode)
        assertEquals("17", mapped.collectorNumber)
    }

    /** Nulls out every collection field of a [GameView], standing in for a sparse upstream view. */
    private fun GameView.setEveryCollectionNull() {
        listOf("players", "myHand", "stack", "exiles", "revealed", "combat", "canPlayObjects").forEach { name ->
            val field = GameView::class.java.getDeclaredField(name)
            field.isAccessible = true
            field.set(this, null)
        }
    }

    /**
     * Nulls a `PlayerView`'s graveyard and exile. A real view never has them null — both are `final`
     * fields initialised inline — but the serialization constructor these fixtures use does, which is
     * the sparse-view case the mapper has to survive.
     */
    private fun PlayerView.nullOutZones(): PlayerView =
        apply {
            listOf("graveyard", "exile").forEach { name ->
                val field = PlayerView::class.java.getDeclaredField(name)
                field.isAccessible = true
                field.set(this, null)
            }
        }

    /** Nulls `CardView.cardIcons`, reproducing the null upstream's own copy constructor guards against. */
    private fun CardView.nullOutCardIcons() {
        val field = CardView::class.java.getDeclaredField("cardIcons")
        field.isAccessible = true
        field.set(this, null)
    }

    /** Nulls `CardView.counters`, which is upstream's state for "this object has no counters at all". */
    private fun CardView.nullOutCounters() {
        val field = CardView::class.java.getDeclaredField("counters")
        field.isAccessible = true
        field.set(this, null)
    }

    /** Nulls the backing collections `getTypeText()`/`getManaCostStr()` walk, so both getters throw. */
    private fun CardView.breakComposedText() {
        listOf("cardTypes", "superTypes", "subTypes", "manaCostLeftStr", "manaCostRightStr").forEach { name ->
            val field = CardView::class.java.getDeclaredField(name)
            field.isAccessible = true
            field.set(this, null)
        }
    }

    /**
     * A `CommandObjectView` that is none of upstream's four implementations — what a fifth would look
     * like to the mapper's `when`. Written by hand rather than allocated reflectively, because the
     * point is a type the branch has never seen.
     */
    private object UnknownCommandObject : CommandObjectView {
        private val objectId: UUID = UUID.randomUUID()

        override fun getExpansionSetCode(): String = "XYZ"

        override fun getName(): String = "Something New"

        override fun getId(): UUID = objectId

        override fun getImageFileName(): String = ""

        override fun getImageNumber(): Int = 0

        override fun getRules(): List<String> = listOf("It does a thing.")

        override fun isPlayable(): Boolean = false

        override fun setPlayableStats(playableStats: PlayableObjectStats) = Unit

        override fun getPlayableStats(): PlayableObjectStats = PlayableObjectStats()

        override fun isChoosable(): Boolean = false

        override fun setChoosable(isChoosable: Boolean) = Unit

        override fun isSelected(): Boolean = false

        override fun setSelected(isSelected: Boolean) = Unit
    }
}
