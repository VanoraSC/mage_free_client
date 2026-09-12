package magefree.feature.game.table

import androidx.compose.ui.unit.dp
import magefree.designsystem.card.BoardBadge
import magefree.designsystem.card.BoardCardSignal
import magefree.feature.game.board.BoardAction
import magefree.feature.game.board.CombatRole
import magefree.feature.game.board.PromptControlsUi
import magefree.feature.game.board.TARGET_ACTION_LABEL
import magefree.feature.game.board.UNPICK_ACTION_LABEL
import magefree.network.game.CardIconType
import magefree.network.game.CardType
import magefree.network.game.CombatGroup
import magefree.network.game.GameCard
import magefree.network.game.GameCardIcon
import magefree.network.game.GameCounter
import magefree.network.game.GamePermanent
import magefree.network.game.GamePlayer
import magefree.network.game.GameState
import magefree.network.game.PlayableObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The snapshot rearranged into two sides and three buckets.
 *
 * The assertions worth having are the ones where a plausible implementation is wrong: a permanent
 * that is *both* a land and a creature, an attachment that would otherwise be drawn twice, and the one
 * icon whose meaning lives in its hint rather than in its type. Everything else is a field copy, and a
 * test that a field was copied is a test of nothing.
 */
class BattlefieldModelTest {
    @Test
    fun `permanents land in the bucket their current types put them in`() {
        val model = battlefieldModel(stateWith(viewer = listOf(bears(), forest(), pacifismOnItsOwn())))
        val side = model.viewer!!

        assertEquals(listOf("bears"), side.inRole(PermanentRole.Creature).map { it.id })
        assertEquals(listOf("forest"), side.inRole(PermanentRole.Land).map { it.id })
        assertEquals(listOf("curse"), side.inRole(PermanentRole.Other).map { it.id })
    }

    @Test
    fun `a land that is currently a creature goes in front with the creatures`() {
        // An animated Mutavault attacks and blocks this turn, which is the whole reason the front row
        // exists. Checking land first would file it at the back with the untapped Plains.
        val manland =
            permanent(
                id = "mutavault",
                name = "Mutavault",
                types = listOf(CardType.Land, CardType.Creature),
                isCreature = true,
            )
        val model = battlefieldModel(stateWith(viewer = listOf(manland)))

        assertEquals(listOf("mutavault"), model.viewer!!.inRole(PermanentRole.Creature).map { it.id })
        assertTrue(model.viewer!!.isEmpty(PermanentRole.Land))
    }

    @Test
    fun `an attached permanent renders on its host and nowhere else`() {
        val model = battlefieldModel(stateWith(viewer = listOf(enchantedBears(), pacifismOn("bears"))))
        val side = model.viewer!!

        // It is gone from the buckets entirely — an Aura drawn both on its host and beside it is the
        // board reporting two permanents where the game has one.
        assertEquals(listOf("bears"), side.permanents.map { it.id })
        assertEquals(
            listOf("Pacifism"),
            side.permanents
                .single()
                .state.attachments
                .map { it.name },
        )
    }

    @Test
    fun `reading an enchanted creature reads what is enchanting it`() {
        // Pacifism is the reason the creature is not attacking, and at board size the Aura is a name
        // band behind its host — so the host's own panel is the only place its text can be read. A
        // panel that listed the creature's abilities and stopped would be describing a card rather
        // than the permanent on the board.
        val model = battlefieldModel(stateWith(viewer = listOf(enchantedBears(), pacifismOn("bears"))))
        val preview = permanentPreview(model.viewer!!.permanents.single())

        assertEquals(listOf("Pacifism"), preview.attachments.map { it.name })
    }

    @Test
    fun `an attachment is findable by its own id, so its band can be pressed`() {
        val model = battlefieldModel(stateWith(viewer = listOf(enchantedBears(), pacifismOn("bears"))))

        assertEquals("Pacifism", model.attachmentById("pacifism")?.card?.name)
        assertNull("a host is not an attachment", model.attachmentById("bears"))
    }

