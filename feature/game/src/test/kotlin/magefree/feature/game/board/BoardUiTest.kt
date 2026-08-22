package magefree.feature.game.board

import magefree.cards.art.CardArtFace
import magefree.cards.art.CardArtSize
import magefree.network.game.CardType
import magefree.network.game.CombatGroup
import magefree.network.game.GameCard
import magefree.network.game.GameCounter
import magefree.network.game.GameMessage
import magefree.network.game.GamePermanent
import magefree.network.game.GamePlayer
import magefree.network.game.GamePrompt
import magefree.network.game.GameResult
import magefree.network.game.GameState
import magefree.network.game.GameZone
import magefree.network.game.ManaPool
import magefree.network.game.PhaseStep
import magefree.network.game.PlayableObject
import magefree.network.game.TurnPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hermetic coverage of [BoardUi.from] — the whole projection the board draws, over crafted [GameState]s.
 *
 * Every test here exists because of a specific way the board could lie about the server's data, and each
 * was demonstrated failing against an implementation that told that lie before the correct one was
 * written (verification standard 1). The two that matter most are the seat lookup (an index-based one
 * seats the player behind the opponent's cards) and the priority gate on `playable` (a board that reads
 * `playable` unconditionally tells the player a card is unplayable when the truth is "not your moment").
 */
class BoardUiTest {
    // ---- seats: located by `isViewer`, never by index ---------------------------------------------

    @Test
    fun `locates the viewer's seat by isViewer when the opponent is first in the list`() {
        val board = BoardUi.from(twoSeatState(viewerFirst = false))

        assertEquals("you", board.viewerSeat?.name)
        assertEquals(listOf("Computer"), board.opponentSeats.map { it.name })
        assertEquals(
            "the viewer's own battlefield must follow the viewer's seat",
            listOf("Forest"),
            board.viewerSeat?.battlefield?.map {
                it.card.name
            },
        )
        assertEquals(
            listOf("Mountain"),
            board.opponentSeats
                .single()
                .battlefield
                .map { it.card.name },
        )
    }

    @Test
    fun `locates the viewer's seat by isViewer when the viewer is first in the list`() {
        val board = BoardUi.from(twoSeatState(viewerFirst = true))

        assertEquals("you", board.viewerSeat?.name)
        assertEquals(listOf("Computer"), board.opponentSeats.map { it.name })
        assertEquals(listOf("Forest"), board.viewerSeat?.battlefield?.map { it.card.name })
    }

    @Test
    fun `carries every vital the seat bar shows`() {
        val board = BoardUi.from(twoSeatState(viewerFirst = false))
        val you = board.viewerSeat!!

        assertEquals(20, you.life)
        assertEquals(53, you.libraryCount)
        assertEquals(7, you.handCount)
        assertEquals(2, you.graveyardCount)
        assertEquals(1, you.exileCount)
        assertEquals("the pool total is what the bar shows", 3, you.floatingMana)
        assertEquals("1/2", you.matchScore)
        assertTrue(you.isActive)
        assertTrue(you.hasPriority)
        assertFalse(you.hasLeft)
        assertTrue(you.isViewer)
    }

    @Test
    fun `a seat with no wins needed has no match score line`() {
        val state = twoSeatState(viewerFirst = false)
        val board = BoardUi.from(state.copy(players = state.players.map { it.copy(wins = 0, winsNeeded = 0) }))

        assertNull("an unrated single game must not draw a 0/0 score", board.viewerSeat?.matchScore)
    }

    // ---- empty states -----------------------------------------------------------------------------

    @Test
    fun `a board with no snapshot yet is empty in every region`() {
        val board = BoardUi.from(GameState("g-1"))

        assertFalse(board.hasSnapshot)
        assertNull("no seat has been reported", board.viewerSeat)
        assertTrue(board.opponentSeats.isEmpty())
        assertTrue("the hand region shows its empty state", board.hand.isEmpty)
        assertEquals(0, board.hand.count)
        assertTrue("the stack region shows its empty state", board.stack.isEmpty)
        assertNull(board.stack.top)
        assertFalse("no exile has been reported", board.hasExiledCards)
        assertNull(board.promptNotice)
        assertNull(board.narration)
        assertNull(board.errorNotice)
        assertNull(board.resultNotice)
        assertNull("an untimed/unstarted table shows no clock", board.clock)
        assertEquals(PriorityUi.NotStarted, board.priority)
        assertFalse("a turn has not started", board.turn.hasStarted)
        assertEquals("Turn —", board.turn.turnLabel)
    }

