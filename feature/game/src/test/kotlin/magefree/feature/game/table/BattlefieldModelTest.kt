package magefree.feature.game.table

import magefree.designsystem.card.BoardBadge
import magefree.designsystem.card.BoardCardSignal
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