    @Test
    fun `an aura on a creature the opponent controls is found across the board`() {
        // Upstream's `attachedControllerDiffers` exists for exactly this, so the host lookup cannot be
        // scoped to the attachment's own controller.
        val state =
            GameState(
                gameId = "g",
                viewerPlayerId = "me",
                players =
                    listOf(
                        GamePlayer(
                            playerId = "me",
                            name = "Me",
                            isViewer = true,
                            battlefield = listOf(pacifismOn("bears", differs = true)),
                        ),
                        GamePlayer(playerId = "them", name = "Them", battlefield = listOf(enchantedBears())),
                    ),
            )

        val model = battlefieldModel(state)

        assertTrue("the aura should not sit in a bucket", model.viewer!!.permanents.isEmpty())
        val host =
            model.opponents
                .single()
                .permanents
                .single()
        assertEquals(
            "Pacifism",
            host.state.attachments
                .single()
                .name,
        )
        assertTrue(
            "their creature carries your aura",
            host.state.attachments
                .single()
                .controlledByOther,
        )
    }

    @Test
    fun `an attachment id the snapshot never sent is dropped rather than drawn blank`() {
        val ghost = enchantedBears().let { it.copy(attachments = it.attachments + "never-sent") }
        val model = battlefieldModel(stateWith(viewer = listOf(ghost, pacifismOn("bears"))))

        assertEquals(
            listOf("Pacifism"),
            model.viewer!!
                .permanents
                .single()
                .state.attachments
                .map { it.name },
        )
    }

    @Test
    fun `shroud is told from hexproof by the hint, which is the only place the hint decides anything`() {
        // Upstream sends both as `CardIconType.ABILITY_HEXPROOF`. Reading the type alone would draw a
        // shrouded creature as hexproof, and a player would try to target their own trick at it.
        val shrouded = bears().withIcons(GameCardIcon(type = CardIconType.AbilityHexproof, hint = "Shroud"))
        val hexproofed = forest().withIcons(GameCardIcon(type = CardIconType.AbilityHexproof, hint = "Hexproof from all"))

        val model = battlefieldModel(stateWith(viewer = listOf(shrouded, hexproofed)))
        val badges = model.viewer!!.permanents.associate { it.id to it.state.badges }

        assertEquals(listOf(BoardBadge.Shroud), badges["bears"])
        assertEquals(listOf(BoardBadge.Hexproof), badges["forest"])
    }

    @Test
    fun `an icon this build does not know still becomes a badge`() {
        val marked = bears().withIcons(GameCardIcon(type = CardIconType.Unknown, hint = "Something new"))

        val model = battlefieldModel(stateWith(viewer = listOf(marked)))

        assertEquals(
            listOf(BoardBadge.Unknown),
            model.viewer!!
                .permanents
                .single()
                .state.badges,
        )
    }

    @Test
    fun `a playable count is not a badge on a permanent`() {
        // It counts playable copies in a pile, which is a hand and zone-browser matter. Drawing it on
        // a permanent would put a badge on the board that means nothing there.
        val marked = bears().withIcons(GameCardIcon(type = CardIconType.PlayableCount, text = "2"))

        val model = battlefieldModel(stateWith(viewer = listOf(marked)))

        assertEquals(
            emptyList<BoardBadge>(),
            model.viewer!!
                .permanents
                .single()
                .state.badges,
        )
    }

    @Test
    fun `combat and playability are the server's answers, carried through as signals`() {
        val state =
            stateWith(viewer = listOf(bears(), forest())).copy(
                combat = listOf(CombatGroup(defenderId = "them", attackerIds = listOf("bears"))),
                playable = listOf(PlayableObject(objectId = "forest")),
            )

        val model = battlefieldModel(state)
        val signals = model.viewer!!.permanents.associate { it.id to it.state.signals }

        assertEquals(setOf(BoardCardSignal.Attacking), signals["bears"])
        assertEquals(setOf(BoardCardSignal.Playable), signals["forest"])
    }