    @Test
    fun `the first real snapshot legitimately has an empty hand and still renders the rest`() {
        // Verified live (story 0052): GAME_INIT fires before the game worker deals, so the first
        // snapshot carries seats and a turn with an empty hand. The board must not treat that as
        // "nothing has arrived".
        val state = twoSeatState(viewerFirst = false).copy(hand = emptyList())
        val board = BoardUi.from(state)

        assertTrue("the snapshot did arrive", board.hasSnapshot)
        assertTrue("the hand is genuinely empty", board.hand.isEmpty)
        assertNotNull("the seats arrived with it", board.viewerSeat)
        assertEquals(
            2,
            board.opponentSeats
                .single()
                .battlefield.size + board.viewerSeat!!.battlefield.size,
        )
    }

    @Test
    fun `a seat with nothing on the battlefield reports its own empty state`() {
        val state = twoSeatState(viewerFirst = false)
        val board = BoardUi.from(state.copy(players = state.players.map { it.copy(battlefield = emptyList()) }))

        assertTrue(board.viewerSeat!!.hasNoPermanents)
        assertTrue(board.opponentSeats.single().hasNoPermanents)
    }

    // ---- `playable` is read ONLY through the priority gate ----------------------------------------

    @Test
    fun `holding priority with nothing playable is stated in words, not left to an absent highlight`() {
        // Requirements §4.2: this state genuinely occurs, and a glow-only design cannot express it.
        val board =
            BoardUi.from(
                twoSeatState(viewerFirst = false).copy(viewerHasPriority = true, playable = emptyList()),
            )

        val priority = board.priority
        assertTrue(priority is PriorityUi.Yours)
        assertEquals("Your turn to act", priority.headline)
        assertEquals("Nothing the server offers you right now.", priority.detail)
        assertTrue("no card may be marked when the server offered none", board.hand.cards.none { it.isOfferedByServer })
    }

    @Test
    fun `marks exactly the objects the server offered while priority is held`() {
        val board =
            BoardUi.from(
                twoSeatState(viewerFirst = false).copy(
                    viewerHasPriority = true,
                    playable = listOf(PlayableObject(objectId = "h-1", abilityIds = listOf("a-1"))),
                ),
            )

        assertEquals(listOf(true, false), board.hand.cards.map { it.isOfferedByServer })
        val priority = board.priority
        assertTrue(priority is PriorityUi.Yours)
        assertNull("with something offered there is nothing extra to say", priority.detail)
    }

    @Test
    fun `never marks anything while priority is not held, whatever playable happens to contain`() {
        // `playable` is the server's answer *about the viewer's own priority window* — outside it the
        // list says nothing about a card. Reading it unconditionally would let the board tell the player
        // "this is not playable" when the truth is "this is not your moment". The gate is therefore on
        // priority, not on the list being empty; this pins that even a non-empty list is ignored.
        val board =
            BoardUi.from(
                twoSeatState(viewerFirst = false).copy(
                    viewerHasPriority = false,
                    playable = listOf(PlayableObject(objectId = "h-1"), PlayableObject(objectId = "y-1")),
                ),
            )

        assertTrue("no hand card may be marked", board.hand.cards.none { it.isOfferedByServer })
        assertTrue("no permanent may be marked", board.viewerSeat!!.battlefield.none { it.isOfferedByServer })
        assertEquals(PriorityUi.Opponent(playerName = "Computer"), board.priority)
        assertEquals("Waiting for opponent", board.priority.headline)
        assertEquals("Computer has priority", board.priority.detail)
    }

    @Test
    fun `says the server is waiting on you when it asks a question you do not hold priority for`() {
        // FOUND ON DEVICE (2026-08-13). The first thing a new game does is ask one seat to choose who
        // goes first, and it does that before priority exists: the live snapshot was
        // `viewerHasPriority=false, prompt=Target('Select a starting player')`, and the server's own
        // narration on the same push was "Waiting for <font …>Player</font>" — i.e. waiting on us. The
        // banner said "Waiting for opponent". Requirements §16.3 forbids exactly that: the player must
        // never be unable to tell a waiting game from a frozen one.
        val board =
            BoardUi.from(
                twoSeatState(viewerFirst = false).copy(
                    viewerHasPriority = false,
                    priorityPlayerName = null,
                    prompt = GamePrompt.Target(message = "Select a starting player", isRequired = true),
                ),
            )

        assertEquals(PriorityUi.Asked, board.priority)
        assertEquals("The server is waiting on you", board.priority.headline)
    }

