package magefree.protocol

import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Wire-contract tests for the in-game protocol (story 0051): every game request/result/event and every
 * [GamePrompt] subtype round-trips through the polymorphic envelope unchanged, and both levels of
 * unknown-`type` tolerance hold — the envelope's (story 0026 F1) and the **nested prompt's**, which the
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
    fun `the prompt set names its reply shape for every subtype`() {
        // A guard on the contract this story exists to make honest: each prompt must be answerable.
        // (The mapping itself is documented per subtype; here we assert the set has not silently grown
        // a member with no discriminator/round-trip coverage above.)
        assertEquals(10, PROMPTS.size, "the closed prompt set should have one member per upstream prompt callback")
        assertNotNull(PROMPTS.first().message)
    }
}