    @Test
    fun `counters travel with their card`() {
        val charged = bears().let { it.copy(card = it.card.copy(counters = listOf(GameCounter("+1/+1", 2)))) }

        val model = battlefieldModel(stateWith(viewer = listOf(charged)))
        val counter =
            model.viewer!!
                .permanents
                .single()
                .state.counters
                .single()

        assertEquals("+1/+1", counter.name)
        assertEquals(2, counter.count)
    }

    @Test
    fun `a spectator has no side of their own and every seat is an opponent`() {
        val state =
            GameState(
                gameId = "g",
                isWatching = true,
                players =
                    listOf(
                        GamePlayer(playerId = "a", name = "A", battlefield = listOf(bears())),
                        GamePlayer(playerId = "b", name = "B", battlefield = listOf(forest())),
                    ),
            )

        val model = battlefieldModel(state)

        assertEquals(null, model.viewer)
        assertEquals(listOf("a", "b"), model.opponents.map { it.playerId })
    }

    // ---- the question the board is being asked ------------------------------------------------

    @Test
    fun `a permanent the outstanding prompt can be answered with says so, in the playable colour`() {
        // The defect this exists for: Liliana's -6 asks the player to separate an opponent's
        // permanents into two piles, and the board drew those permanents exactly like permanents
        // nobody was asking about. Nothing on screen said which cards answered the question.
        //
        // The *playable* colour, not one of its own: "you can act on this" is one idea, and a player
        // who has learned the green border once should not have to learn a second colour for it
        // depending on which question happens to be outstanding.
        val state = stateWith(viewer = listOf(bears(), forest()))

        val model = battlefieldModel(state, PromptPicks(pickable = setOf("forest")))

        val side = model.viewer!!
        assertTrue(BoardCardSignal.Playable in side.permanentById("forest").state.signals)
        assertTrue(BoardCardSignal.Playable !in side.permanentById("bears").state.signals)
    }

    @Test
    fun `a permanent the player has chosen is tinted, and stays pressable`() {
        // Two channels, because the two facts are different in kind: green says the card *can*
        // answer the question, the tint says it already does. And it keeps the border, deliberately —
        // upstream removes a target sent a second time, so a chosen card is still a card to press.
        val state = stateWith(viewer = listOf(bears(), forest()))

        val model = battlefieldModel(state, PromptPicks(pickable = setOf("forest", "bears"), picked = setOf("forest")))

        val side = model.viewer!!
        assertTrue(side.permanentById("forest").state.isSelected)
        assertTrue(BoardCardSignal.Playable in side.permanentById("forest").state.signals)
        assertFalse(side.permanentById("bears").state.isSelected)
    }

    @Test
    fun `a prompt the board already draws its own way is not painted over`() {
        // A priority window's candidates are `Playable` and a mana payment's are the cost being
        // assembled. Both are established board colours; a third on top of them would lose the
        // distinction rather than add one.
        val priority =
            PromptControlsUi.Priority(
                message = "Play something",
                pickableObjectIds = setOf("forest"),
                buttons = emptyList(),
            )

        assertEquals(PromptPicks(), priority.boardPicks())
        assertEquals(PromptPicks(), (null as PromptControlsUi?).boardPicks())
    }

    @Test
    fun `a targeting prompt hands the board its candidates`() {
        val targeting =
            PromptControlsUi.Targeting(
                message = "Select permanents to put in the first pile",
                pickableObjectIds = setOf("forest", "swamp"),
                chosenObjectIds = setOf("forest"),
                candidateCards = emptyList(),
                buttons = emptyList(),
                hasPicked = true,
            )

        assertEquals(
            PromptPicks(pickable = setOf("forest", "swamp"), picked = setOf("forest")),
            targeting.boardPicks(),
        )
    }