    @Test
    fun `priority still wins over the question when both are true`() {
        // A Select prompt *is* the viewer's priority window, so "Your turn to act" is the truer line.
        val board =
            BoardUi.from(
                twoSeatState(viewerFirst = false).copy(
                    viewerHasPriority = true,
                    prompt = GamePrompt.Select(message = "Select an ability to play"),
                ),
            )

        assertTrue(board.priority is PriorityUi.Yours)
    }

    @Test
    fun `with no question outstanding it is plainly the opponent's moment`() {
        val board =
            BoardUi.from(
                twoSeatState(viewerFirst = false).copy(viewerHasPriority = false, prompt = null),
            )

        assertEquals(PriorityUi.Opponent(playerName = "Computer"), board.priority)
    }

    // ---- exile: judged by cards, never by the zone list's size --------------------------------------

    @Test
    fun `an exile zone that exists but holds nothing does not mean something is exiled`() {
        // Verified live: `GameView.exile` carries one zone entry even when nothing has been exiled, so a
        // board that asks `exile.isNotEmpty()` reports an exiled card in every single game.
        val board =
            BoardUi.from(
                twoSeatState(viewerFirst = false).copy(exile = listOf(GameZone(name = "Exile", cards = emptyList()))),
            )

        assertEquals(0, board.exiledCardCount)
        assertFalse(board.hasExiledCards)
    }

    @Test
    fun `counts exiled cards across every exile zone`() {
        val board =
            BoardUi.from(
                twoSeatState(viewerFirst = false).copy(
                    exile =
                        listOf(
                            GameZone(name = "Exile", cards = listOf(card("e-1", "Swamp"))),
                            GameZone(name = "Bounce", cards = listOf(card("e-2", "Plains"), card("e-3", "Island"))),
                        ),
                ),
            )

        assertEquals(3, board.exiledCardCount)
        assertTrue(board.hasExiledCards)
    }

    // ---- cards -------------------------------------------------------------------------------------

    @Test
    fun `a land's null mana cost stays null rather than becoming an empty cost`() {
        val board = BoardUi.from(twoSeatState(viewerFirst = false))
        val land = board.hand.cards.first { it.card.name == "Forest" }

        assertNull(land.card.display.manaCost)
        assertEquals("Basic Land — Forest", land.card.display.typeLine)
    }

    @Test
    fun `builds the art request from the printing the server named`() {
        val board = BoardUi.from(twoSeatState(viewerFirst = false))
        val art =
            board.hand.cards
                .first { it.card.name == "Forest" }
                .card.art!!

        assertEquals("M21", art.setCode)
        assertEquals("272", art.collectorNumber)
        assertEquals(CardArtFace.FRONT, art.face)
        assertEquals(CardArtSize.SMALL, art.size)
    }

    @Test
    fun `requests the back face for a transformed permanent (story 0076)`() {
        // Found live (Pete, 2026-08-17): Kytheon, Hero of Akros transformed into Gideon,
        // Battle-Forged, but the board kept showing Kytheon's art. `GameCard.transformed` -- upstream's
        // own live "is this permanent currently showing its back face" fact -- is the real signal; a
        // DFC's two faces share one setCode/collectorNumber, so CardArtFace is the only thing that can
        // tell them apart.
        val state = twoSeatState(viewerFirst = false)
        val transformed =
            card("perm-1", "Gideon, Battle-Forged")
                .copy(setCode = "ORI", collectorNumber = "23", alternateName = "Kytheon, Hero of Akros", transformed = true)
        val board =
            BoardUi.from(
                state.copy(players = state.players.map { it.copy(battlefield = listOf(GamePermanent(card = transformed))) }),
            )

        val art =
            board.viewerSeat!!
                .battlefield
                .single()
                .card.art!!

        assertEquals("ORI", art.setCode)
        assertEquals("23", art.collectorNumber)
        assertEquals(CardArtFace.BACK, art.face)
    }

    @Test
    fun `an untransformed card still requests the front face (story 0076)`() {
        val state = twoSeatState(viewerFirst = false)
        val ordinary = card("perm-1", "Grizzly Bears")
        val board =
            BoardUi.from(
                state.copy(players = state.players.map { it.copy(battlefield = listOf(GamePermanent(card = ordinary))) }),
            )

        val art =
            board.viewerSeat!!
                .battlefield
                .single()
                .card.art!!
        assertEquals(CardArtFace.FRONT, art.face)
    }

