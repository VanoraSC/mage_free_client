package magefree.network.game

import magefree.protocol.AskPrompt
import magefree.protocol.CardTypeCode
import magefree.protocol.ChooseAbilityPrompt
import magefree.protocol.ChooseChoicePrompt
import magefree.protocol.ChoosePilePrompt
import magefree.protocol.CommandObjectKind
import magefree.protocol.GameAbilityChoice
import magefree.protocol.GameCardView
import magefree.protocol.GameChoiceOption
import magefree.protocol.GameCombatGroupView
import magefree.protocol.GameCommandObjectView
import magefree.protocol.GameCounterView
import magefree.protocol.GameError
import magefree.protocol.GameInformed
import magefree.protocol.GameManaPoolView
import magefree.protocol.GameMultiAmountEntry
import magefree.protocol.GameOver
import magefree.protocol.GamePermanentView
import magefree.protocol.GamePlayableObject
import magefree.protocol.GamePlayerView
import magefree.protocol.GamePromptOptions
import magefree.protocol.GamePrompted
import magefree.protocol.GameStarted
import magefree.protocol.GameStateUpdated
import magefree.protocol.GameStateView
import magefree.protocol.GameZoneView
import magefree.protocol.GetAmountPrompt
import magefree.protocol.GetMultiAmountPrompt
import magefree.protocol.PhaseStepCode
import magefree.protocol.PlayManaPrompt
import magefree.protocol.PlayXManaPrompt
import magefree.protocol.SelectPrompt
import magefree.protocol.TableUpdated
import magefree.protocol.TargetPrompt
import magefree.protocol.TurnPhaseCode
import magefree.protocol.UnknownGamePrompt
import magefree.protocol.WatchingGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hermetic coverage of the **pure** [GameEventFold] and the [GameViewMapper] it delegates shape
 * conversion to, over scripted sequences of real `:protocol` events — no client, no socket, no coroutine.
 *
 * The sequence this names (game start → turn/phase changes → a prompt appears and clears → game
 * over) is `aScriptedGameFoldsFromStartThroughPromptsToGameOver`; the rest pin the individual rules the
 * fold has to get right, in particular **which** pushes clear an outstanding prompt and which must not.
 */
class GameEventFoldTest {
    private val seed = GameState(gameId = GAME)

    // --- the scripted game ---------------------------------------------------------------------------

    @Test
    fun aScriptedGameFoldsFromStartThroughPromptsToGameOver() {
        // 1. GAME_INIT. The server fires it before the hand is dealt, so an empty hand here is correct
        //    and must not be special-cased away (:bridge's GameRelayIT records the same ordering).
        val started =
            GameEventFold.fold(seed, GameStarted(gameId = GAME, state = view(turn = 1, hand = emptyList())))
        assertNotNull("a GAME_INIT for this game must fold", started)
        assertTrue("the first snapshot is what makes state real", started!!.hasSnapshot)
        assertEquals(1, started.turn)
        assertTrue("the opening snapshot legitimately carries no hand yet", started.hand.isEmpty())
        assertNull("nothing has been asked yet", started.prompt)

        // 2. The opening hand arrives on a later snapshot.
        val dealt =
            GameEventFold.fold(started, GameStateUpdated(gameId = GAME, state = view(turn = 1, hand = hand(7))))!!
        assertEquals(7, dealt.hand.size)

        // 3. A prompt appears — and it carries the snapshot at the moment of asking.
        val asked =
            GameEventFold.fold(
                dealt,
                GamePrompted(
                    gameId = GAME,
                    state = view(turn = 1, hand = hand(7), viewerHasPriority = true, playable = listOf(playable("c-0"))),
                    prompt = SelectPrompt(message = "Play a land"),
                ),
            )!!
        assertEquals(GamePrompt.Select(message = "Play a land"), asked.prompt)
        assertTrue("a select is only sent to the player holding priority", asked.viewerHasPriority)
        assertTrue("the server's own canPlayObjects rides the same snapshot", asked.isPlayable("c-0"))

        // 4. The turn/phase advances and the prompt clears — the game moved on, so the question is
        //    finished with and a UI must not be able to answer it any more.
        val advanced =
            GameEventFold.fold(
                asked,
                GameStateUpdated(
                    gameId = GAME,
                    state = view(turn = 2, phase = TurnPhaseCode.COMBAT, step = PhaseStepCode.DECLARE_ATTACKERS),
                ),
            )!!
        assertEquals(2, advanced.turn)
        assertEquals(TurnPhase.Combat, advanced.phase)
        assertEquals(PhaseStep.DeclareAttackers, advanced.step)
        assertNull("a fresh snapshot means the prompt was answered", advanced.prompt)
        assertTrue("the snapshot replaced the old one wholesale", advanced.playable.isEmpty())

        // 5. Game over: the terminal result lands and any prompt is gone.
        val over =
            GameEventFold.fold(
                advanced,
                GameOver(gameId = GAME, message = "pete has won the game", state = view(turn = 2)),
            )!!
        assertEquals(GameResult("pete has won the game"), over.result)
        assertTrue(over.isOver)
        assertNull(over.prompt)
    }

    // --- filtering -----------------------------------------------------------------------------------