    @Test
    fun `a chosen card is answerable even though the server stopped listing it as possible`() {
        // The bug this exists for: discarding to hand size, "Take back this choice" appeared on a
        // chosen Swamp and did nothing.
        //
        // `Target.keepValidPossibleTargets` — "keep only valid and *not selected* targets" — drops
        // chosen ids from every `possibleTargets`, so after a pick the candidate set and the chosen
        // set are **disjoint**. The label was offered for anything chosen and the action for anything
        // possible, so the two never met. Upstream accepts the id regardless: `HumanPlayer.choose`
        // checks `target.contains` before it ever consults `possibleTargets`.
        val targeting =
            PromptControlsUi.Targeting(
                message = "Select 3 cards to discard",
                pickableObjectIds = setOf("mountain"),
                chosenObjectIds = setOf("swamp"),
                candidateCards = emptyList(),
                buttons = emptyList(),
                hasPicked = true,
            )

        assertEquals(BoardAction.ChooseTarget("swamp"), targeting.actionFor("swamp"))
        assertEquals(UNPICK_ACTION_LABEL, targeting.actionLabelFor("swamp"))
        // And the board still draws it as pressable, or the label would be on a card with no border.
        assertEquals(setOf("mountain", "swamp"), targeting.boardPicks().pickable)
        assertEquals(setOf("swamp"), targeting.boardPicks().picked)
    }

    @Test
    fun `a card already chosen offers to take the choice back, not to choose it again`() {
        // Upstream toggles: `HumanPlayer.choose` removes a target sent a second time. A button still
        // reading "choose" would take the choice back while claiming to make it.
        val targeting =
            PromptControlsUi.Targeting(
                message = "Select permanents to put in the first pile",
                pickableObjectIds = setOf("forest", "swamp"),
                chosenObjectIds = setOf("forest"),
                candidateCards = emptyList(),
                buttons = emptyList(),
                hasPicked = true,
            )

        assertEquals(UNPICK_ACTION_LABEL, targeting.actionLabelFor("forest"))
        assertEquals(TARGET_ACTION_LABEL, targeting.actionLabelFor("swamp"))
        assertNull(targeting.actionLabelFor("mountain"))
        assertEquals(BoardAction.ChooseTarget("forest"), targeting.actionFor("forest"))
    }

    private fun BattlefieldSide.permanentById(id: String) =
        PermanentRole.entries.firstNotNullOf { role -> inRole(role).firstOrNull { it.id == id } }
}

private fun stateWith(viewer: List<GamePermanent>) =
    GameState(
        gameId = "g",
        viewerPlayerId = "me",
        players = listOf(GamePlayer(playerId = "me", name = "Me", isViewer = true, battlefield = viewer)),
    )

private fun permanent(
    id: String,
    name: String,
    types: List<CardType>,
    isCreature: Boolean = false,
    attachments: List<String> = emptyList(),
    attachedTo: String? = null,
    attachedControllerDiffers: Boolean = false,
) = GamePermanent(
    card = GameCard(id = id, name = name, cardTypes = types, isCreature = isCreature),
    attachments = attachments,
    attachedTo = attachedTo,
    isAttachedToPermanent = attachedTo != null,
    attachedControllerDiffers = attachedControllerDiffers,
)

private fun bears() = permanent(id = "bears", name = "Grizzly Bears", types = listOf(CardType.Creature), isCreature = true)

private fun enchantedBears() = bears().copy(attachments = listOf("pacifism"))

private fun forest() = permanent(id = "forest", name = "Forest", types = listOf(CardType.Land))

/** An Aura attached to a permanent, which is the case that leaves the buckets. */
private fun pacifismOn(
    host: String,
    differs: Boolean = false,
) = permanent(
    id = "pacifism",
    name = "Pacifism",
    types = listOf(CardType.Enchantment),
    attachedTo = host,
    attachedControllerDiffers = differs,
)