    @Test
    fun `an untransformed permanent requests the front face even when alternateName is set (story 0076, found live again)`() {
        // Found live (Pete, 2026-08-22): Ajani, Nacatl Pariah on the battlefield, UNTRANSFORMED, showed
        // Ajani, Nacatl Avenger's (the back face's) art. Upstream sets alternateName unconditionally on
        // any transformable permanent regardless of state, so a non-null alternateName here must not
        // flip the requested face -- only `transformed` does that.
        val state = twoSeatState(viewerFirst = false)
        val untransformed =
            card("perm-1", "Ajani, Nacatl Pariah").copy(alternateName = "Ajani, Nacatl Avenger", transformed = false)
        val board =
            BoardUi.from(
                state.copy(players = state.players.map { it.copy(battlefield = listOf(GamePermanent(card = untransformed))) }),
            )

        val art =
            board.viewerSeat!!
                .battlefield
                .single()
                .card.art!!
        assertEquals(CardArtFace.FRONT, art.face)
    }

    @Test
    fun `asks for no art when the server named no printing`() {
        val state = twoSeatState(viewerFirst = false)
        val board = BoardUi.from(state.copy(hand = listOf(card("h-9", "Mystery").copy(setCode = null, collectorNumber = null))))

        assertNull(
            "a card with no printing degrades to the placeholder",
            board.hand.cards
                .single()
                .card.art,
        )
    }

    @Test
    fun `never asks for the art of a face-down card, and never names it`() {
        val state = twoSeatState(viewerFirst = false)
        val hidden = card("o-9", "Ancestral Recall").copy(isFaceDown = true)
        val board =
            BoardUi.from(
                state.copy(players = state.players.map { it.copy(battlefield = listOf(GamePermanent(card = hidden))) }),
            )

        val permanent = board.viewerSeat!!.battlefield.single()
        assertEquals(FACE_DOWN_NAME, permanent.card.name)
        assertNull("fetching its art would show the player a card they may not see", permanent.card.art)
        assertNull(permanent.card.display.typeLine)
        assertTrue(permanent.card.isFaceDown)
    }

    @Test
    fun `carries the battlefield-only state the server reports`() {
        val state = twoSeatState(viewerFirst = false)
        val board =
            BoardUi.from(
                state.copy(
                    players =
                        state.players.map { player ->
                            if (!player.isViewer) {
                                player
                            } else {
                                player.copy(
                                    battlefield =
                                        listOf(
                                            GamePermanent(
                                                // A bear really is a creature in the server's snapshot, and the
                                                // fixture has to say so: a fake that differs from production is a
                                                // defect in the fake.
                                                card =
                                                    card("y-2", "Grizzly Bears").copy(
                                                        power = "2",
                                                        toughness = "2",
                                                        isCreature = true,
                                                        cardTypes = listOf(CardType.Creature),
                                                    ),
                                                isTapped = true,
                                                hasSummoningSickness = true,
                                                damage = 1,
                                            ),
                                        ),
                                )
                            }
                        },
                ),
            )

        val bear = board.viewerSeat!!.battlefield.single()
        assertTrue(bear.isTapped)
        assertTrue(bear.hasSummoningSickness)
        assertEquals(1, bear.damage)
        assertEquals("2/2", bear.card.powerToughness)
    }

    // ---- story 0058: what a permanent currently *is* -----------------------------------------------

    @Test
    fun `a land that is not a creature shows no power toughness and no summoning sickness`() {
        // The defect this story exists to fix, seen on device: "0/0 · Summoning sick" under a Mountain.
        // Upstream really does send "0"/"0" for a noncreature permanent and really does set summoning
        // sickness for anything that arrived this turn — so both must be gated on what the card *is*.
        val mountain =
            card("o-1", "Mountain").copy(power = "0", toughness = "0", isCreature = false, cardTypes = listOf(CardType.Land))

        val permanent = onlyPermanent(GamePermanent(card = mountain, hasSummoningSickness = true))

        assertNull("a land has no power or toughness to show", permanent.card.powerToughness)
        assertFalse("summoning sickness says nothing about a land", permanent.showsSummoningSickness)
        assertTrue("the server's own fact is still carried — only the rendering is gated", permanent.hasSummoningSickness)
    }

