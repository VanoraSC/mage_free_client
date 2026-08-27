package magefree.protocol

import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Wire-contract tests for the in-game protocol: every game request/result/event and every
 * [GamePrompt] subtype round-trips through the polymorphic envelope unchanged, and both levels of
 * unknown-`type` tolerance hold — the envelope's and the **nested prompt's**, which the
 * envelope's own tolerance does not cover.
 */
class GameSerializationTest {
    private val json = ProtocolJson.json

    private companion object {
        val CARD =
            GameCardView(
                id = "c-1",
                name = "Forest",
                setCode = "M21",
                collectorNumber = "272",
                manaCost = "",
                typeLine = "Basic Land - Forest",
                rules = listOf("({T}: Add {G}.)"),
            )

        val PERMANENT =
            GamePermanentView(
                card = CARD,
                tapped = true,
                summoningSickness = true,
                damage = 2,
                attachedTo = "p-9",
                controlledByViewer = true,
            )

        val STATE =
            GameStateView(
                turn = 3,
                phase = TurnPhaseCode.PRECOMBAT_MAIN,
                step = PhaseStepCode.PRECOMBAT_MAIN,
                activePlayerId = "pl-1",
                activePlayerName = "alice",
                priorityPlayerName = "alice",
                viewerPlayerId = "pl-1",
                viewerHasPriority = true,
                players =
                    listOf(
                        GamePlayerView(
                            playerId = "pl-1",
                            name = "alice",
                            life = 20,
                            libraryCount = 53,
                            handCount = 7,
                            graveyardCount = 1,
                            exileCount = 0,
                            wins = 0,
                            winsNeeded = 1,
                            viewer = true,
                            active = true,
                            hasPriority = true,
                            manaPool = GameManaPoolView(green = 2),
                            battlefield = listOf(PERMANENT),
                        ),
                        GamePlayerView(playerId = "pl-2", name = "Computer", life = 18, human = false),
                    ),
                hand = listOf(CARD),
                stack = listOf(CARD),
                exile = listOf(GameZoneView(name = "Exile", cards = listOf(CARD), zoneId = "z-1")),
                revealed = listOf(GameZoneView(name = "Revealed", cards = listOf(CARD))),
                combat =
                    listOf(
                        GameCombatGroupView(
                            defenderId = "pl-2",
                            defenderName = "Computer",
                            blocked = true,
                            attackerIds = listOf("perm-1"),
                            blockerIds = listOf("perm-2"),
                        ),
                    ),
                playable = listOf(GamePlayableObject(objectId = "c-1", abilityIds = listOf("ab-1", "ab-2"))),
                specialActionsAvailable = true,
                priorityTimeSeconds = 1200,
                bufferTimeSeconds = 5,
            )

        val OPTIONS =
            GamePromptOptions(
                text =
                    mapOf(
                        GamePromptOptions.LEFT_BUTTON_TEXT to "Mulligan",
                        GamePromptOptions.RIGHT_BUTTON_TEXT to "Keep",
                        GamePromptOptions.SPECIAL_BUTTON to "All attack",
                    ),
                ids =
                    mapOf(
                        GamePromptOptions.POSSIBLE_ATTACKERS to listOf("perm-1", "perm-2"),
                        GamePromptOptions.CHOSEN_TARGETS to listOf("pl-2"),
                    ),
            )

        /** Every [GamePrompt] subtype — the closed set the app must be able to answer. */
        val PROMPTS: List<GamePrompt> =
            listOf(
                SelectPrompt(message = "Play instant or ability", options = OPTIONS),
                TargetPrompt(
                    message = "Choose target creature",
                    cards = listOf(CARD),
                    targetIds = listOf("perm-1", "perm-2"),
                    required = true,
                    options = OPTIONS,
                ),
                AskPrompt(message = "Mulligan?", options = OPTIONS),
                ChooseAbilityPrompt(
                    message = "Choose ability",
                    choices = listOf(GameAbilityChoice(abilityId = "ab-1", text = "1. Cast Forest")),
                ),
                ChoosePilePrompt(message = "Choose a pile", pile1 = listOf(CARD), pile2 = emptyList()),
                ChooseChoicePrompt(
                    message = "Choose a color",
                    choices = listOf(GameChoiceOption(key = "Green", label = "Green")),
                    subMessage = "for Prismatic Lens",
                    required = true,
                    specialText = "Any",
                ),
                PlayManaPrompt(message = "Pay {G}", options = OPTIONS),
                PlayXManaPrompt(message = "Announce X", options = OPTIONS),
                GetAmountPrompt(message = "How many?", min = 1, max = 4, options = OPTIONS),
                GetMultiAmountPrompt(
                    message = "Assign damage",
                    entries = listOf(GameMultiAmountEntry(message = "Bear", min = 0, max = 3, defaultValue = 1)),
                    min = 0,
                    max = 3,
                    options = OPTIONS,
                ),
            )
    }