/** A Curse: attached to a *player*, so it has no host on the battlefield and keeps its bucket. */
private fun pacifismOnItsOwn() =
    GamePermanent(
        card = GameCard(id = "curse", name = "Curse of Death's Hold", cardTypes = listOf(CardType.Enchantment)),
        attachedTo = "them",
        isAttachedToPermanent = false,
    )

private fun GamePermanent.withIcons(vararg icons: GameCardIcon) = copy(card = card.copy(icons = icons.toList()))

/**
 * What a battlefield row actually draws: single permanents, and piles of identical tokens.
 *
 * The assertions worth having are the ones a plausible implementation gets wrong: that **only tokens**
 * pile, that a **tapped** token is its own pile rather than the turned half of one, that summoning
 * sickness splits a pile like any other difference, and that a pile appears where its first member did.
 */
class RowEntryTest {
    @Test
    fun `identical tokens draw as one pile`() {
        // Twelve Zombie tokens are one thing on the board twelve times over. Drawing twelve cards
        // spends the row's whole width saying so, which is what shrank every card on the table.
        val side = sideWith(List(12) { token("z$it", "Zombie Token") })

        val entries = side.entriesIn(PermanentRole.Creature)

        assertEquals(1, entries.size)
        assertEquals(12, entries.single().permanents.size)
        assertTrue(entries.single() is RowEntry.Pile)
    }

    @Test
    fun `real cards never pile, however identical`() {
        // Two Grizzly Bears are two cards a player owns and may want to tell apart. Upstream marks the
        // difference itself, so nothing here is guessed.
        val side = sideWith(listOf(creature("b1", "Grizzly Bears"), creature("b2", "Grizzly Bears")))

        val entries = side.entriesIn(PermanentRole.Creature)

        assertEquals(2, entries.size)
        assertTrue(entries.all { it is RowEntry.Single })
    }

    @Test
    fun `a tapped token is its own pile, not the turned half of one`() {
        // Where tokens differ from lands, deliberately. A land's two halves are one permanent in two
        // states and the count is what matters. A creature's tap state is *what it is doing*.
        val side =
            sideWith(
                listOf(
                    token("z1", "Zombie Token"),
                    token("z2", "Zombie Token", tapped = true),
                    token("z3", "Zombie Token"),
                ),
            )

        val entries = side.entriesIn(PermanentRole.Creature)

        assertEquals(2, entries.size)
        assertEquals(listOf(2, 1), entries.map { it.permanents.size })
        // Each pile is uniformly one way up, so the renderer gets one populated half.
        entries.filterIsInstance<RowEntry.Pile>().forEach { pile ->
            val stack = pile.asStack()
            assertTrue("a pile is all upright or all turned", stack.untapped.isEmpty() || stack.tapped.isEmpty())
        }
    }

    @Test
    fun `summoning sickness splits a pile, because it is a different creature to attack with`() {
        val side =
            sideWith(
                listOf(
                    token("z1", "Zombie Token"),
                    token("z2", "Zombie Token", summoningSick = true),
                ),
            )

        assertEquals(2, side.entriesIn(PermanentRole.Creature).size)
    }

    @Test
    fun `a token carrying an attachment stands alone`() {
        // The Aura is on *that* Zombie, so "read one and you have read them all" stops being true —
        // the same absolute rule the land stacks follow.
        val enchanted = token("z1", "Zombie Token").let { it.copy(attachments = listOf("pacifism")) }
        val side = sideWith(listOf(enchanted, token("z2", "Zombie Token"), pacifismOn("z1")))

        val entries = side.entriesIn(PermanentRole.Creature)

        assertEquals(2, entries.size)
        assertTrue(entries.all { it is RowEntry.Single })
    }