    @Test
    fun anEventForAnotherGameIsNotFolded() {
        assertNull(GameEventFold.fold(seed, GameStarted(gameId = "other", state = view())))
        assertNull(GameEventFold.fold(seed, GameStateUpdated(gameId = "other", state = view())))
        assertNull(GameEventFold.fold(seed, GamePrompted(gameId = "other", state = view(), prompt = AskPrompt("?"))))
        assertNull(GameEventFold.fold(seed, GameOver(gameId = "other")))
        assertNull(GameEventFold.fold(seed, GameError(gameId = "other", message = "boom")))
        assertNull(GameEventFold.fold(seed, GameInformed(gameId = "other", message = "hi")))
        assertNull(GameEventFold.fold(seed, WatchingGame(gameId = "other")))
    }

    @Test
    fun aNonGameFrameOnTheSharedPushChannelIsIgnored() {
        // The push side-channel carries table/lobby/chat frames too; the fold must decline them rather
        // than emit an unchanged state that would look like a game event.
        assertNull(GameEventFold.fold(seed, TableUpdated(tableId = "t-1", isOwner = true)))
    }

    // --- which pushes clear an outstanding prompt ----------------------------------------------------

    @Test
    fun narrationDoesNotClearAnOutstandingPromptButDoesReplaceTheSnapshot() {
        // Upstream's informOthers deliberately excludes the player it is waiting on, and a personal
        // inform can legitimately arrive while a prompt is open. Treating either as an answer would drop
        // a live prompt, so narration only moves the message (and the snapshot when it carries one).
        val asked = prompted(AskPrompt("Mulligan?"))
        val informed =
            GameEventFold.fold(
                asked,
                GameInformed(gameId = GAME, message = "Waiting for Computer", state = view(turn = 3), personal = true),
            )!!

        assertEquals(GamePrompt.Ask("Mulligan?"), informed.prompt)
        assertEquals(GameMessage(text = "Waiting for Computer", isPersonal = true), informed.lastMessage)
        assertEquals("the snapshot that rode with it still replaces state", 3, informed.turn)
    }

    @Test
    fun narrationWithoutASnapshotLeavesTheStateAlone() {
        val asked = prompted(AskPrompt("Mulligan?"))
        val informed = GameEventFold.fold(asked, GameInformed(gameId = GAME, message = "Turn 1"))!!

        assertEquals("the snapshot is optional here", asked.turn, informed.turn)
        assertEquals(GameMessage(text = "Turn 1", isPersonal = false), informed.lastMessage)
        assertEquals(asked.prompt, informed.prompt)
    }

    @Test
    fun aGameErrorRecordsItselfWithoutTouchingTheSnapshotOrThePrompt() {
        // GAME_ERROR carries no GameView at all upstream, so nothing else may move.
        val asked = prompted(SelectPrompt("Play a land"))
        val errored = GameEventFold.fold(asked, GameError(gameId = GAME, message = "server exploded"))!!

        assertEquals("server exploded", errored.lastError)
        assertEquals(asked.prompt, errored.prompt)
        assertEquals(asked.turn, errored.turn)
    }

    @Test
    fun aNewPromptReplacesTheOutstandingOneRatherThanQueueing() {
        // One prompt at a time: upstream blocks the game thread on the answer, so a second prompt means
        // the first is finished with. A queue would let a UI answer a stale question.
        val first = prompted(AskPrompt("Mulligan?"))
        val second =
            GameEventFold.fold(first, GamePrompted(gameId = GAME, state = view(), prompt = SelectPrompt("Play a land")))!!

        assertEquals(GamePrompt.Select("Play a land"), second.prompt)
    }

    @Test
    fun gameOverWithoutAFinalSnapshotStillEndsTheGame() {
        val asked = prompted(SelectPrompt("Play a land"))
        val over = GameEventFold.fold(asked, GameOver(gameId = GAME))!!

        assertTrue(over.isOver)
        assertNull("the server sent no end-of-game line", over.result?.message)
        assertNull(over.prompt)
        assertEquals("no snapshot came with it, so state is unchanged", asked.turn, over.turn)
    }

    @Test
    fun watchingGameOnlyMarksTheSessionAsSpectating() {
        val watching = GameEventFold.fold(seed, WatchingGame(gameId = GAME, tableId = "t-1"))!!

        assertTrue(watching.isWatching)
        assertFalse("WATCHGAME carries no state, so nothing has been seen yet", watching.hasSnapshot)
    }

    // --- the snapshot projection ---------------------------------------------------------------------