    @Test
    fun `game client messages round-trip through the polymorphic envelope`() {
        val messages: List<ClientMessage> =
            listOf(
                JoinGame(gameId = "g-1", requestId = "r-1"),
                WatchGame(gameId = "g-1", requestId = "r-2"),
                QuitMatch(gameId = "g-1", requestId = "r-3"),
                StopWatching(gameId = "g-1", requestId = "r-4"),
                SendPlayerUuid(gameId = "g-1", value = "c-1", requestId = "r-5"),
                SendPlayerBoolean(gameId = "g-1", value = false, requestId = "r-6"),
                SendPlayerInteger(gameId = "g-1", value = 3, requestId = "r-7"),
                SendPlayerString(gameId = "g-1", value = "1,2,0", requestId = "r-8"),
                SendPlayerManaType(
                    gameId = "g-1",
                    playerId = "pl-1",
                    manaType = ManaTypeCode.GREEN,
                    requestId = "r-9",
                ),
                SendPlayerAction(
                    gameId = "g-1",
                    action = PlayerActionCode.ROLLBACK_TURNS,
                    dataInt = 2,
                    requestId = "r-10",
                ),
                SendPlayerAction(
                    gameId = "g-1",
                    action = PlayerActionCode.REQUEST_AUTO_ANSWER_TEXT_YES,
                    dataText = "Pay {1}?",
                ),
                GetGameState(gameId = "g-1", requestId = "r-11"),
                GetGameState(gameId = "g-1"),
                // The default-valued form: proves nothing required is silently defaulted away.
                JoinGame(gameId = "g-1"),
            )

        messages.forEach { message ->
            val decoded = json.decodeFromString<ClientMessage>(json.encodeToString(message))
            assertEquals(message, decoded, "round-trip changed $message")
        }
    }

    @Test
    fun `game server messages round-trip through the polymorphic envelope`() {
        val messages: List<ServerMessage> =
            listOf(
                GameActionResult(action = GameActionCode.JOIN_GAME, ok = true, requestId = "r-1"),
                GameActionResult(
                    action = GameActionCode.SEND_UUID,
                    ok = false,
                    reason = "invalid object id",
                    failure = GameFailureCode.REFUSED,
                    requestId = "r-2",
                ),
                GameActionResult(
                    action = GameActionCode.PLAYER_ACTION,
                    ok = false,
                    reason = "no connected session",
                    failure = GameFailureCode.SESSION_GONE,
                ),
                GameStarted(gameId = "g-1", state = STATE),
                GameStateUpdated(gameId = "g-1", state = STATE),
                GameInformed(gameId = "g-1", message = "alice draws a card", state = STATE),
                GameInformed(gameId = "g-1", message = "You lost the roll", personal = true),
                GameError(gameId = "g-1", message = "server exploded"),
                GameOver(gameId = "g-1", message = "alice has won", state = STATE),
                WatchingGame(gameId = "g-1", tableId = "t-1", parentTableId = "t-0"),
                GameStateSnapshot(gameId = "g-1", state = STATE, capturedAtEpochMs = 1_700_000_000_000L, requestId = "r-11"),
                GameStateSnapshot(gameId = "g-1", state = STATE),
                GameStateUnavailable(
                    gameId = "g-1",
                    reason = GameStateUnavailableCode.NO_STATE_YET,
                    detail = "no snapshot for this game on this session yet",
                    requestId = "r-12",
                ),
                GameStateUnavailable(gameId = "g-1", reason = GameStateUnavailableCode.SESSION_GONE),
            ) + PROMPTS.map { GamePrompted(gameId = "g-1", state = STATE, prompt = it) }

        messages.forEach { message ->
            val decoded = json.decodeFromString<ServerMessage>(json.encodeToString(message))
            assertEquals(message, decoded, "round-trip changed $message")
        }
    }