    @Test
    fun `a pile is drawn where its first member was, not at the end of the row`() {
        // A pile that jumped to the end when it gained a token would be a pile that appears to move.
        val side =
            sideWith(
                listOf(
                    token("z1", "Zombie Token"),
                    creature("b1", "Grizzly Bears"),
                    token("z2", "Zombie Token"),
                ),
            )

        val entries = side.entriesIn(PermanentRole.Creature)

        assertEquals(listOf(listOf("z1", "z2"), listOf("b1")), entries.map { e -> e.permanents.map { it.id } })
    }

    @Test
    fun `a pile costs more of the row than a card, because it fans`() {
        val side = sideWith(listOf(token("z1", "Zombie Token"), token("z2", "Zombie Token"), creature("b1", "Bears")))
        val entries = side.entriesIn(PermanentRole.Creature)

        val pile = entries.filterIsInstance<RowEntry.Pile>().single()
        val single = entries.filterIsInstance<RowEntry.Single>().single()
        assertTrue(pile.widthInCards() > single.widthInCards())
    }

    private fun sideWith(permanents: List<GamePermanent>) =
        battlefieldModel(
            GameState(
                gameId = "g",
                viewerPlayerId = "me",
                players = listOf(GamePlayer(playerId = "me", name = "Me", isViewer = true, battlefield = permanents)),
            ),
        ).viewer!!

    private fun token(
        id: String,
        name: String,
        tapped: Boolean = false,
        summoningSick: Boolean = false,
    ) = GamePermanent(
        card = GameCard(id = id, name = name, cardTypes = listOf(CardType.Creature), isCreature = true, isToken = true),
        isTapped = tapped,
        hasSummoningSickness = summoningSick,
    )

    private fun creature(
        id: String,
        name: String,
    ) = GamePermanent(card = GameCard(id = id, name = name, cardTypes = listOf(CardType.Creature), isCreature = true))

    private fun pacifismOn(hostId: String) =
        GamePermanent(
            card = GameCard(id = "pacifism", name = "Pacifism", cardTypes = listOf(CardType.Enchantment)),
            attachedTo = hostId,
            isAttachedToPermanent = true,
        )
}

/**
 * Combat as a set of relationships, and the taps that make them.
 *
 * Who is attacking is a border; **who they are attacking, and which blocker is on which attacker, is a
 * pairing** — and a pairing has no honest representation on a card. These assert the two halves that
 * matter: that a declaration is answered by a tap rather than by raising a card, and that the pairings
 * the board draws lines for are the server's own.
 */
class CombatDeclarationTest {
    @Test
    fun `a tap on a creature declares it, with no card raised in between`() {
        // Declaring is the one prompt where the player is not choosing between things they have to
        // read first — they are looking at their own board, at speed. Raising each creature into a
        // full-screen card turns a five-creature attack into ten presses.
        val controls = declaration(pickable = setOf("bears"))

        assertTrue(controls.answersOnPress)
        assertEquals(BoardAction.ChooseTarget("bears"), controls.actionFor("bears"))
    }

    @Test
    fun `a tap on a declared creature takes it back, which is the same message`() {
        // Upstream's own design: `selectAttackers` answers an id matching `filterAttack` with
        // `removeAttackerIfPossible`, and `selectBlockers` answers one matching `filterBlock` with
        // `removeBlocker`. One verb, and the server decides which way it went.
        val controls = declaration(pickable = setOf("bears"), withdrawable = setOf("wolf"))

        assertEquals(BoardAction.ChooseTarget("wolf"), controls.actionFor("wolf"))
        assertEquals(setOf("wolf"), controls.chosenObjectIds)
    }

    @Test
    fun `a creature the server did not offer answers nothing`() {
        val controls = declaration(pickable = setOf("bears"))

        assertNull(controls.actionFor("someone-elses-creature"))
    }

    private fun declaration(
        pickable: Set<String>,
        withdrawable: Set<String> = emptySet(),
    ) = PromptControlsUi.Declaration(
        message = "Select attackers",
        pickableObjectIds = pickable,
        buttons = emptyList(),
        role = CombatRole.Attacking,
        withdrawableObjectIds = withdrawable,
    )
}