    @Test
    fun aSnapshotIsMappedFieldForFieldIntoAppSchema() {
        val folded =
            GameEventFold.fold(
                seed,
                GameStarted(
                    gameId = GAME,
                    state =
                        GameStateView(
                            turn = 4,
                            phase = TurnPhaseCode.POSTCOMBAT_MAIN,
                            step = PhaseStepCode.POSTCOMBAT_MAIN,
                            activePlayerId = "p-1",
                            activePlayerName = "pete",
                            priorityPlayerName = "pete",
                            viewerPlayerId = "p-1",
                            viewerHasPriority = true,
                            players =
                                listOf(
                                    GamePlayerView(
                                        playerId = "p-1",
                                        name = "pete",
                                        life = 18,
                                        libraryCount = 51,
                                        handCount = 6,
                                        graveyardCount = 2,
                                        exileCount = 1,
                                        wins = 1,
                                        winsNeeded = 2,
                                        viewer = true,
                                        active = true,
                                        hasPriority = true,
                                        human = true,
                                        hasLeft = false,
                                        manaPool = GameManaPoolView(green = 2, colorless = 1),
                                        battlefield =
                                            listOf(
                                                GamePermanentView(
                                                    card = card("perm-1", "Forest"),
                                                    tapped = true,
                                                    summoningSickness = true,
                                                    damage = 3,
                                                    attachedTo = "perm-2",
                                                    controlledByViewer = true,
                                                ),
                                            ),
                                    ),
                                    GamePlayerView(playerId = "p-2", name = "Computer", life = 20, human = false, hasLeft = true),
                                ),
                            hand = listOf(card("c-1", "Forest")),
                            stack = listOf(card("s-1", "Shock")),
                            exile = listOf(GameZoneView(name = "Exile", cards = listOf(card("e-1", "Bolt")), zoneId = "z-1")),
                            revealed = listOf(GameZoneView(name = "Revealed", cards = listOf(card("r-1", "Island")))),
                            combat =
                                listOf(
                                    GameCombatGroupView(
                                        defenderId = "p-2",
                                        defenderName = "Computer",
                                        blocked = true,
                                        attackerIds = listOf("perm-1"),
                                        blockerIds = listOf("perm-9"),
                                    ),
                                ),
                            playable = listOf(GamePlayableObject(objectId = "c-1", abilityIds = listOf("a-1", "a-2"))),
                            specialActionsAvailable = true,
                            priorityTimeSeconds = 90,
                            bufferTimeSeconds = 5,
                        ),
                ),
            )!!

        assertEquals(4, folded.turn)
        assertEquals(TurnPhase.PostcombatMain, folded.phase)
        assertEquals(PhaseStep.PostcombatMain, folded.step)
        assertEquals("p-1", folded.activePlayerId)
        assertEquals("pete", folded.activePlayerName)
        assertEquals("pete", folded.priorityPlayerName)
        assertTrue("is it my turn: the two server ids match", folded.isViewerTurn)
        assertTrue("do I have priority: the viewer's own PlayerView flag", folded.viewerHasPriority)
        assertFalse("a seated viewer is not a spectator", folded.isSpectator)

        val me = folded.viewer!!
        assertEquals("p-1", me.playerId)
        assertEquals(18, me.life)
        assertEquals(listOf(51, 6, 2, 1), listOf(me.libraryCount, me.handCount, me.graveyardCount, me.exileCount))
        assertEquals(1, me.wins)
        assertEquals(2, me.winsNeeded)
        assertTrue(me.isActive)
        assertTrue(me.hasPriority)
        assertEquals(3, me.manaPool.total)
        assertEquals(2, me.manaPool.green)

        val permanent = me.battlefield.single()
        assertEquals("Forest", permanent.card.name)
        assertTrue(permanent.isTapped)
        assertTrue(permanent.hasSummoningSickness)
        assertEquals(3, permanent.damage)
        assertEquals("perm-2", permanent.attachedTo)
        assertTrue(permanent.isControlledByViewer)

        val opponent = folded.players.last()
        assertFalse("the AI seat maps as non-human", opponent.isHuman)
        assertTrue(opponent.hasLeft)

        assertEquals(listOf("Forest"), folded.hand.map { it.name })
        assertEquals(listOf("Shock"), folded.stack.map { it.name })
        assertEquals(
            listOf("Bolt"),
            folded.exile
                .single()
                .cards
                .map { it.name },
        )
        assertEquals("z-1", folded.exile.single().zoneId)
        assertEquals(
            listOf("Island"),
            folded.revealed
                .single()
                .cards
                .map { it.name },
        )

        val group = folded.combat.single()
        assertEquals("Computer", group.defenderName)
        assertTrue(group.isBlocked)
        assertEquals(listOf("perm-1"), group.attackerIds)
        assertEquals(listOf("perm-9"), group.blockerIds)

        assertTrue("what can I play: the server's own list, and only that", folded.isPlayable("c-1"))
        assertEquals(listOf("a-1", "a-2"), folded.playableAbilitiesOf("c-1"))
        assertFalse("nothing else is playable, however plausible", folded.isPlayable("s-1"))
        assertEquals(emptyList<String>(), folded.playableAbilitiesOf("s-1"))
        assertTrue(folded.specialActionsAvailable)
        assertEquals(90, folded.priorityTimeSeconds)
        assertEquals(5, folded.bufferTimeSeconds)
    }

    @Test
    fun aCardCarriesThePrintingTheCardCatalogResolvesArtBy() {
        val folded = GameEventFold.fold(seed, GameStarted(gameId = GAME, state = view(hand = hand(1))))!!
        val card = folded.hand.single()

        assertEquals("M21", card.setCode)
        assertEquals("272", card.collectorNumber)
        assertEquals("{G}", card.manaCost)
        assertEquals("Basic Land — Forest", card.typeLine)
        assertEquals(listOf("({T}: Add {G}.)"), card.rules)
        assertFalse(card.isFaceDown)
    }