    @Test
    fun `every prompt subtype carries a distinct wire discriminator`() {
        // A duplicated @SerialName would silently make two prompts indistinguishable on the wire —
        // exactly the ambiguity a closed, typed prompt set exists to prevent.
        val discriminators =
            PROMPTS.map { prompt ->
                val encoded = json.encodeToString<ServerMessage>(GamePrompted("g-1", STATE, prompt))
                Regex(""""prompt":\{"type":"([a-z_]+)"""").find(encoded)?.groupValues?.get(1)
            }

        assertTrue(discriminators.all { it != null }, "every prompt should encode a type discriminator, got $discriminators")
        assertEquals(PROMPTS.size, discriminators.toSet().size, "prompt discriminators must be unique, got $discriminators")
    }

    @Test
    fun `an unknown prompt type decodes to the sentinel instead of throwing`() {
        // The envelope's `type` is known, so ServerMessage-level tolerance never sees this: without a
        // polymorphic default for GamePrompt the whole frame — state included — would fail to decode.
        val frame =
            """
            {"type":"game_prompted","gameId":"g-1","state":{"turn":7},
             "prompt":{"type":"choose_dungeon_room","message":"Which room?","rooms":["a","b"]}}
            """.trimIndent()

        val decoded = json.decodeFromString<ServerMessage>(frame)

        assertTrue(decoded is GamePrompted, "expected the envelope to still decode, got $decoded")
        val prompted = decoded as GamePrompted
        assertEquals(7, prompted.state.turn, "the state must survive an unrecognised prompt")
        assertTrue(prompted.prompt is UnknownGamePrompt, "expected an UnknownGamePrompt sentinel, got ${prompted.prompt}")
        assertEquals("choose_dungeon_room", (prompted.prompt as UnknownGamePrompt).type)
    }

    @Test
    fun `the unknown prompt sentinel is deserialize-only and never encoded`() {
        assertThrows(SerializationException::class.java) {
            json.encodeToString<ServerMessage>(
                GamePrompted(gameId = "g-1", state = STATE, prompt = UnknownGamePrompt("choose_dungeon_room")),
            )
        }
    }

    @Test
    fun `an unknown game message type decodes to the envelope sentinel`() {
        val decoded = json.decodeFromString<ServerMessage>("""{"type":"game_replay_started","gameId":"g-1"}""")

        assertTrue(decoded is UnknownServerMessage, "expected an UnknownServerMessage sentinel, got $decoded")
        assertEquals("game_replay_started", (decoded as UnknownServerMessage).type)
    }

    @Test
    fun `an unknown extra field on a game frame is tolerated`() {
        val frame = """{"type":"join_game","gameId":"g-1","seatPreference":"left"}"""

        assertEquals(JoinGame(gameId = "g-1"), json.decodeFromString<ClientMessage>(frame))
    }

    @Test
    fun `a state frame with only a turn decodes, every other field defaulting`() {
        // Additive forward-compat in the other direction: an older bridge that has not yet learned a
        // field must still produce a decodable state, so every GameStateView field carries a default.
        val decoded = json.decodeFromString<ServerMessage>("""{"type":"game_started","gameId":"g-1","state":{}}""")

        assertTrue(decoded is GameStarted, "expected a GameStarted, got $decoded")
        val state = (decoded as GameStarted).state
        assertEquals(GameStateView(), state, "an empty state object should decode to the all-defaults view")
        assertEquals(TurnPhaseCode.UNKNOWN, state.phase)
        assertEquals(PhaseStepCode.UNKNOWN, state.step)
        assertTrue(state.playable.isEmpty(), "no canPlayObjects means nothing is playable — never a guess")
    }

    @Test
    fun `null defaults are omitted from a game frame`() {
        assertEquals("""{"type":"join_game","gameId":"g-1"}""", json.encodeToString<ClientMessage>(JoinGame("g-1")))
    }

    @Test
    fun `the game-state read carries its own discriminators`() {
        // The read and its two replies must be distinguishable on the wire from the pushes
        // that carry the same payload — a `game_state_snapshot` that encoded as `game_state_updated`
        // would be folded as a fresh server push rather than correlated to its waiter.
        assertEquals(
            """{"type":"get_game_state","gameId":"g-1","requestId":"r-1"}""",
            json.encodeToString<ClientMessage>(GetGameState(gameId = "g-1", requestId = "r-1")),
        )
        val snapshot = json.encodeToString<ServerMessage>(GameStateSnapshot(gameId = "g-1", state = STATE))
        assertTrue(snapshot.contains(""""type":"game_state_snapshot""""), "got $snapshot")

        val unavailable =
            json.encodeToString<ServerMessage>(
                GameStateUnavailable(gameId = "g-1", reason = GameStateUnavailableCode.NO_STATE_YET),
            )
        assertTrue(unavailable.contains(""""type":"game_state_unavailable""""), "got $unavailable")
    }

    @Test
    fun `a no-state reply is typed, and never decodes as a snapshot of an empty board`() {
        // The acceptance criterion this turns on: before any snapshot exists the honest answer is
        // a *kind*, not a GameStateView full of defaults. An empty board is indistinguishable from a real
        // one — no players, no hand, nothing playable is a legal snapshot — so a client that received one
        // would render it as truth. Decoding must therefore land on a different type entirely.
        val frame = """{"type":"game_state_unavailable","gameId":"g-1","reason":"NO_STATE_YET","requestId":"r-1"}"""

        val decoded = json.decodeFromString<ServerMessage>(frame)

        assertTrue(decoded is GameStateUnavailable, "expected a GameStateUnavailable, got $decoded")
        val unavailable = decoded as GameStateUnavailable
        assertEquals(GameStateUnavailableCode.NO_STATE_YET, unavailable.reason)
        assertEquals("g-1", unavailable.gameId)
        assertEquals("r-1", unavailable.requestId)
    }

    @Test
    fun `a snapshot from a bridge that does not stamp a capture time still decodes`() {
        // Additive tolerance in the older-peer direction: `capturedAtEpochMs` is newer than the first
        // without it must decode rather than throw — and must report the absence rather than a fake zero.
        val frame = """{"type":"game_state_snapshot","gameId":"g-1","state":{"turn":4},"requestId":"r-1"}"""

        val decoded = json.decodeFromString<ServerMessage>(frame)

        assertTrue(decoded is GameStateSnapshot, "expected a GameStateSnapshot, got $decoded")
        val snapshot = decoded as GameStateSnapshot
        assertEquals(4, snapshot.state.turn)
        assertNull(snapshot.capturedAtEpochMs, "an absent capture time is null, never a fabricated instant")
    }

    // ---- what a card currently *is* -----------------------------------------------------

    @Test
    fun `a card carries its current types, creature status and counters`() {
        val animatedLand =
            GameCardView(
                id = "c-2",
                name = "Mountain",
                setCode = "M21",
                collectorNumber = "269",
                typeLine = "Basic Land - Mountain",
                power = "2",
                toughness = "2",
                // An Earthbent Mountain: still a land, and a creature *as well* — which is exactly why
                // the list is carried rather than a single type.
                cardTypes = listOf(CardTypeCode.LAND, CardTypeCode.CREATURE),
                creature = true,
                counters = listOf(GameCounterView(name = "+1/+1", count = 2), GameCounterView(name = "stun", count = 1)),
            )

        val round = json.decodeFromString<GameCardView>(json.encodeToString(animatedLand))

        assertEquals(animatedLand, round)
        assertTrue(round.creature, "creature status is the server's own answer and must survive the wire")
        assertEquals(listOf(CardTypeCode.LAND, CardTypeCode.CREATURE), round.cardTypes)
        assertEquals(listOf("+1/+1", "stun"), round.counters.map { it.name })
        assertEquals(listOf(2, 1), round.counters.map { it.count })
    }

    @Test
    fun `a card frame from an older bridge decodes with the 0058 fields defaulted`() {
        // Additive-only: the fields added by this must have defaults, so a payload written before
        // them still decodes rather than throwing.
        val frame = """{"id":"c-3","name":"Forest"}"""

        val decoded = json.decodeFromString<GameCardView>(frame)

        assertTrue(decoded.cardTypes.isEmpty())
        assertFalse(decoded.creature, "absence of the field is 'the server said nothing', never 'it is a creature'")
        assertTrue(decoded.counters.isEmpty())
    }

    @Test
    fun `a stack entry's targets round-trip in the order the server sent them`() {
        // Upstream de-duplicates through a LinkedHashSet precisely so the order is stable
        // across snapshots, so the wire must not turn the list into a set or sort it.
        val bolt = GameCardView(id = "s-1", name = "Lightning Bolt", targets = listOf("perm-9", "p-2"))

        val round = json.decodeFromString<GameCardView>(json.encodeToString(bolt))

        assertEquals(listOf("perm-9", "p-2"), round.targets)
    }

    @Test
    fun `a card frame from a bridge that does not send targets decodes with none`() {
        // Additive-only, per ProtocolVersion: a payload written before the field still decodes, and its
        // absence means "this is not pointing at anything" -- which for most cards is simply true.
        val decoded = json.decodeFromString<GameCardView>("""{"id":"c-3","name":"Forest"}""")

        assertTrue(decoded.targets.isEmpty())
    }

    @Test
    fun `a permanent's attachments round-trip in both directions`() {
        // The host's list and the attachment's own back-pointer are separate fields and
        // both have to survive: a board that has one but not the other cannot draw the relationship.
        val aura =
            GamePermanentView(
                card = GameCardView(id = "perm-2", name = "Rancor"),
                attachedTo = "perm-1",
                attachedToPermanent = true,
                attachedControllerDiffers = true,
            )
        val host = GamePermanentView(card = GameCardView(id = "perm-1", name = "Grizzly Bears"), attachments = listOf("perm-2"))

        val roundAura = json.decodeFromString<GamePermanentView>(json.encodeToString(aura))
        val roundHost = json.decodeFromString<GamePermanentView>(json.encodeToString(host))

        assertEquals("perm-1", roundAura.attachedTo)
        assertTrue(roundAura.attachedToPermanent)
        assertTrue(roundAura.attachedControllerDiffers)
        assertEquals(listOf("perm-2"), roundHost.attachments)
    }

    @Test
    fun `a permanent frame from a bridge that does not send attachment state decodes with none`() {
        // Additive-only, per ProtocolVersion. Both flags must default to false rather than to
        // "probably": "the bridge said nothing" is never "your aura is on their creature".
        val frame = """{"card":{"id":"perm-1","name":"Forest"}}"""

        val decoded = json.decodeFromString<GamePermanentView>(frame)

        assertTrue(decoded.attachments.isEmpty())
        assertFalse(decoded.attachedToPermanent)
        assertFalse(decoded.attachedControllerDiffers)
    }

    @Test
    fun `a player's counters, crown and designations round-trip`() {
        val player =
            GamePlayerView(
                playerId = "pl-1",
                name = "alice",
                counters = listOf(GameCounterView("poison", 3), GameCounterView("energy", 2)),
                monarch = true,
                initiative = true,
                designationNames = listOf("City's Blessing"),
            )

        val round = json.decodeFromString<GamePlayerView>(json.encodeToString(player))

        assertEquals(mapOf("poison" to 3, "energy" to 2), round.counters.associate { it.name to it.count })
        assertTrue(round.monarch)
        assertTrue(round.initiative)
        assertEquals(listOf("City's Blessing"), round.designationNames)
    }

    @Test
    fun `a player frame without counters decodes with none, and with neither crown`() {
        // Absence is "the bridge said nothing", which for a boolean win-condition flag must be false.
        val decoded = json.decodeFromString<GamePlayerView>("""{"playerId":"pl-1","name":"alice"}""")

        assertTrue(decoded.counters.isEmpty())
        assertFalse(decoded.monarch)
        assertFalse(decoded.initiative)
        assertTrue(decoded.designationNames.isEmpty())
    }

    @Test
    fun `a command zone round-trips with each kind`() {
        val player =
            GamePlayerView(
                playerId = "pl-1",
                name = "alice",
                commandList =
                    listOf(
                        GameCommandObjectView(id = "cmd-1", name = "Emblem", kind = CommandObjectKind.EMBLEM, rules = listOf("…")),
                        GameCommandObjectView(
                            id = "cmd-2",
                            name = "Atraxa, Praetors' Voice",
                            kind = CommandObjectKind.COMMANDER,
                            setCode = "C16",
                            collectorNumber = "28",
                        ),
                    ),
            )

        val round = json.decodeFromString<GamePlayerView>(json.encodeToString(player))

        assertEquals(listOf(CommandObjectKind.EMBLEM, CommandObjectKind.COMMANDER), round.commandList.map { it.kind })
        assertEquals("28", round.commandList[1].collectorNumber)
        assertNull(round.commandList[0].collectorNumber)
    }

    @Test
    fun `a command object kind this build has never heard of decodes to UNKNOWN instead of throwing`() {
        // The same forward-compatibility promise CardTypeCode makes: upstream adds command-object
        // kinds, and one this build does not know must cost a single value, not the whole snapshot.
        val frame = """{"id":"cmd-9","name":"Something New","kind":"SAGA_OF_THE_FUTURE"}"""

        val decoded = json.decodeFromString<GameCommandObjectView>(frame)

        assertEquals(CommandObjectKind.UNKNOWN, decoded.kind)
        assertEquals("Something New", decoded.name)
    }

    @Test
    fun `command object kinds encode as their own names`() {
        val encoded = json.encodeToString(GameCommandObjectView(id = "c", name = "n", kind = CommandObjectKind.DUNGEON))

        assertTrue(encoded.contains("\"kind\":\"DUNGEON\""), encoded)
    }

    @Test
    fun `a player frame from a bridge that sends no command zone decodes with an empty one`() {
        val decoded = json.decodeFromString<GamePlayerView>("""{"playerId":"pl-1","name":"alice"}""")

        assertTrue(decoded.commandList.isEmpty())
    }

    @Test
    fun `a player's graveyard and exile round-trip in order, alongside their counts`() {
        val player =
            GamePlayerView(
                playerId = "pl-1",
                name = "alice",
                graveyardCount = 2,
                exileCount = 1,
                graveyard = listOf(GameCardView(id = "g-1", name = "Bolt"), GameCardView(id = "g-2", name = "Forest")),
                exile = listOf(GameCardView(id = "x-1", name = "Bear")),
            )

        val round = json.decodeFromString<GamePlayerView>(json.encodeToString(player))

        assertEquals(listOf("Bolt", "Forest"), round.graveyard.map { it.name })
        assertEquals(listOf("Bear"), round.exile.map { it.name })
        assertEquals(2, round.graveyardCount)
        assertEquals(1, round.exileCount)
    }

    @Test
    fun `a player frame that carries only counts decodes with empty zone lists`() {
        // The additive shape: an older bridge sends the counts and nothing else, and the lists default
        // to empty rather than the decode failing.
        val decoded =
            json.decodeFromString<GamePlayerView>("""{"playerId":"pl-1","name":"alice","graveyardCount":7,"exileCount":2}""")

        assertEquals(7, decoded.graveyardCount)
        assertTrue(decoded.graveyard.isEmpty())
        assertTrue(decoded.exile.isEmpty())
    }

    @Test
    fun `a card type this build has never heard of decodes to UNKNOWN instead of throwing`() {
        // The list form of the forward-compat promise: upstream keeps adding card types (BATTLE and
        // DUNGEON are recent), and `ignoreUnknownKeys` does not cover an unknown *value* inside a list.
        // One unrecognised entry must cost that entry, never the whole snapshot.
        val frame = """{"id":"c-4","name":"Thing","cardTypes":["CREATURE","SPACESHIP"]}"""

        val decoded = json.decodeFromString<GameCardView>(frame)

        assertEquals(listOf(CardTypeCode.CREATURE, CardTypeCode.UNKNOWN), decoded.cardTypes)
    }

    @Test
    fun `card types encode as their upstream names`() {
        val encoded = json.encodeToString(GameCardView(id = "c-5", name = "Bear", cardTypes = listOf(CardTypeCode.CREATURE)))

        assertTrue(encoded.contains(""""cardTypes":["CREATURE"]"""), "got $encoded")
    }

    @Test
    fun `power and toughness stay strings, so a star is carried as sent`() {
        // Tarmogoyf/Mortivore: `*` is a real power. Parsing it into a number anywhere on this wire would
        // lose it, so the contract is a string end to end.
        val goyf = GameCardView(id = "c-6", name = "Tarmogoyf", power = "*", toughness = "1+*", creature = true)

        val round = json.decodeFromString<GameCardView>(json.encodeToString(goyf))

        assertEquals("*", round.power)
        assertEquals("1+*", round.toughness)
    }

    @Test
    fun `the prompt set names its reply shape for every subtype`() {
        // A guard on the contract this exists to make honest: each prompt must be answerable.
        // (The mapping itself is documented per subtype; here we assert the set has not silently grown
        // a member with no discriminator/round-trip coverage above.)
        assertEquals(10, PROMPTS.size, "the closed prompt set should have one member per upstream prompt callback")
        assertNotNull(PROMPTS.first().message)
    }
}