/**
 * How much of a side a row costs, in the units the layout budgets in.
 *
 * **A pile is taller than a card**, and a layout that forgot it put the non-creature permanents below
 * the bottom of the board — behind the phase bar — the moment a token pile appeared, and moved the
 * whole side when a token tapped and a second pile split off.
 */
class RowHeightTest {
    @Test
    fun `a pile of upright tokens costs exactly what one card costs`() {
        // **It used to cost half again as much**, because a pile was a fan of three staggering down
        // and to the right with room kept below for a turned half. A pile is one card and a tally now,
        // so twelve Zombies cost the row what one Zombie costs — which is the whole reason the fan
        // went: the extra height came out of every card on the board, both sides, every row.
        val cards = listOf(RowEntry.Single(permanentFor("b1")))
        val pile = listOf(RowEntry.Pile(listOf(permanentFor("z1"), permanentFor("z2"))))

        assertEquals(cards.heightInCards(), pile.heightInCards())
    }

    @Test
    fun `a pile of tapped tokens costs the room a leaning card needs, and no more`() {
        // A turned card is a square on its corner — √2 across — so it genuinely is taller than an
        // upright one. What it must not also carry is a fan, or a drop onto an upright card that this
        // pile can never contain.
        val upright = listOf(RowEntry.Pile(listOf(permanentFor("z1"), permanentFor("z2"))))
        val turned = listOf(RowEntry.Pile(listOf(permanentFor("z1", tapped = true), permanentFor("z2", tapped = true))))

        assertTrue(
            "a leaning pile measured ${turned.heightInCards()} against an upright ${upright.heightInCards()}",
            turned.heightInCards() > upright.heightInCards(),
        )
        assertTrue(
            "a leaning card is √2 tall, not two cards: ${turned.heightInCards()}",
            turned.heightInCards() < 2f * upright.heightInCards(),
        )
    }

    @Test
    fun `a row costs its tallest entry, not the sum of its entries`() {
        // A row is a row: five cards side by side are one card tall.
        val one = listOf(RowEntry.Single(permanentFor("b1")))
        val five = (1..5).map { RowEntry.Single(permanentFor("b$it")) }

        assertEquals(one.heightInCards(), five.heightInCards())
    }

    @Test
    fun `an empty row costs nothing, which is the board's own rule`() {
        assertEquals(0f, emptyList<RowEntry>().heightInCards())
    }

    private fun permanentFor(
        id: String,
        tapped: Boolean = false,
    ) = TablePermanent(
        id = id,
        role = PermanentRole.Creature,
        state =
            magefree.designsystem.card.BoardCardState(
                card = magefree.designsystem.card.CardDisplay(name = id),
                tapped = tapped,
            ),
    )
}

/**
 * How much height one side is given out of the space above the phase bar and the hand.
 *
 * **The gap between the two sides is height too**, and it was never taken out of the budget — so each
 * side was sized for half a gap more than it had. Invisible on a board with slack; with a hand on
 * screen there is none, and the surplus came out as the non-creature row overlapping the creatures and
 * running under the phase bar.
 */
class SideHeightTest {
    @Test
    fun `the gap between the two sides comes out of the budget`() {
        val whole = 400.dp

        val each = sideHeightFor(whole, sideCount = 2)

        assertTrue(
            "two sides plus the gap between them must fit in what there is, got $each each",
            each * 2 + CentreLineGapForTest <= whole,
        )
    }

    @Test
    fun `a single side pays for no gap, because there is nothing to be apart from`() {
        // A spectator's board, or one seat left. There is no centre line to leave room for.
        assertEquals(400.dp, sideHeightFor(400.dp, sideCount = 1))
    }

    @Test
    fun `a board too small for the gap asks for nothing rather than a negative height`() {
        assertEquals(0.dp, sideHeightFor(0.dp, sideCount = 2))
    }
}