    @Test
    fun `a land an effect has animated shows its power toughness and can be summoning sick`() {
        // The mirror of the test above, and the one that stops "hide P/T for lands" from passing: an
        // Earthbent Mountain is a land AND a creature, and a board that reads the printed type gets it
        // wrong. Summoning sickness is real here too — an animated land that came under your control
        // this turn genuinely cannot attack.
        val earthbent =
            card("o-1", "Mountain").copy(
                power = "0",
                toughness = "3",
                isCreature = true,
                cardTypes = listOf(CardType.Land, CardType.Creature),
            )

        val permanent = onlyPermanent(GamePermanent(card = earthbent, hasSummoningSickness = true))

        assertEquals("0/3", permanent.card.powerToughness)
        assertTrue(permanent.showsSummoningSickness)
    }

    @Test
    fun `a creature that has lost its creature status stops showing power toughness`() {
        // Turgid Dross / an ended Crew: the same object, the next snapshot. Nothing is remembered.
        val vehicle = card("y-2", "Smuggler's Copter").copy(power = "3", toughness = "3", cardTypes = listOf(CardType.Artifact))

        val crewed = onlyPermanent(GamePermanent(card = vehicle.copy(isCreature = true)))
        val uncrewed = onlyPermanent(GamePermanent(card = vehicle.copy(isCreature = false)))

        assertEquals("3/3", crewed.card.powerToughness)
        assertNull("the effect ended, so the board must stop calling it a creature", uncrewed.card.powerToughness)
    }

    @Test
    fun `a star power is shown exactly as the server sent it`() {
        val goyf = card("y-2", "Tarmogoyf").copy(power = "*", toughness = "1+*", isCreature = true)

        assertEquals("*/1+*", onlyPermanent(GamePermanent(card = goyf)).card.powerToughness)
    }

    @Test
    fun `a creature the server sent no power for shows none`() {
        // Defensive, and the reason the check is on the strings rather than on `isCreature` alone: a
        // half-filled view must not render "null/2".
        val odd = card("y-2", "Mystery").copy(power = null, toughness = "2", isCreature = true)

        assertNull(onlyPermanent(GamePermanent(card = odd)).card.powerToughness)
    }

    @Test
    fun `counters are carried by name and count, whatever the kind`() {
        val ballista =
            card("y-2", "Walking Ballista").copy(
                power = "2",
                toughness = "2",
                isCreature = true,
                counters = listOf(GameCounter("+1/+1", 2), GameCounter("stun", 1), GameCounter("oil", 3)),
            )

        val counters = onlyPermanent(GamePermanent(card = ballista)).card.counters

        assertEquals(listOf("+1/+1", "stun", "oil"), counters.map { it.name })
        assertEquals(listOf(2, 1, 3), counters.map { it.count })
        assertEquals(listOf("+1/+1 ×2", "stun ×1", "oil ×3"), counters.map { it.label })
    }

    @Test
    fun `a permanent with no counters carries none`() {
        assertTrue(onlyPermanent(GamePermanent(card = card("y-2", "Grizzly Bears"))).card.counters.isEmpty())
    }

    @Test
    fun `counters are not battlefield-only - a card in hand carries the ones the server sent`() {
        // `CardView` carries counters, not just `PermanentView`, so the board renders them wherever the
        // data is present rather than assuming a zone.
        val state = twoSeatState(viewerFirst = false)
        val loyal = card("h-3", "Relic of Progenitus").copy(counters = listOf(GameCounter("charge", 4)))

        val board = BoardUi.from(state.copy(hand = listOf(loyal)))

        assertEquals(
            listOf("charge ×4"),
            board.hand.cards
                .single()
                .card.counters
                .map { it.label },
        )
    }

    @Test
    fun `a noncreature that is somehow summoning sick still shows its counters`() {
        val enchantment =
            card("y-2", "Luminarch Ascension").copy(
                power = "0",
                toughness = "0",
                cardTypes = listOf(CardType.Enchantment),
                counters = listOf(GameCounter("quest", 3)),
            )

        val permanent = onlyPermanent(GamePermanent(card = enchantment, hasSummoningSickness = true))

        assertNull(permanent.card.powerToughness)
        assertFalse(permanent.showsSummoningSickness)
        assertEquals(listOf("quest ×3"), permanent.card.counters.map { it.label })
    }

    /** The viewer's single permanent, for the story-0058 cases that only care about one card. */
    private fun onlyPermanent(permanent: GamePermanent): PermanentUi {
        val state = twoSeatState(viewerFirst = false)
        val board =
            BoardUi.from(
                state.copy(players = state.players.map { if (it.isViewer) it.copy(battlefield = listOf(permanent)) else it }),
            )
        return board.viewerSeat!!.battlefield.single()
    }

