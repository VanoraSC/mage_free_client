package magefree.network.game

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import magefree.model.ConnectionState
import magefree.network.fake.FakeBridgeClient
import magefree.protocol.ClientMessage
import magefree.protocol.GameActionCode
import magefree.protocol.GameActionResult
import magefree.protocol.GameCardView
import magefree.protocol.GameFailureCode
import magefree.protocol.GamePlayableObject
import magefree.protocol.GamePlayerView
import magefree.protocol.GameStateSnapshot
import magefree.protocol.GameStateUnavailable
import magefree.protocol.GameStateUnavailableCode
import magefree.protocol.GameStateView
import magefree.protocol.GetGameState
import magefree.protocol.JoinGame
import magefree.protocol.ManaTypeCode
import magefree.protocol.PhaseStepCode
import magefree.protocol.PlayerActionCode
import magefree.protocol.QuitMatch
import magefree.protocol.SendPlayerAction
import magefree.protocol.SendPlayerBoolean
import magefree.protocol.SendPlayerInteger
import magefree.protocol.SendPlayerManaType
import magefree.protocol.SendPlayerString
import magefree.protocol.SendPlayerUuid
import magefree.protocol.ServerMessage
import magefree.protocol.StopWatching
import magefree.protocol.TableActionCode
import magefree.protocol.TableActionResult
import magefree.protocol.TurnPhaseCode
import magefree.protocol.WatchGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hermetic coverage of the production [DefaultGameClient]: each verb mints a `requestId`, sends the
 * matching 0051 request over the [FakeBridgeClient]'s request/response seam, and maps the correlated
 * `GameActionResult` into a [Result].
 *
 * The bulk of it is the **reply-to-wire mapping**, one case per prompt kind. That mapping is the whole
 * risk in this layer: upstream's client→server game protocol is five untyped primitives, so a reply sent
 * as the wrong primitive — or with the wrong encoding, like a multi-amount list — does not fail loudly;
 * the server simply refuses it and re-asks, and the game stalls. Each case below pins the exact message
 * one prompt's answer produces.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GameClientTest {
    private val sent = mutableListOf<ClientMessage>()

    private fun client(reply: (ClientMessage) -> ServerMessage = { ok(it) }): DefaultGameClient {
        val fake =
            FakeBridgeClient(
                responder = { message ->
                    sent += message
                    reply(message)
                },
            )
        return DefaultGameClient(
            bridgeClient = fake,
            pushSource = fake,
            connectionState = MutableStateFlow(ConnectionState.Connected),
            newRequestId = { REQUEST_ID },
        )
    }

    /** A success for whatever verb was sent — the request itself is what each test asserts on. */
    private fun ok(message: ClientMessage): ServerMessage = GameActionResult(action = codeOf(message), ok = true, requestId = REQUEST_ID)

    private fun codeOf(message: ClientMessage): GameActionCode =
        when (message) {
            is JoinGame -> GameActionCode.JOIN_GAME
            is WatchGame -> GameActionCode.WATCH_GAME
            is QuitMatch -> GameActionCode.QUIT_MATCH
            is StopWatching -> GameActionCode.STOP_WATCHING
            is SendPlayerUuid -> GameActionCode.SEND_UUID
            is SendPlayerBoolean -> GameActionCode.SEND_BOOLEAN
            is SendPlayerInteger -> GameActionCode.SEND_INTEGER
            is SendPlayerString -> GameActionCode.SEND_STRING
            is SendPlayerManaType -> GameActionCode.SEND_MANA_TYPE
            is SendPlayerAction -> GameActionCode.PLAYER_ACTION
            else -> error("not a game request: $message")
        }

    // --- joining and leaving -------------------------------------------------------------------------

    @Test
    fun joinGameSendsJoinGameForTheGameAndSucceeds() =
        runTest {
            val result = client().joinGame(GAME)

            assertEquals(JoinGame(gameId = GAME, requestId = REQUEST_ID), sent.single())
            assertTrue(result.isSuccess)
        }

    @Test
    fun watchGameSendsWatchGame() =
        runTest {
            assertTrue(client().watchGame(GAME).isSuccess)
            assertEquals(WatchGame(gameId = GAME, requestId = REQUEST_ID), sent.single())
        }

    @Test
    fun quitMatchSendsQuitMatch() =
        runTest {
            assertTrue(client().quitMatch(GAME).isSuccess)
            assertEquals(QuitMatch(gameId = GAME, requestId = REQUEST_ID), sent.single())
        }

    @Test
    fun stopWatchingSendsStopWatching() =
        runTest {
            assertTrue(client().stopWatching(GAME).isSuccess)
            assertEquals(StopWatching(gameId = GAME, requestId = REQUEST_ID), sent.single())
        }

    // --- one reply per prompt kind -------------------------------------------------------------------

    @Test
    fun aSelectIsAnsweredByAnObjectIdAPassOrTheSpecialButton() =
        runTest {
            val client = client()

            assertTrue(client.playObject(GAME, "card-7").isSuccess)
            assertEquals(SendPlayerUuid(gameId = GAME, value = "card-7", requestId = REQUEST_ID), sent[0])

            assertTrue(client.passPriority(GAME).isSuccess)
            assertEquals(
                "a pass is upstream's boolean-false arm of the select, not a player action",
                SendPlayerBoolean(gameId = GAME, value = false, requestId = REQUEST_ID),
                sent[1],
            )

            assertTrue(client.useSpecialAction(GAME).isSuccess)
            assertEquals(SendPlayerString(gameId = GAME, value = "special", requestId = REQUEST_ID), sent[2])
        }

    @Test
    fun aTargetIsAnsweredByAnIdAndDeclinedByTheSharedCancelArm() =
        runTest {
            val client = client()

            assertTrue(client.chooseTarget(GAME, "target-3").isSuccess)
            assertEquals(SendPlayerUuid(gameId = GAME, value = "target-3", requestId = REQUEST_ID), sent[0])

            assertTrue(client.cancelPrompt(GAME).isSuccess)
            assertEquals(SendPlayerBoolean(gameId = GAME, value = false, requestId = REQUEST_ID), sent[1])
        }

    @Test
    fun anAskIsAnsweredByABoolean() =
        runTest {
            val client = client()

            assertTrue(client.answerAsk(GAME, answer = true).isSuccess)
            assertEquals(SendPlayerBoolean(gameId = GAME, value = true, requestId = REQUEST_ID), sent[0])

            assertTrue(client.answerAsk(GAME, answer = false).isSuccess)
            assertEquals(SendPlayerBoolean(gameId = GAME, value = false, requestId = REQUEST_ID), sent[1])
        }

    @Test
    fun anAbilityChoiceIsAnsweredByTheAbilityId() =
        runTest {
            assertTrue(client().chooseAbility(GAME, "ability-2").isSuccess)
            assertEquals(SendPlayerUuid(gameId = GAME, value = "ability-2", requestId = REQUEST_ID), sent.single())
        }

    @Test
    fun aPileChoiceIsAnsweredByABooleanWhereTrueIsTheFirstPile() =
        runTest {
            val client = client()

            assertTrue(client.choosePile(GAME, first = true).isSuccess)
            assertEquals(SendPlayerBoolean(gameId = GAME, value = true, requestId = REQUEST_ID), sent[0])

            assertTrue(client.choosePile(GAME, first = false).isSuccess)
            assertEquals(SendPlayerBoolean(gameId = GAME, value = false, requestId = REQUEST_ID), sent[1])
        }

    @Test
    fun aChoiceIsAnsweredByItsKeyAndItsSpecialOptionByTheHashPrefixedKey() =
        runTest {
            val client = client()

            assertTrue(client.chooseChoice(GAME, key = "green").isSuccess)
            assertEquals(SendPlayerString(gameId = GAME, value = "green", requestId = REQUEST_ID), sent[0])

            assertTrue(client.chooseChoice(GAME, key = "green", special = true).isSuccess)
            assertEquals(
                "upstream reads a leading '#' as 'the choice's special option'",
                SendPlayerString(gameId = GAME, value = "#green", requestId = REQUEST_ID),
                sent[1],
            )
        }

    @Test
    fun aManaPromptIsAnsweredByASourceOrByUnlockingATypeFromAPool() =
        runTest {
            val client = client()

            assertTrue(client.playManaSource(GAME, "land-1").isSuccess)
            assertEquals(SendPlayerUuid(gameId = GAME, value = "land-1", requestId = REQUEST_ID), sent[0])

            assertTrue(client.unlockMana(GAME, playerId = "p-1", manaType = ManaType.Green).isSuccess)
            assertEquals(
                SendPlayerManaType(gameId = GAME, playerId = "p-1", manaType = ManaTypeCode.GREEN, requestId = REQUEST_ID),
                sent[1],
            )
        }

    @Test
    fun everyManaTypeMapsOntoItsWireCode() =
        runTest {
            val client = client()
            val expected =
                listOf(
                    ManaType.White to ManaTypeCode.WHITE,
                    ManaType.Blue to ManaTypeCode.BLUE,
                    ManaType.Black to ManaTypeCode.BLACK,
                    ManaType.Red to ManaTypeCode.RED,
                    ManaType.Green to ManaTypeCode.GREEN,
                    ManaType.Colorless to ManaTypeCode.COLORLESS,
                    ManaType.Generic to ManaTypeCode.GENERIC,
                )
            assertEquals("every app-schema mana type must be covered", ManaType.entries.size, expected.size)

            expected.forEach { (type, _) -> client.unlockMana(GAME, playerId = "p-1", manaType = type) }

            assertEquals(expected.map { it.second }, sent.map { (it as SendPlayerManaType).manaType })
        }

    @Test
    fun theNumericPromptsAreAnsweredByAnInteger() =
        runTest {
            val client = client()

            assertTrue(client.announceX(GAME, value = 3).isSuccess)
            assertEquals(SendPlayerInteger(gameId = GAME, value = 3, requestId = REQUEST_ID), sent[0])

            assertTrue(client.chooseAmount(GAME, amount = 2).isSuccess)
            assertEquals(SendPlayerInteger(gameId = GAME, value = 2, requestId = REQUEST_ID), sent[1])
        }

    @Test
    fun aMultiAmountIsAnsweredByACommaSeparatedListInEntryOrder() =
        runTest {
            // The encoding is the answer here: upstream parses exactly this positional form, so rendering
            // it in the client (rather than leaving it to a caller) is what stops a UI inventing its own.
            assertTrue(client().distributeAmounts(GAME, listOf(2, 0, 1)).isSuccess)
            assertEquals(SendPlayerString(gameId = GAME, value = "2,0,1", requestId = REQUEST_ID), sent.single())
        }

    // --- player actions ------------------------------------------------------------------------------

    @Test
    fun concedeIsAPlayerActionRatherThanAPromptAnswer() =
        runTest {
            assertTrue(client().concede(GAME).isSuccess)
            assertEquals(
                SendPlayerAction(gameId = GAME, action = PlayerActionCode.CONCEDE, requestId = REQUEST_ID),
                sent.single(),
            )
        }

    @Test
    fun everyPassPriorityScopeMapsOntoItsPlayerAction() =
        runTest {
            val client = client()
            val expected =
                listOf(
                    PassPriorityScope.UntilMyNextTurn to PlayerActionCode.PASS_PRIORITY_UNTIL_MY_NEXT_TURN,
                    PassPriorityScope.UntilTurnEndStep to PlayerActionCode.PASS_PRIORITY_UNTIL_TURN_END_STEP,
                    PassPriorityScope.UntilNextMainPhase to PlayerActionCode.PASS_PRIORITY_UNTIL_NEXT_MAIN_PHASE,
                    PassPriorityScope.UntilNextTurn to PlayerActionCode.PASS_PRIORITY_UNTIL_NEXT_TURN,
                    PassPriorityScope.UntilNextTurnSkipStack to PlayerActionCode.PASS_PRIORITY_UNTIL_NEXT_TURN_SKIP_STACK,
                    PassPriorityScope.UntilStackResolved to PlayerActionCode.PASS_PRIORITY_UNTIL_STACK_RESOLVED,
                    PassPriorityScope.UntilEndStepBeforeMyNextTurn to
                        PlayerActionCode.PASS_PRIORITY_UNTIL_END_STEP_BEFORE_MY_NEXT_TURN,
                    PassPriorityScope.CancelAll to PlayerActionCode.PASS_PRIORITY_CANCEL_ALL_ACTIONS,
                )
            assertEquals("every scope must be covered", PassPriorityScope.entries.size, expected.size)

            expected.forEach { (scope, _) -> client.passPriorityUntil(GAME, scope) }

            assertEquals(expected.map { it.second }, sent.map { (it as SendPlayerAction).action })
        }

    // --- failures ------------------------------------------------------------------------------------

    @Test
    fun aDeclinedActionSurfacesTheServersReasonAsATypedFailure() =
        runTest {
            val client =
                client { GameActionResult(action = GameActionCode.SEND_UUID, ok = false, reason = "not your priority") }

            val result = client.playObject(GAME, "card-7")

            val failure = result.exceptionOrNull()
            assertTrue("a decline must be a typed failure, not a silent drop, got $failure", failure is GameActionFailure)
            assertEquals("not your priority", (failure as GameActionFailure).reason)
            assertTrue("a plain decline is not a session loss", failure !is GameSessionGoneFailure)
        }

    @Test
    fun aSessionGoneDeclineSurfacesAsItsOwnFailureSoTheCallerCanReAuthenticate() =
        runTest {
            // the distinction, on the game verbs: the request never reached the server, so
            // retrying cannot help and only re-authenticating can. A caller that cannot tell the two
            // apart shows the user "the server declined" for a session that simply expired.
            val client =
                client {
                    GameActionResult(
                        action = GameActionCode.JOIN_GAME,
                        ok = false,
                        reason = "no session is bound to this socket",
                        failure = GameFailureCode.SESSION_GONE,
                    )
                }

            val failure = client.joinGame(GAME).exceptionOrNull()

            assertTrue("SESSION_GONE must be its own type, got $failure", failure is GameSessionGoneFailure)
            assertEquals("no session is bound to this socket", (failure as GameSessionGoneFailure).reason)
        }

    @Test
    fun aDeclineWithNoFailureCodeStaysAPlainDecline() =
        runTest {
            // An older bridge does not send the field at all; that must not be read as a session loss.
            val client = client { GameActionResult(action = GameActionCode.QUIT_MATCH, ok = false, reason = null) }

            val failure = client.quitMatch(GAME).exceptionOrNull()

            assertTrue(failure is GameActionFailure)
            assertTrue(failure !is GameSessionGoneFailure)
        }

    @Test
    fun anUnexpectedReplyIsAFailedResultRatherThanACrash() =
        runTest {
            val client = client { TableActionResult(action = TableActionCode.CREATE, ok = true) }

            val failure = client.passPriority(GAME).exceptionOrNull()

            assertTrue(failure is GameActionFailure)
            assertTrue(
                "the failure should name what actually arrived, got '${failure?.message}'",
                failure?.message?.contains("TableActionResult") == true,
            )
        }

    @Test
    fun aTransportFailureIsCapturedAsAFailedResultNotAThrow() =
        runTest {
            val client = client { error("no active session") }

            val result = client.joinGame(GAME)

            assertTrue("the client must never throw at its caller", result.isFailure)
            assertEquals("no active session", result.exceptionOrNull()?.message)
        }

    // --- the targeted read ---------------------------------------------------------------

    @Test
    fun refreshGameSendsGetGameStateAndMapsTheSnapshotOntoAppSchemaState() =
        runTest {
            val view =
                GameStateView(
                    turn = 4,
                    phase = TurnPhaseCode.PRECOMBAT_MAIN,
                    step = PhaseStepCode.PRECOMBAT_MAIN,
                    activePlayerId = "p-1",
                    activePlayerName = "pete",
                    viewerPlayerId = "p-1",
                    viewerHasPriority = true,
                    players = listOf(GamePlayerView(playerId = "p-1", name = "pete", life = 18, viewer = true)),
                    hand = listOf(GameCardView(id = "c-1", name = "Forest", setCode = "M21", collectorNumber = "272")),
                    playable = listOf(GamePlayableObject(objectId = "c-1", abilityIds = listOf("a-1"))),
                )
            val client =
                client {
                    GameStateSnapshot(gameId = GAME, state = view, capturedAtEpochMs = 1_700L, requestId = REQUEST_ID)
                }

            val snapshot = client.refreshGame(GAME).getOrThrow()

            assertEquals(GetGameState(gameId = GAME, requestId = REQUEST_ID), sent.single())
            assertEquals(1_700L, snapshot.capturedAtEpochMs)
            assertEquals(4, snapshot.state.turn)
            val seat = snapshot.state.players.first()
            assertEquals(18, seat.life)
            assertEquals(listOf("c-1"), snapshot.state.hand.map { it.id })
            assertTrue("the server's own canPlayObjects must survive the read", snapshot.state.isPlayable("c-1"))
            assertTrue("a read that produced a state must say so", snapshot.state.hasSnapshot)
            assertEquals("a read carries a GameView, so it never carries a question", null, snapshot.state.prompt)
        }

    @Test
    fun refreshGameSurfacesNoStateAsATypedFailureRatherThanAnEmptyBoard() =
        runTest {
            // The acceptance criterion, on the client side: an all-defaults GameState is a legal board,
            // so "nothing to show" has to be a different *shape* from "here is the board" or every
            // caller renders the absence as truth.
            val client =
                client {
                    GameStateUnavailable(
                        gameId = GAME,
                        reason = GameStateUnavailableCode.NO_STATE_YET,
                        detail = "no snapshot for this game on this session yet",
                        requestId = REQUEST_ID,
                    )
                }

            val failure = client.refreshGame(GAME).exceptionOrNull()

            assertTrue("expected a typed unavailability, got $failure", failure is GameStateUnavailableFailure)
            val unavailable = failure as GameStateUnavailableFailure
            assertEquals(GAME, unavailable.gameId)
            assertEquals(GameStateUnavailableReason.NoStateYet, unavailable.reason)
            assertEquals("no snapshot for this game on this session yet", unavailable.detail)
        }

    @Test
    fun refreshGameDistinguishesALostSessionFromAGameWithNoStateYet() =
        runTest {
            // the distinction on the read side: "wait, nothing yet" and "you are signed out"
            // demand opposite responses, and prose cannot be branched on.
            val client =
                client {
                    GameStateUnavailable(
                        gameId = GAME,
                        reason = GameStateUnavailableCode.SESSION_GONE,
                        detail = "no active session on this socket",
                        requestId = REQUEST_ID,
                    )
                }

            val failure = client.refreshGame(GAME).exceptionOrNull() as? GameStateUnavailableFailure

            assertEquals(GameStateUnavailableReason.SessionGone, failure?.reason)
        }

    @Test
    fun refreshGameCapturesATransportFailureRatherThanThrowing() =
        runTest {
            val client = client { error("no active session") }

            val result = client.refreshGame(GAME)

            assertTrue("the client must never throw at its caller", result.isFailure)
            assertEquals("no active session", result.exceptionOrNull()?.message)
        }

    private companion object {
        const val GAME = "g-1"
        const val REQUEST_ID = "req-fixed"
    }
}