    @Test
    fun transformedAndAlternateNameSurviveFoldingTheRealSignalForWhichFaceIsCurrentlyUp() {
        // - Kytheon, Hero of Akros transformed into Gideon, Battle-Forged,
        // but the board kept showing Kytheon's art. `transformed` (upstream's own CardView.isTransformed(),
        // correctly computed for any permanent) is the real signal for which face is up; `alternateName`
        // is a separate catalog fact (has another face, what it's called) carried through unchanged.
        val transformed =
            GamePermanentView(
                card =
                    GameCardView(
                        id = "perm-1",
                        name = "Gideon, Battle-Forged",
                        setCode = "ORI",
                        collectorNumber = "23",
                        alternateName = "Kytheon, Hero of Akros",
                        transformed = true,
                    ),
                controlledByViewer = true,
            )
        val folded =
            GameEventFold.fold(
                seed,
                GameStarted(
                    gameId = GAME,
                    state =
                        GameStateView(
                            turn = 1,
                            viewerPlayerId = "p-1",
                            players =
                                listOf(
                                    GamePlayerView(playerId = "p-1", name = "pete", viewer = true, battlefield = listOf(transformed)),
                                ),
                        ),
                ),
            )!!

        val card =
            folded.viewer!!
                .battlefield
                .single()
                .card
        assertTrue(card.transformed)
        assertEquals("Kytheon, Hero of Akros", card.alternateName)
    }

    @Test
    fun aStackEntrysTargetsSurviveFoldingAndResolveAgainstTheSameSnapshot() {
        // `targets` is upstream's own de-duplicated, stably ordered id list for what a
        // spell or ability on the stack is pointing at. The fold carries it unchanged -- the ids name
        // objects in this same snapshot (here: a permanent on the battlefield and the spell below it
        // on the stack), and joining them is the renderer's job, not the fold's.
        val bear =
            GamePermanentView(
                card = GameCardView(id = "perm-1", name = "Grizzly Bears", creature = true),
                controlledByViewer = false,
            )
        val bolt = GameCardView(id = "s-1", name = "Lightning Bolt", targets = listOf("perm-1"))
        val counterspell = GameCardView(id = "s-2", name = "Counterspell", targets = listOf("s-1"))
        val folded =
            GameEventFold.fold(
                seed,
                GameStarted(
                    gameId = GAME,
                    state =
                        GameStateView(
                            turn = 1,
                            viewerPlayerId = "p-1",
                            players =
                                listOf(
                                    GamePlayerView(playerId = "p-1", name = "pete", viewer = true),
                                    GamePlayerView(playerId = "p-2", name = "Computer", human = false, battlefield = listOf(bear)),
                                ),
                            stack = listOf(bolt, counterspell),
                        ),
                ),
            )!!

        assertEquals(listOf("perm-1"), folded.stack.single { it.name == "Lightning Bolt" }.targets)
        assertEquals(
            "a target that is itself a stack entry is a plain id like any other",
            listOf("s-1"),
            folded.stack.single { it.name == "Counterspell" }.targets,
        )
        // Both ids resolve inside the very snapshot that carried them.
        assertTrue(folded.stack.any { it.id == "s-1" })
        assertTrue(folded.players.flatMap { it.battlefield }.any { it.card.id == "perm-1" })
    }

    @Test
    fun anAuraAndItsHostBothSurviveFoldingWithTheRelationshipIntact() {
        // the relationship is only renderable if BOTH directions arrive -- the host's
        // `attachments` and the aura's `attachedTo`. This aura is ours and the creature is theirs,
        // which is the case `attachedControllerDiffers` exists for, and it means the round trip
        // crosses players' battlefields rather than staying inside one.
        val aura =
            GamePermanentView(
                card = GameCardView(id = "aura-1", name = "Rancor"),
                attachedTo = "bear-1",
                attachedToPermanent = true,
                attachedControllerDiffers = true,
                controlledByViewer = true,
            )
        val bear =
            GamePermanentView(
                card = GameCardView(id = "bear-1", name = "Grizzly Bears", creature = true),
                attachments = listOf("aura-1"),
                controlledByViewer = false,
            )
        val folded =
            GameEventFold.fold(
                seed,
                GameStarted(
                    gameId = GAME,
                    state =
                        GameStateView(
                            turn = 4,
                            viewerPlayerId = "p-1",
                            players =
                                listOf(
                                    GamePlayerView(playerId = "p-1", name = "pete", viewer = true, battlefield = listOf(aura)),
                                    GamePlayerView(playerId = "p-2", name = "Computer", human = false, battlefield = listOf(bear)),
                                ),
                        ),
                ),
            )!!

        val foldedAura = folded.viewer!!.battlefield.single()
        val foldedBear =
            folded.players
                .single { !it.isViewer }
                .battlefield
                .single()

        assertEquals("bear-1", foldedAura.attachedTo)
        assertTrue(foldedAura.isAttachedToPermanent)
        assertTrue("your aura on their creature must stay flagged", foldedAura.attachedControllerDiffers)
        assertEquals(listOf("aura-1"), foldedBear.attachments)
    }