    @Test
    fun `marks attackers and blockers from the combat groups`() {
        val state = twoSeatState(viewerFirst = false)
        val board =
            BoardUi.from(
                state.copy(
                    combat = listOf(CombatGroup(attackerIds = listOf("o-1"), blockerIds = listOf("y-1"), isBlocked = true)),
                ),
            )

        assertTrue(
            board.opponentSeats
                .single()
                .battlefield
                .single { it.objectId == "o-1" }
                .isAttacking,
        )
        assertTrue(
            board.viewerSeat!!
                .battlefield
                .single { it.objectId == "y-1" }
                .isBlocking,
        )
    }

    @Test
    fun `an attacker says what it is attacking, and what is blocking it`() {
        // Story 0061: marking a creature "Attacking" is not enough to follow a fight — with two
        // attackers and a planeswalker in play, *what* it attacks is the whole question. The defender's
        // name is the server's own (`CombatGroup.defenderName`), and a defender is not always a player.
        val state = twoSeatState(viewerFirst = false)
        val board =
            BoardUi.from(
                state.copy(
                    combat =
                        listOf(
                            CombatGroup(
                                defenderId = "p-you",
                                defenderName = "you",
                                isBlocked = true,
                                attackerIds = listOf("o-1"),
                                blockerIds = listOf("y-1"),
                            ),
                        ),
                ),
            )

        val attacker =
            board.opponentSeats
                .single()
                .battlefield
                .single { it.objectId == "o-1" }
        assertEquals("you", attacker.attackingDefenderName)
        assertEquals(listOf("Forest"), attacker.blockedByNames)
        assertEquals("$ATTACKING_MARK you · $BLOCKED_BY_MARK Forest", attacker.combatSummary)

        val blocker = board.viewerSeat!!.battlefield.single { it.objectId == "y-1" }
        assertEquals(listOf("Mountain"), blocker.blockingAttackerNames)
        assertEquals("$BLOCKING_MARK Mountain", blocker.combatSummary)
    }

    @Test
    fun `a planeswalker being attacked is named as the defender, not the opposing player`() {
        // A defender is not always the opposing player — planeswalkers and battles are legal defenders
        // in ordinary 1v1 (§7.4). A board that said "attacking Computer" here would be lying.
        val state = twoSeatState(viewerFirst = false)
        val board =
            BoardUi.from(
                state.copy(
                    combat =
                        listOf(
                            CombatGroup(defenderId = "pw-1", defenderName = "Tibalt, the Fiend-Blooded", attackerIds = listOf("o-1")),
                        ),
                ),
            )

        val attacker =
            board.opponentSeats
                .single()
                .battlefield
                .single { it.objectId == "o-1" }
        assertEquals("$ATTACKING_MARK Tibalt, the Fiend-Blooded", attacker.combatSummary)
    }

    @Test
    fun `two attackers are two groups with the defender repeated, and each says so`() {
        // Measured live (§7.3): `CombatGroup` is **per-attacker**, so two attackers produced two groups
        // rather than one group with two attackers. A projection that assumed one group per defender
        // would silently drop the second attacker.
        val state = twoSeatState(viewerFirst = false)
        val opponent =
            state.players.first { !it.isViewer }.copy(
                battlefield = listOf(GamePermanent(card = card("o-1", "Goblin Token")), GamePermanent(card = card("o-2", "Goblin Token"))),
            )
        val board =
            BoardUi.from(
                state.copy(
                    players = state.players.map { if (it.isViewer) it else opponent },
                    combat =
                        listOf(
                            CombatGroup(defenderId = "p-you", defenderName = "you", attackerIds = listOf("o-1")),
                            CombatGroup(defenderId = "p-you", defenderName = "you", attackerIds = listOf("o-2")),
                        ),
                ),
            )

        assertEquals(
            listOf("$ATTACKING_MARK you", "$ATTACKING_MARK you"),
            board.opponentSeats
                .single()
                .battlefield
                .map { it.combatSummary },
        )
    }

    @Test
    fun `an empty combat marks nothing`() {
        val board = BoardUi.from(twoSeatState(viewerFirst = false))

        assertTrue(board.viewerSeat!!.battlefield.none { it.isAttacking || it.isBlocking })
        assertTrue(
            board.opponentSeats
                .single()
                .battlefield
                .none { it.isAttacking || it.isBlocking },
        )
        // …and says nothing about combat either, which is the ordinary state of most of a game.
        assertNull(
            board.viewerSeat!!
                .battlefield
                .single()
                .combatSummary,
        )
    }

