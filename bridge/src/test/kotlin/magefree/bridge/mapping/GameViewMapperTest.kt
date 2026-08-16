package magefree.bridge.mapping

import mage.abilities.Ability
import mage.constants.CardType
import mage.constants.PhaseStep
import mage.constants.SubType
import mage.constants.TurnPhase
import mage.view.AbilityView
import mage.view.CardView
import mage.view.GameView
import mage.view.StackAbilityView
import magefree.protocol.CardTypeCode
import magefree.protocol.PhaseStepCode
import magefree.protocol.TurnPhaseCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import sun.misc.Unsafe
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
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
        // can render is always better than an exception that costs it the whole push (0006/0026-F5).
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

    // ---- story 0058: what a card currently *is* -----------------------------------------------------

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
    fun `a counter list upstream never populated maps to empty rather than throwing`() {
        // `CardView.counters` is left null unless the object actually has counters (upstream only
        // allocates the list when `Card.getCounters(game)` is non-empty), so null is the ordinary case
        // for most cards — not a drifted view.
        val sparse = GameViews.card(name = "Forest").apply { nullOutCounters() }

        assertTrue(GameViewMapper.mapCard(sparse).counters.isEmpty())
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
    fun `mapCard names an AbilityView from its source card, not upstream's literal placeholder (story 0072)`() {
        // AbilityView.java (mage-common) hardcodes `this.name = "Ability"` for the ordinary case --
        // confirmed by reading the constructor directly, not assumed. The real name is only on the
        // nested getSourceCard(). Prove the fix reads it, and that `rules` (already correct) survives.
        val sourceCard = GameViews.card(name = "Guide of Souls")
        val abilityView = AbilityView(fakeAbility(rule = "Whenever another creature enters, gain 1 life."), "Guide of Souls", sourceCard)

        val mapped = GameViewMapper.mapCard(abilityView)

        assertEquals("Ability", abilityView.name, "sanity check: upstream's own placeholder is literally this")
        assertEquals("Guide of Souls", mapped.name, "the mapper must read the source card's name, not the placeholder")
        assertEquals(listOf("Whenever another creature enters, gain 1 life."), mapped.rules)
    }

    @Test
    fun `mapCard falls back to the placeholder if an AbilityView somehow has no source card`() {
        val abilityView = AbilityView(fakeAbility(), "Unknown", null)

        assertEquals("Ability", GameViewMapper.mapCard(abilityView).name)
    }

    @Test
    fun `mapCard names a StackAbilityView from its source card too (story 0072)`() {
        // StackAbilityView is a *separate* sibling class from AbilityView (does not extend it) --
        // upstream's own comment on the field says its view "will be replaced by sourceCard" in the
        // GUI, confirming this is the sanctioned way to read it, not a guess. Its real constructor
        // needs a full Game/StackAbility context impractical to fabricate hermetically, so this
        // allocates the instance directly (bypassing the constructor, same idea as this file's
        // existing nullOutCounters/breakComposedText reflection helpers) and sets only the two
        // fields displayName() reads.
        val stackAbilityView = allocateStackAbilityView(name = "Ability", sourceCardName = "Guide of Souls")

        assertEquals("Guide of Souls", GameViewMapper.mapCard(stackAbilityView).name)
    }

    /** Allocates a [StackAbilityView] without running its constructor and sets [name]/`sourceCard`. */
    private fun allocateStackAbilityView(
        name: String,
        sourceCardName: String,
    ): StackAbilityView {
        val unsafeField = Unsafe::class.java.getDeclaredField("theUnsafe")
        unsafeField.isAccessible = true
        val unsafe = unsafeField.get(null) as Unsafe
        val instance = unsafe.allocateInstance(StackAbilityView::class.java) as StackAbilityView

        val nameField = CardView::class.java.getDeclaredField("name")
        nameField.isAccessible = true
        nameField.set(instance, name)

        val sourceCardField = StackAbilityView::class.java.getDeclaredField("sourceCard")
        sourceCardField.isAccessible = true
        sourceCardField.set(instance, GameViews.card(name = sourceCardName))

        return instance
    }

    /**
     * A minimal [Ability] double via [Proxy] — [Ability] is a large interface with no simple concrete
     * implementation that doesn't require a live `Game`/permanent context. [AbilityView]'s constructor
     * only calls three methods on it (verified by reading the constructor directly), so only those need
     * real answers; everything else returns `null`/defaults, which is never reached.
     */
    private fun fakeAbility(rule: String = "Test rule text."): Ability {
        val handler =
            InvocationHandler { _, method, _ ->
                when (method.name) {
                    "getId" -> UUID.randomUUID()
                    "getRule" -> rule
                    "getManaCostSymbols" -> emptyList<String>()
                    "hashCode" -> 0
                    "equals" -> false
                    "toString" -> "fakeAbility"
                    else -> null
                }
            }
        return Proxy.newProxyInstance(Ability::class.java.classLoader, arrayOf(Ability::class.java), handler) as Ability
    }

    /** Nulls out every collection field of a [GameView], standing in for a sparse upstream view. */
    private fun GameView.setEveryCollectionNull() {
        listOf("players", "myHand", "stack", "exiles", "revealed", "combat", "canPlayObjects").forEach { name ->
            val field = GameView::class.java.getDeclaredField(name)
            field.isAccessible = true
            field.set(this, null)
        }
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
}