    @Test
    fun playerCountersTheCrownAndDesignationsSurviveFolding() {
        // Poison at ten is a loss, so this is win-condition state the fold must not drop. Counters are
        // read by name: the order upstream sends is its own hash-map order and means nothing.
        val folded =
            GameEventFold.fold(
                seed,
                GameStarted(
                    gameId = GAME,
                    state =
                        GameStateView(
                            turn = 5,
                            viewerPlayerId = "p-1",
                            players =
                                listOf(
                                    GamePlayerView(
                                        playerId = "p-1",
                                        name = "pete",
                                        viewer = true,
                                        counters = listOf(GameCounterView("poison", 9), GameCounterView("energy", 4)),
                                        monarch = true,
                                        initiative = true,
                                        designationNames = listOf("City's Blessing"),
                                    ),
                                    GamePlayerView(playerId = "p-2", name = "Computer", human = false),
                                ),
                        ),
                ),
            )!!

        val viewer = folded.viewer!!
        assertEquals(mapOf("poison" to 9, "energy" to 4), viewer.counters.associate { it.name to it.count })
        assertTrue(viewer.isMonarch)
        assertTrue(viewer.hasInitiative)
        assertEquals(listOf("City's Blessing"), viewer.designationNames)

        val opponent = folded.players.single { !it.isViewer }
        assertTrue(opponent.counters.isEmpty())
        assertFalse(opponent.isMonarch)
        assertFalse(opponent.hasInitiative)
        assertTrue(opponent.designationNames.isEmpty())
    }

    @Test
    fun aCommandZoneSurvivesFoldingWithItsKinds() {
        val folded =
            GameEventFold.fold(
                seed,
                GameStarted(
                    gameId = GAME,
                    state =
                        GameStateView(
                            turn = 6,
                            viewerPlayerId = "p-1",
                            players =
                                listOf(
                                    GamePlayerView(
                                        playerId = "p-1",
                                        name = "pete",
                                        viewer = true,
                                        commandList =
                                            listOf(
                                                GameCommandObjectView(
                                                    id = "cmd-1",
                                                    name = "Atraxa, Praetors' Voice",
                                                    kind = CommandObjectKind.COMMANDER,
                                                    setCode = "C16",
                                                    collectorNumber = "28",
                                                ),
                                                GameCommandObjectView(
                                                    id = "cmd-2",
                                                    name = "Emblem Elspeth",
                                                    kind = CommandObjectKind.EMBLEM,
                                                    rules = listOf("Creatures you control get +1/+1."),
                                                ),
                                            ),
                                    ),
                                    GamePlayerView(playerId = "p-2", name = "Computer", human = false),
                                ),
                        ),
                ),
            )!!

        val command = folded.viewer!!.commandList
        assertEquals(
            listOf(magefree.network.game.CommandObjectKind.Commander, magefree.network.game.CommandObjectKind.Emblem),
            command.map { it.kind },
        )
        assertEquals("28", command[0].collectorNumber)
        assertEquals(listOf("Creatures you control get +1/+1."), command[1].rules)
        assertNull("a dungeon or an emblem not printed on a card has no number", command[1].collectorNumber)
        assertTrue(
            folded.players
                .single { !it.isViewer }
                .commandList
                .isEmpty(),
        )
    }

    @Test
    fun aCommandObjectKindThisBuildDoesNotKnowFoldsToUnknown() {
        val folded =
            GameEventFold.fold(
                seed,
                GameStarted(
                    gameId = GAME,
                    state =
                        GameStateView(
                            turn = 1,
                            viewerPlayerId = "p-1",
                            players =
                                listOf(
                                    GamePlayerView(
                                        playerId = "p-1",
                                        name = "pete",
                                        viewer = true,
                                        commandList = listOf(GameCommandObjectView(id = "cmd-1", name = "Something New")),
                                    ),
                                ),
                        ),
                ),
            )!!

        assertEquals(
            magefree.network.game.CommandObjectKind.Unknown,
            folded.viewer!!
                .commandList
                .single()
                .kind,
        )
        assertEquals(
            "Something New",
            folded.viewer!!
                .commandList
                .single()
                .name,
        )
    }

    @Test
    fun aPlayersGraveyardAndExileSurviveFoldingWithTheirCounts() {
        val folded =
            GameEventFold.fold(
                seed,
                GameStarted(
                    gameId = GAME,
                    state =
                        GameStateView(
                            turn = 9,
                            viewerPlayerId = "p-1",
                            players =
                                listOf(
                                    GamePlayerView(
                                        playerId = "p-1",
                                        name = "pete",
                                        viewer = true,
                                        graveyardCount = 2,
                                        exileCount = 1,
                                        graveyard =
                                            listOf(
                                                GameCardView(id = "g-1", name = "Lightning Bolt"),
                                                GameCardView(id = "g-2", name = "Forest"),
                                            ),
                                        exile = listOf(GameCardView(id = "x-1", name = "Grizzly Bears")),
                                    ),
                                    GamePlayerView(playerId = "p-2", name = "Computer", human = false),
                                ),
                        ),
                ),
            )!!

        val viewer = folded.viewer!!
        assertEquals(listOf("Lightning Bolt", "Forest"), viewer.graveyard.map { it.name })
        assertEquals(listOf("Grizzly Bears"), viewer.exile.map { it.name })
        assertEquals("the count and the list are the same data measured", 2, viewer.graveyardCount)
        assertEquals(viewer.graveyard.size, viewer.graveyardCount)
        assertEquals(viewer.exile.size, viewer.exileCount)

        val opponent = folded.players.single { !it.isViewer }
        assertTrue(opponent.graveyard.isEmpty())
        assertTrue(opponent.exile.isEmpty())
    }