    // ---- the stack ---------------------------------------------------------------------------------

    @Test
    fun `reads the stack top first, because upstream sends the top last`() {
        val board =
            BoardUi.from(
                twoSeatState(viewerFirst = false).copy(
                    stack = listOf(card("s-1", "Dragon Fodder"), card("s-2", "Forked Bolt")),
                ),
            )

        assertEquals(2, board.stack.count)
        assertFalse(board.stack.isEmpty)
        assertEquals(
            "Forked Bolt",
            board.stack.top
                ?.card
                ?.name,
        )
        assertEquals(listOf("Forked Bolt", "Dragon Fodder"), board.stack.entries.map { it.card.name })
    }

    // ---- turn, phase, priority ---------------------------------------------------------------------

    @Test
    fun `names the turn, the step and whose turn it is`() {
        val board = BoardUi.from(twoSeatState(viewerFirst = false))

        assertEquals("Turn 3", board.turn.turnLabel)
        assertEquals("Main 1", board.turn.phaseLabel)
        assertTrue(board.turn.isViewerTurn)
        assertEquals("you", board.turn.activePlayerName)
    }

    @Test
    fun `falls back to the phase when the server reported no step this build knows`() {
        val board =
            BoardUi.from(
                twoSeatState(viewerFirst = false).copy(step = PhaseStep.Unknown, phase = TurnPhase.Combat),
            )

        assertEquals("Combat", board.turn.phaseLabel)
    }

    @Test
    fun `says so plainly when neither phase nor step is known`() {
        val board =
            BoardUi.from(
                twoSeatState(viewerFirst = false).copy(step = PhaseStep.Unknown, phase = TurnPhase.Unknown),
            )

        assertEquals("…", board.turn.phaseLabel)
    }

    @Test
    fun `it is not the viewer's turn when the active seat is the opponent's`() {
        val board = BoardUi.from(twoSeatState(viewerFirst = false).copy(activePlayerId = "p-opp"))

        assertFalse(board.turn.isViewerTurn)
    }

    // ---- clocks ------------------------------------------------------------------------------------

    @Test
    fun `an untimed table shows no clock at all`() {
        // Verified live: the reference server reports 0 on an untimed table. "0:00" would be a lie.
        val board = BoardUi.from(twoSeatState(viewerFirst = false).copy(priorityTimeSeconds = 0))

        assertNull(board.clock)
    }

    @Test
    fun `a timed table shows the remaining priority clock`() {
        val board = BoardUi.from(twoSeatState(viewerFirst = false).copy(priorityTimeSeconds = 65))

        assertEquals("1:05", board.clock?.label)
    }

    // ---- server narration is HTML ------------------------------------------------------------------

    @Test
    fun `strips the server's HTML out of narration, prompts and the priority name`() {
        val board =
            BoardUi.from(
                twoSeatState(viewerFirst = false).copy(
                    viewerHasPriority = false,
                    priorityPlayerName = "<font color='#20B2AA'>Computer</font>",
                    lastMessage = GameMessage(text = "Draw - Waiting for <font color='#20B2AA'>Computer</font>"),
                    prompt = GamePrompt.Select(message = "Select <b>an</b> ability&nbsp;to play"),
                ),
            )

        assertEquals("Draw - Waiting for Computer", board.narration)
        assertEquals("Select an ability to play", board.promptNotice)
    }

    @Test
    fun `strips the server's HTML out of the priority holder's name`() {
        // The name is only ever shown on the opponent's line, so this is asserted with no outstanding
        // question — which is also the only state in which that line is drawn.
        val board =
            BoardUi.from(
                twoSeatState(viewerFirst = false).copy(
                    viewerHasPriority = false,
                    prompt = null,
                    priorityPlayerName = "<font color='#20B2AA'>Computer</font>",
                ),
            )

        assertEquals("Computer has priority", board.priority.detail)
    }

    @Test
    fun `a line that is nothing but markup is dropped rather than drawn blank`() {
        val board =
            BoardUi.from(
                twoSeatState(viewerFirst = false).copy(lastMessage = GameMessage(text = "<font color='#fff'></font>")),
            )

        assertNull(board.narration)
    }

    // ---- prompts are shown, never answerable -------------------------------------------------------