    @Test
    fun tokenIdentityAndObjectTypeSurviveFolding() {
        val token =
            GamePermanentView(
                card =
                    GameCardView(
                        id = "perm-1",
                        name = "Soldier",
                        creature = true,
                        token = true,
                        objectType = magefree.protocol.MageObjectTypeCode.TOKEN,
                    ),
                controlledByViewer = true,
            )
        val land =
            GamePermanentView(
                card = GameCardView(id = "perm-2", name = "Plains", objectType = magefree.protocol.MageObjectTypeCode.PERMANENT),
                controlledByViewer = true,
            )
        val folded =
            GameEventFold.fold(
                seed,
                GameStarted(
                    gameId = GAME,
                    state =
                        GameStateView(
                            turn = 3,
                            viewerPlayerId = "p-1",
                            players =
                                listOf(
                                    GamePlayerView(
                                        playerId = "p-1",
                                        name = "pete",
                                        viewer = true,
                                        battlefield = listOf(token, land),
                                    ),
                                ),
                        ),
                ),
            )!!

        val board = folded.viewer!!.battlefield
        assertTrue("a token permanent must stay flagged as one", board[0].card.isToken)
        assertEquals(MageObjectType.Token, board[0].card.objectType)
        assertFalse(board[1].card.isToken)
        assertEquals(MageObjectType.Permanent, board[1].card.objectType)
    }

    @Test
    fun aCardWithNoObjectTypeFoldsToUnknownAndIsNotAToken() {
        val folded = GameEventFold.fold(seed, GameStarted(gameId = GAME, state = view(hand = hand(1))))!!

        val card = folded.hand.single()
        assertFalse(card.isToken)
        assertEquals(MageObjectType.Unknown, card.objectType)
    }

    @Test
    fun anUnattachedPermanentFoldsWithNoAttachmentState() {
        val folded =
            GameEventFold.fold(
                seed,
                GameStarted(
                    gameId = GAME,
                    state =
                        GameStateView(
                            turn = 1,
                            viewerPlayerId = "p-1",
                            players =
                                listOf(
                                    GamePlayerView(
                                        playerId = "p-1",
                                        name = "pete",
                                        viewer = true,
                                        battlefield = listOf(GamePermanentView(card = GameCardView(id = "perm-1", name = "Forest"))),
                                    ),
                                ),
                        ),
                ),
            )!!

        val forest = folded.viewer!!.battlefield.single()
        assertNull(forest.attachedTo)
        assertTrue(forest.attachments.isEmpty())
        assertFalse(forest.isAttachedToPermanent)
        assertFalse(forest.attachedControllerDiffers)
    }

    @Test
    fun aCardThatTargetsNothingCarriesAnEmptyTargetList() {
        // Most cards are not targeting stack objects: upstream leaves `targets` null for a hand card
        // or an ordinary permanent, the bridge maps that to empty, and nothing downstream invents one.
        val folded = GameEventFold.fold(seed, GameStarted(gameId = GAME, state = view(hand = hand(1))))!!

        assertTrue(
            folded.hand
                .single()
                .targets
                .isEmpty(),
        )
    }

    @Test
    fun aCardCarriesWhatItCurrentlyIsAndTheCountersOnIt() {
        // Creature-ness is game state: the same Mountain is a land in one snapshot and a
        // 0/3 creature in the next, and only the server can say which. The fold carries all three
        // fields the bridge writes from `mage.view.CardView` — nothing here re-derives them.
        val earthbentMountain =
            GameCardView(
                id = "perm-1",
                name = "Mountain",
                typeLine = "Basic Land — Mountain",
                power = "0",
                toughness = "3",
                cardTypes = listOf(CardTypeCode.LAND, CardTypeCode.CREATURE),
                creature = true,
                counters = listOf(GameCounterView(name = "+1/+1", count = 2), GameCounterView(name = "stun", count = 1)),
            )

        val folded = GameEventFold.fold(seed, GameStarted(gameId = GAME, state = view(hand = listOf(earthbentMountain))))!!
        val card = folded.hand.single()

        assertTrue("the server says this land is a creature right now", card.isCreature)
        assertEquals(listOf(CardType.Land, CardType.Creature), card.cardTypes)
        assertEquals("0", card.power)
        assertEquals("3", card.toughness)
        assertEquals(listOf("+1/+1", "stun"), card.counters.map { it.name })
        assertEquals(listOf(2, 1), card.counters.map { it.count })
    }

    @Test
    fun aCardTheServerSaysNothingAboutIsNotAssumedToBeACreature() {
        val folded = GameEventFold.fold(seed, GameStarted(gameId = GAME, state = view(hand = hand(1))))!!
        val card = folded.hand.single()

        assertFalse("absence of the field is 'the server said nothing', never 'yes'", card.isCreature)
        assertTrue(card.cardTypes.isEmpty())
        assertTrue(card.counters.isEmpty())
    }