    @Test
    fun `renders the outstanding prompt as plain text for comprehension`() {
        val board =
            BoardUi.from(
                twoSeatState(viewerFirst = false).copy(
                    prompt = GamePrompt.Target(message = "Select targets (selected 0 of 2, min 1)", isRequired = false),
                ),
            )

        assertEquals("Select targets (selected 0 of 2, min 1)", board.promptNotice)
    }

    @Test
    fun `an unrecognised prompt still renders as a notice rather than nothing`() {
        val board = BoardUi.from(twoSeatState(viewerFirst = false).copy(prompt = GamePrompt.Unrecognised(type = "GAME_WHAT")))

        assertEquals("unrecognised prompt", board.promptNotice)
    }

    // ---- errors and results ------------------------------------------------------------------------

    @Test
    fun `surfaces the server's sticky game error`() {
        val board = BoardUi.from(twoSeatState(viewerFirst = false).copy(lastError = "you may not do that"))

        assertEquals("you may not do that", board.errorNotice)
    }

    @Test
    fun `shows the server's own end-of-game line verbatim`() {
        // Upstream sends one line of prose and no structured winner, so the board repeats the sentence
        // and claims nothing beyond it.
        val board = BoardUi.from(twoSeatState(viewerFirst = false).copy(result = GameResult(message = "you has won the game")))

        assertEquals("you has won the game", board.resultNotice)
        assertTrue(board.isOver)
    }

    @Test
    fun `still says the game ended when the server sent no wording`() {
        val board = BoardUi.from(twoSeatState(viewerFirst = false).copy(result = GameResult(message = null)))

        assertEquals(BoardUi.GAME_OVER_FALLBACK, board.resultNotice)
        assertTrue(board.isOver)
    }

    // ---- spectator ---------------------------------------------------------------------------------

    @Test
    fun `a spectator has no seat of its own and no hand`() {
        val state = twoSeatState(viewerFirst = false)
        val board =
            BoardUi.from(
                state.copy(
                    viewerPlayerId = null,
                    hand = emptyList(),
                    players = state.players.map { it.copy(isViewer = false) },
                ),
            )

        assertTrue(board.isSpectator)
        assertNull(board.viewerSeat)
        assertEquals("both seats become opponents to a watcher", 2, board.opponentSeats.size)
        assertTrue(board.hand.isEmpty)
    }

    // ---- fixtures ----------------------------------------------------------------------------------

    private fun card(
        id: String,
        name: String,
    ) = GameCard(
        id = id,
        name = name,
        setCode = "M21",
        collectorNumber = "272",
        // Null for a land upstream — that is the point of the `manaCost` test above.
        manaCost = if (name == "Forest" || name == "Mountain") null else "1G",
        typeLine = if (name == "Forest") "Basic Land — Forest" else "Creature — Bear",
    )

    /**
     * A running two-seat game. [viewerFirst] flips the **order of the players list** and nothing else:
     * the server's order is not stable and is not viewer-first (observed live), so both orders must
     * produce the same board.
     */
    private fun twoSeatState(viewerFirst: Boolean): GameState {
        val opponent =
            GamePlayer(
                playerId = "p-opp",
                name = "Computer",
                life = 18,
                libraryCount = 51,
                handCount = 5,
                isHuman = false,
                hasPriority = false,
                battlefield = listOf(GamePermanent(card = card("o-1", "Mountain"))),
            )
        val viewer =
            GamePlayer(
                playerId = "p-you",
                name = "you",
                life = 20,
                libraryCount = 53,
                handCount = 7,
                graveyardCount = 2,
                exileCount = 1,
                wins = 1,
                winsNeeded = 2,
                isViewer = true,
                isActive = true,
                hasPriority = true,
                manaPool = ManaPool(green = 2, colorless = 1),
                battlefield = listOf(GamePermanent(card = card("y-1", "Forest"))),
            )
        return GameState(
            gameId = "g-1",
            turn = 3,
            phase = TurnPhase.PrecombatMain,
            step = PhaseStep.PrecombatMain,
            activePlayerId = "p-you",
            activePlayerName = "you",
            priorityPlayerName = "Computer",
            viewerPlayerId = "p-you",
            viewerHasPriority = false,
            hasSnapshot = true,
            players = if (viewerFirst) listOf(viewer, opponent) else listOf(opponent, viewer),
            hand = listOf(card("h-1", "Forest"), card("h-2", "Grizzly Bears")),
        )
    }
}