    @Test
    fun aCardTypeThisBuildDoesNotKnowFoldsToUnknownRatherThanBeingDropped() {
        val folded =
            GameEventFold.fold(
                seed,
                GameStarted(
                    gameId = GAME,
                    state =
                        view(
                            hand =
                                listOf(
                                    GameCardView(
                                        id = "c-1",
                                        name = "Thing",
                                        cardTypes = listOf(CardTypeCode.CREATURE, CardTypeCode.UNKNOWN),
                                        creature = true,
                                    ),
                                ),
                        ),
                ),
            )!!

        assertEquals(listOf(CardType.Creature, CardType.Unknown), folded.hand.single().cardTypes)
    }

    @Test
    fun aSpectatorSnapshotHasNoSeatNoHandAndNothingPlayable() {
        // The server builds a watcher's GameView with no myPlayer, so canPlayObjects is absent too. An
        // empty playable list here is information — "nothing for you to do" — not a mapping gap.
        val folded =
            GameEventFold.fold(
                seed,
                GameStateUpdated(
                    gameId = GAME,
                    state = view(viewerPlayerId = null, hand = emptyList(), playable = emptyList()),
                ),
            )!!

        assertTrue(folded.isSpectator)
        assertNull(folded.viewer)
        assertTrue(folded.hand.isEmpty())
        assertTrue(folded.playable.isEmpty())
        assertFalse("a spectator has no seat, so it is never its turn", folded.isViewerTurn)
    }

    @Test
    fun aSnapshotReplacesRatherThanMergesTheOneBefore() {
        // The whole design rests on this: a field absent from the new snapshot is absent, never carried
        // over. A merge would show a hand, a battlefield or a playable list the server no longer reports.
        val rich =
            GameEventFold.fold(
                seed,
                GameStarted(gameId = GAME, state = view(turn = 1, hand = hand(7), playable = listOf(playable("c-0")))),
            )!!
        assertEquals(7, rich.hand.size)

        val sparse = GameEventFold.fold(rich, GameStateUpdated(gameId = GAME, state = GameStateView(turn = 2)))!!

        assertEquals(2, sparse.turn)
        assertTrue("the hand is gone because the snapshot has none", sparse.hand.isEmpty())
        assertTrue("so is the playable list", sparse.playable.isEmpty())
        assertTrue(sparse.players.isEmpty())
        assertEquals(TurnPhase.Unknown, sparse.phase)
    }

    // --- every prompt kind ---------------------------------------------------------------------------

    @Test
    fun everyPromptKindMapsToItsTypedAppSchemaFace() {
        assertEquals(
            GamePrompt.Select(message = "Play", options = PromptOptions(text = mapOf(PromptOptions.SPECIAL_BUTTON to "Cycle"))),
            promptOf(SelectPrompt("Play", GamePromptOptions(text = mapOf(GamePromptOptions.SPECIAL_BUTTON to "Cycle")))),
        )
        assertEquals(
            GamePrompt.Target(
                message = "Choose a creature",
                cards = listOf(GameCard(id = "c-1", name = "Forest", setCode = "M21", collectorNumber = "272")),
                targetIds = listOf("t-1"),
                isRequired = true,
                options = PromptOptions(ids = mapOf(PromptOptions.POSSIBLE_TARGETS to listOf("t-1", "t-2"))),
            ),
            promptOf(
                TargetPrompt(
                    message = "Choose a creature",
                    cards = listOf(GameCardView(id = "c-1", name = "Forest", setCode = "M21", collectorNumber = "272")),
                    targetIds = listOf("t-1"),
                    required = true,
                    options = GamePromptOptions(ids = mapOf(GamePromptOptions.POSSIBLE_TARGETS to listOf("t-1", "t-2"))),
                ),
            ),
        )
        assertEquals(
            GamePrompt.Ask(
                message = "Mulligan?",
                options = PromptOptions(text = mapOf(PromptOptions.LEFT_BUTTON_TEXT to "Mulligan")),
            ),
            promptOf(AskPrompt("Mulligan?", GamePromptOptions(text = mapOf(GamePromptOptions.LEFT_BUTTON_TEXT to "Mulligan")))),
        )
        assertEquals(
            GamePrompt.ChooseAbility("Which ability?", listOf(AbilityChoice("a-1", "{T}: Add {G}."))),
            promptOf(ChooseAbilityPrompt("Which ability?", listOf(GameAbilityChoice("a-1", "{T}: Add {G}.")))),
        )
        assertEquals(
            GamePrompt.ChoosePile(
                message = "Pick a pile",
                pile1 = listOf(GameCard(id = "c-1", name = "Forest")),
                pile2 = listOf(GameCard(id = "c-2", name = "Island")),
            ),
            promptOf(
                ChoosePilePrompt(
                    message = "Pick a pile",
                    pile1 = listOf(GameCardView(id = "c-1", name = "Forest")),
                    pile2 = listOf(GameCardView(id = "c-2", name = "Island")),
                ),
            ),
        )
        assertEquals(
            GamePrompt.ChooseChoice(
                message = "Choose a colour",
                choices = listOf(ChoiceOption("green", "Green")),
                subMessage = "for Sylvan Caryatid",
                isRequired = true,
                specialText = "Any",
            ),
            promptOf(
                ChooseChoicePrompt(
                    message = "Choose a colour",
                    choices = listOf(GameChoiceOption("green", "Green")),
                    subMessage = "for Sylvan Caryatid",
                    required = true,
                    specialText = "Any",
                ),
            ),
        )
        assertEquals(GamePrompt.PlayMana("Pay {G}"), promptOf(PlayManaPrompt("Pay {G}")))
        assertEquals(GamePrompt.PlayXMana("Announce X"), promptOf(PlayXManaPrompt("Announce X")))
        assertEquals(GamePrompt.GetAmount("How many?", min = 1, max = 4), promptOf(GetAmountPrompt("How many?", min = 1, max = 4)))
        assertEquals(
            GamePrompt.GetMultiAmount(
                message = "Assign damage",
                entries = listOf(MultiAmountEntry("Bear", min = 0, max = 3, defaultValue = 1)),
                min = 0,
                max = 3,
            ),
            promptOf(
                GetMultiAmountPrompt(
                    message = "Assign damage",
                    entries = listOf(GameMultiAmountEntry("Bear", min = 0, max = 3, defaultValue = 1)),
                    min = 0,
                    max = 3,
                ),
            ),
        )
    }

    @Test
    fun anUnrecognisedPromptSurvivesAsAnUnanswerableOne() {
        // A newer bridge may add a prompt kind additively. It must reach the app (so the UI can show the
        // game as waiting) but it must not pretend to be answerable — GameClient offers no method for it.
        val prompt = promptOf(UnknownGamePrompt(type = "brand_new_prompt"))

        assertEquals(GamePrompt.Unrecognised(type = "brand_new_prompt"), prompt)
        assertEquals("unrecognised prompt", prompt.message)
    }

    @Test
    fun promptOptionsExposeTheServersOwnLabelsAndIdHints() {
        val options =
            PromptOptions(
                text =
                    mapOf(
                        PromptOptions.LEFT_BUTTON_TEXT to "Mulligan",
                        PromptOptions.RIGHT_BUTTON_TEXT to "Keep",
                        PromptOptions.SPECIAL_BUTTON to "Cycle",
                        "somethingNewer" to "carried through",
                    ),
                ids =
                    mapOf(
                        PromptOptions.POSSIBLE_ATTACKERS to listOf("perm-1"),
                        PromptOptions.POSSIBLE_BLOCKERS to listOf("perm-2"),
                        PromptOptions.CHOSEN_TARGETS to listOf("t-1"),
                        PromptOptions.POSSIBLE_TARGETS to listOf("t-1", "t-2"),
                    ),
            )

        assertEquals("Mulligan", options.leftButtonText)
        assertEquals("Keep", options.rightButtonText)
        assertEquals("Cycle", options.specialButtonText)
        assertEquals(listOf("perm-1"), options.possibleAttackers)
        assertEquals(listOf("perm-2"), options.possibleBlockers)
        assertEquals(listOf("t-1"), options.chosenTargets)
        assertEquals(listOf("t-1", "t-2"), options.possibleTargets)
        assertEquals("an unknown hint is carried, not dropped", "carried through", options.text["somethingNewer"])
        assertNull(PromptOptions().specialButtonText)
    }

    // --- fixtures ------------------------------------------------------------------------------------

    private fun promptOf(prompt: magefree.protocol.GamePrompt): GamePrompt =
        GameEventFold.fold(seed, GamePrompted(gameId = GAME, state = view(), prompt = prompt))!!.prompt!!

    private fun prompted(prompt: magefree.protocol.GamePrompt): GameState =
        GameEventFold.fold(seed, GamePrompted(gameId = GAME, state = view(turn = 1), prompt = prompt))!!

    private fun card(
        id: String,
        name: String,
    ) = GameCardView(
        id = id,
        name = name,
        setCode = "M21",
        collectorNumber = "272",
        manaCost = "{G}",
        typeLine = "Basic Land — Forest",
        rules = listOf("({T}: Add {G}.)"),
    )

    private fun hand(size: Int) = (0 until size).map { card("c-$it", "Forest") }

    private fun playable(objectId: String) = GamePlayableObject(objectId = objectId, abilityIds = listOf("a-$objectId"))

    private fun view(
        turn: Int = 1,
        phase: TurnPhaseCode = TurnPhaseCode.PRECOMBAT_MAIN,
        step: PhaseStepCode = PhaseStepCode.PRECOMBAT_MAIN,
        viewerPlayerId: String? = "p-1",
        viewerHasPriority: Boolean = false,
        hand: List<GameCardView> = emptyList(),
        playable: List<GamePlayableObject> = emptyList(),
    ) = GameStateView(
        turn = turn,
        phase = phase,
        step = step,
        activePlayerId = "p-1",
        activePlayerName = "pete",
        priorityPlayerName = if (viewerHasPriority) "pete" else "Computer",
        viewerPlayerId = viewerPlayerId,
        viewerHasPriority = viewerHasPriority,
        players =
            listOf(
                GamePlayerView(playerId = "p-1", name = "pete", life = 20, viewer = viewerPlayerId != null, active = true),
                GamePlayerView(playerId = "p-2", name = "Computer", life = 20, human = false),
            ),
        hand = hand,
        playable = playable,
    )

    private companion object {
        const val GAME = "g-1"
    }
}
