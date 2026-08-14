package magefree.feature.game.board

import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import magefree.network.fake.FakeGameClient
import magefree.network.game.AbilityChoice
import magefree.network.game.ChoiceOption
import magefree.network.game.GameActionFailure
import magefree.network.game.GameCard
import magefree.network.game.GamePermanent
import magefree.network.game.GamePlayer
import magefree.network.game.GamePrompt
import magefree.network.game.GameState
import magefree.network.game.ManaPool
import magefree.network.game.ManaType
import magefree.network.game.MultiAmountEntry
import magefree.network.game.PlayableObject
import magefree.network.game.PromptOptions
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Hermetic coverage of [GameBoardViewModel] over 0052's [FakeGameClient] — no bridge, no `:protocol`.
 *
 * The properties that are about *production behaviour* rather than about projection:
 *
 * 1. **the subscription is opened before the join** — the push side-channel is replay-less, so a client
 *    that joins first can miss `GAME_INIT` entirely (0052's live test records the same ordering);
 * 2. **every prompt kind is answerable, through one seam** — [GameBoardViewModel.act] is the only
 *    translator from a gesture to a client verb, and the assertions are on the fake's recorded call
 *    list, so a wrong verb or a silently-dropped action would have to show up there to ship;
 * 3. **target picks are sent one at a time** (§17.2) — the server re-prompts with an updated count and
 *    a narrowed candidate set after each pick, so a client that held picks locally could assemble a
 *    combination the server rejects. The player's confirmation is the final *done*;
 * 4. **priority is passed through one policy seam** (§14.1) — proven live in both directions: the
 *    shipped manual policy never passes on its own, and a policy that says to pass makes the app pass
 *    with no UI interaction at all, which is what makes the auto-pass hook real rather than decorative;
 * 5. **the visibility toggle never hides that the server is waiting** (§16.3).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GameBoardViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ---- joining and observing (story 0055) ---------------------------------------------------------

    @Test
    fun `opens the subscription before joining, so the first snapshot cannot be missed`() =
        runTest {
            val client = FakeGameClient()

            viewModel(client).observe(GAME_ID)

            // `observeGame` is not itself recorded, so the proof is the *seed*: the fake emits it as the
            // flow opens, exactly as production does. If it has been seen, the collection was already
            // running when the join went out.
            assertEquals(listOf("join:$GAME_ID"), client.calls)
        }

    @Test
    fun `renders the seed first, as an honest nothing-has-arrived-yet board`() =
        runTest {
            val viewModel = viewModel(FakeGameClient())

            viewModel.uiState.test {
                viewModel.observe(GAME_ID)
                // The starting value, then the seed-derived board.
                skipItems(1)
                val seeded = awaitItem()

                assertEquals(GAME_ID, seeded.board.gameId)
                assertFalse("the seed is not a snapshot", seeded.board.hasSnapshot)
                assertTrue(seeded.board.hand.isEmpty)
                assertTrue(seeded.board.stack.isEmpty)
                assertNull(seeded.board.viewerSeat)
                assertNull("no prompt means no controls", seeded.controls)
                assertEquals(PriorityUi.NotStarted, seeded.board.priority)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `projects each pushed snapshot onto the board`() =
        runTest {
            val client = FakeGameClient()
            val viewModel = viewModel(client)
            viewModel.observe(GAME_ID)

            client.emitGameState(dealtState())

            val board = viewModel.uiState.value.board
            assertTrue(board.hasSnapshot)
            assertEquals(listOf("Forest", "Forest"), board.hand.cards.map { it.card.name })
            assertEquals("you", board.viewerSeat?.name)
            assertEquals(listOf("Computer"), board.opponentSeats.map { it.name })
        }

    @Test
    fun `a later snapshot replaces the previous one rather than merging with it`() =
        runTest {
            // State arrives as whole snapshots, never deltas (0052), so a card the server stops sending
            // must disappear. A board that merged would keep showing a permanent that has been destroyed.
            val client = FakeGameClient()
            val viewModel = viewModel(client)
            viewModel.observe(GAME_ID)

            client.emitGameState(dealtState())
            client.emitGameState(dealtState().copy(hand = emptyList()))

            assertTrue("the hand the earlier snapshot carried must be gone", viewModel.uiState.value.board.hand.isEmpty)
        }

    @Test
    fun `surfaces the server's own reason when the join is declined`() =
        runTest {
            val client = FakeGameClient(actionResult = Result.failure(GameActionFailure("you are not in this match")))

            val viewModel = viewModel(client)
            viewModel.observe(GAME_ID)

            assertEquals("you are not in this match", viewModel.uiState.value.joinError)
            assertFalse(viewModel.uiState.value.isJoining)
        }

    @Test
    fun `clears the joining flag once the server accepts`() =
        runTest {
            val viewModel = viewModel(FakeGameClient())

            viewModel.observe(GAME_ID)

            assertFalse(viewModel.uiState.value.isJoining)
            assertNull(viewModel.uiState.value.joinError)
        }

    @Test
    fun `observing twice does not open a second subscription or re-join`() =
        runTest {
            val client = FakeGameClient()
            val viewModel = viewModel(client)

            viewModel.observe(GAME_ID)
            viewModel.observe(GAME_ID)

            assertEquals("a recomposition must not re-join the game", listOf("join:$GAME_ID"), client.calls)
        }

    @Test
    fun `sends nothing at all while the server is not asking`() =
        runTest {
            // The board answers questions; it does not volunteer. With no prompt outstanding, no
            // snapshot — however it moves — may produce a call.
            val client = FakeGameClient()
            val viewModel = viewModel(client)
            viewModel.observe(GAME_ID)

            client.emitGameState(dealtState())
            client.emitGameState(dealtState().copy(viewerHasPriority = true, playable = listOf(PlayableObject("h-1"))))
            viewModel.setHandExpanded(true)
            viewModel.setControlsVisible(false)
            viewModel.selectCard("h-1")

            assertEquals(listOf("join:$GAME_ID"), client.calls)
        }

    // ---- one seam, every prompt kind ------------------------------------------------------------------

    @Test
    fun `answers every prompt kind through the single act seam`() =
        runTest {
            val client = FakeGameClient()
            val viewModel = viewModel(client)
            viewModel.observe(GAME_ID)
            client.emitGameState(dealtState())
            client.calls.clear()

            // One row per prompt kind that has an answering method. `Unrecognised` is deliberately absent
            // — it has none, and its own test below proves the board offers no way to try.
            listOf(
                BoardAction.PlayObject("h-1") to "play:$GAME_ID:h-1",
                BoardAction.PassPriority to "pass:$GAME_ID",
                BoardAction.UseSpecial to "special:$GAME_ID",
                BoardAction.ChooseTarget("o-1") to "target:$GAME_ID:o-1",
                BoardAction.FinishTargeting to "cancel:$GAME_ID",
                BoardAction.CancelPrompt to "cancel:$GAME_ID",
                BoardAction.AnswerAsk(yes = true) to "ask:$GAME_ID:true",
                BoardAction.ChooseAbility("a-1") to "ability:$GAME_ID:a-1",
                BoardAction.ChoosePile(first = false) to "pile:$GAME_ID:false",
                BoardAction.ChooseChoice("R", special = true) to "choice:$GAME_ID:R:true",
                BoardAction.PlayManaSource("y-1") to "mana:$GAME_ID:y-1",
                BoardAction.UnlockMana(ManaType.Red) to "unlockMana:$GAME_ID:p-you:Red",
                BoardAction.AnnounceX(3) to "x:$GAME_ID:3",
                BoardAction.ChooseAmount(2) to "amount:$GAME_ID:2",
                BoardAction.DistributeAmounts(listOf(1, 1)) to "multiAmount:$GAME_ID:1,1",
                BoardAction.Concede to "concede:$GAME_ID",
                BoardAction.QuitMatch to "quit:$GAME_ID",
            ).forEach { (action, expected) ->
                client.calls.clear()
                viewModel.act(action)
                assertEquals("$action must reach the server as one call", listOf(expected), client.calls)
            }
        }

    @Test
    fun `surfaces the server's own reason when it declines an action`() =
        runTest {
            val client = FakeGameClient()
            val viewModel = viewModel(client)
            viewModel.observe(GAME_ID)
            client.actionResult = Result.failure(GameActionFailure("you can't play that now"))

            viewModel.act(BoardAction.PlayObject("h-1"))

            assertEquals("you can't play that now", viewModel.uiState.value.actionError)
        }

    @Test
    fun `an unrecognised prompt produces a notice with nothing to press`() =
        runTest {
            val client = FakeGameClient()
            val viewModel = viewModel(client)
            viewModel.observe(GAME_ID)
            client.calls.clear()

            client.emitGameState(dealtState().copy(prompt = GamePrompt.Unrecognised(type = "GAME_NEW_THING")))

            val controls = viewModel.uiState.value.controls
            assertTrue(controls is PromptControlsUi.Notice)
            assertFalse(controls!!.isAnswerable)
            assertEquals("no answer may be invented for it", emptyList<String>(), client.calls)
        }

    // ---- targeting: per pick, then the player's own done ---------------------------------------------

    @Test
    fun `sends each target pick as it is made and never batches them`() =
        runTest {
            // §17.2, verified live. Two picks must arrive as two calls, in order, *before* the done —
            // a client that held them and flushed on confirm would produce a different sequence.
            val client = FakeGameClient()
            val viewModel = viewModel(client)
            viewModel.observe(GAME_ID)
            client.emitGameState(targetPromptState(candidates = listOf("o-1", "y-1")))
            client.calls.clear()

            viewModel.act(BoardAction.ChooseTarget("o-1"))
            // **The assertion that separates per-pick from batch-then-flush**, and the reason it is made
            // here rather than only at the end: a client that held the picks locally and sent them on
            // confirm produces the *same final sequence*. What it cannot produce is the pick already
            // being at the server before the player has confirmed anything — which is exactly the
            // property §17.2 requires, because the server's next prompt is computed from it.
            assertEquals(
                "each pick must reach the server as it is made, before any confirmation",
                listOf("target:$GAME_ID:o-1"),
                client.calls,
            )

            // The server re-prompts with an updated count after the first pick; the second is chosen
            // against *that* list.
            client.emitGameState(targetPromptState(candidates = listOf("y-1")))
            viewModel.act(BoardAction.ChooseTarget("y-1"))
            assertEquals(
                "the second pick too — it is chosen against the narrowed list the server just sent",
                listOf("target:$GAME_ID:o-1", "target:$GAME_ID:y-1"),
                client.calls,
            )

            viewModel.act(BoardAction.FinishTargeting)
            assertEquals(
                listOf("target:$GAME_ID:o-1", "target:$GAME_ID:y-1", "cancel:$GAME_ID"),
                client.calls,
            )
        }

    @Test
    fun `the closing button becomes a done as soon as a pick has been sent`() =
        runTest {
            val client = FakeGameClient()
            val viewModel = viewModel(client)
            viewModel.observe(GAME_ID)
            client.emitGameState(targetPromptState(candidates = listOf("o-1", "y-1")))

            assertFalse((viewModel.uiState.value.controls as PromptControlsUi.Targeting).hasPicked)

            viewModel.act(BoardAction.ChooseTarget("o-1"))
            assertTrue(
                "after a pick, the confirmation is the final done",
                (viewModel.uiState.value.controls as PromptControlsUi.Targeting).hasPicked,
            )

            // A new target sequence starts clean: the record is of what *this app* sent, not of the game.
            client.emitGameState(dealtState().copy(prompt = GamePrompt.Select(message = "Select")))
            client.emitGameState(targetPromptState(candidates = listOf("o-1")))
            assertFalse((viewModel.uiState.value.controls as PromptControlsUi.Targeting).hasPicked)
        }

    // ---- cancel: every step the server permits --------------------------------------------------------

    @Test
    fun `cancels at the target step, where the rewind is proven`() =
        runTest {
            // §17.1: declining the target prompt returned the Bolt to hand, cleared the stack and left
            // both Mountains untapped.
            val client = FakeGameClient()
            val viewModel = viewModel(client)
            viewModel.observe(GAME_ID)
            client.emitGameState(targetPromptState(candidates = listOf("o-1")))
            client.calls.clear()

            viewModel.act(BoardAction.CancelPrompt)

            assertEquals(listOf("cancel:$GAME_ID"), client.calls)
        }

    @Test
    fun `cancels at the mana step, where the rewind was first proven`() =
        runTest {
            // §6.4a: declining the mana step returned the Hawk to hand (7), cleared the stack and left
            // both Plains untapped.
            val client = FakeGameClient()
            val viewModel = viewModel(client)
            viewModel.observe(GAME_ID)
            client.emitGameState(dealtState().copy(prompt = GamePrompt.PlayMana(message = "Pay {1}{W}")))
            client.calls.clear()

            viewModel.act(BoardAction.CancelPrompt)

            assertEquals(listOf("cancel:$GAME_ID"), client.calls)
        }

    @Test
    fun `cancels at the mode step and at the X announcement, the other two the client can decline`() =
        runTest {
            val client = FakeGameClient()
            val viewModel = viewModel(client)
            viewModel.observe(GAME_ID)

            client.emitGameState(
                dealtState().copy(
                    prompt = GamePrompt.ChooseAbility(message = "Choose a mode", choices = listOf(AbilityChoice("a-1", "Mode one"))),
                ),
            )
            client.calls.clear()
            viewModel.act(BoardAction.CancelPrompt)
            assertEquals(listOf("cancel:$GAME_ID"), client.calls)

            client.emitGameState(dealtState().copy(prompt = GamePrompt.PlayXMana(message = "Announce X")))
            client.calls.clear()
            viewModel.act(BoardAction.CancelPrompt)
            assertEquals(listOf("cancel:$GAME_ID"), client.calls)
        }

    @Test
    fun `a cancel says the server is rewinding, and never that the spell was not cast`() =
        runTest {
            // `playObject` puts the spell on the stack immediately (CR 601 / §16.5) and the rewind is the
            // server's, arriving on a later snapshot. The board must not claim otherwise in between.
            val client = FakeGameClient()
            val viewModel = viewModel(client)
            viewModel.observe(GAME_ID)
            client.emitGameState(selectState())

            viewModel.act(BoardAction.PlayObject("h-1"))
            client.emitGameState(dealtState().copy(prompt = GamePrompt.PlayMana(message = "Pay {1}{W}")))
            assertEquals(CastUi(cardName = "Forest", stepLabel = CAST_STEP_MANA), viewModel.uiState.value.cast)

            viewModel.act(BoardAction.CancelPrompt)
            val cancelling = viewModel.uiState.value.cast
            assertTrue("the cast context states the rewind is in progress", cancelling!!.isCancelling)

            // The server rewinds and hands priority back; the act is over.
            client.emitGameState(selectState())
            assertNull("the cast context ends when priority returns", viewModel.uiState.value.cast)
        }

    @Test
    fun `the cast context follows the server's questions, one continuous act`() =
        runTest {
            // §6.4: the spell must not vanish behind each new question.
            val client = FakeGameClient()
            val viewModel = viewModel(client)
            viewModel.observe(GAME_ID)
            client.emitGameState(selectState())

            viewModel.act(BoardAction.PlayObject("h-1"))
            assertEquals(CastUi(cardName = "Forest"), viewModel.uiState.value.cast)

            client.emitGameState(targetPromptState(candidates = listOf("o-1")))
            val choosingTargets = viewModel.uiState.value.cast
            assertEquals(CAST_STEP_TARGETS, choosingTargets?.stepLabel)

            client.emitGameState(dealtState().copy(prompt = GamePrompt.PlayMana(message = "Pay {1}{W}")))
            val payingMana = viewModel.uiState.value.cast
            assertEquals(CAST_STEP_MANA, payingMana?.stepLabel)

            client.emitGameState(selectState())
            assertNull(viewModel.uiState.value.cast)
        }

    // ---- the pass seam ---------------------------------------------------------------------------------

    @Test
    fun `the shipped policy never passes on its own, however many times the server asks`() =
        runTest {
            // §9.1/§14.1: everything explicit and manual. A board that passed by itself would be playing
            // the game for the player.
            val client = FakeGameClient()
            val viewModel = viewModel(client, ManualPassPolicy)
            viewModel.observe(GAME_ID)
            client.calls.clear()

            client.emitGameState(selectState())
            client.emitGameState(selectState())

            assertEquals(emptyList<String>(), client.calls)
        }

    @Test
    fun `the player's pass goes through the same single seam`() =
        runTest {
            val client = FakeGameClient()
            val viewModel = viewModel(client)
            viewModel.observe(GAME_ID)
            client.emitGameState(selectState())
            client.calls.clear()

            viewModel.act(BoardAction.PassPriority)

            assertEquals(listOf("pass:$GAME_ID"), client.calls)
        }

    @Test
    fun `a policy that passes answers the select with no UI interaction at all`() =
        runTest {
            // The proof that the auto-pass hook of §14.1 is load-bearing rather than decorative: swapping
            // the policy — the single thing `BoardModule` binds — changes *when* the app answers, and
            // nothing else has to change.
            val client = FakeGameClient()
            val viewModel = viewModel(client, PassPolicy { PassDecision.PassImmediately })
            viewModel.observe(GAME_ID)
            client.calls.clear()

            client.emitGameState(selectState())

            assertEquals(listOf("pass:$GAME_ID"), client.calls)
        }

    @Test
    fun `the policy is asked once per question, never twice for the same one`() =
        runTest {
            val client = FakeGameClient()
            val asked = mutableListOf<GameState>()
            val viewModel =
                viewModel(
                    client,
                    PassPolicy { state ->
                        asked += state
                        PassDecision.AskThePlayer
                    },
                )
            viewModel.observe(GAME_ID)
            val state = selectState()

            client.emitGameState(state)
            client.emitGameState(state)

            assertEquals("a re-emitted snapshot is not a new question", 1, asked.size)
        }

    @Test
    fun `the policy is not consulted for prompts a pass cannot answer`() =
        runTest {
            val client = FakeGameClient()
            var consulted = 0
            val viewModel =
                viewModel(
                    client,
                    PassPolicy {
                        consulted++
                        PassDecision.PassImmediately
                    },
                )
            viewModel.observe(GAME_ID)

            client.emitGameState(dealtState().copy(prompt = GamePrompt.Ask(message = "Mulligan?")))
            client.emitGameState(targetPromptState(candidates = listOf("o-1")))
            client.emitGameState(dealtState().copy(prompt = GamePrompt.PlayMana(message = "Pay")))

            assertEquals("a pass is only ever an answer to a select", 0, consulted)
            assertEquals(listOf("join:$GAME_ID"), client.calls)
        }

    // ---- view state: the toggle, the hand and the tapped card -----------------------------------------

    @Test
    fun `hiding the controls never hides that the server is waiting on you`() =
        runTest {
            // §16.3's hard constraint. The priority statement belongs to the board, not to the controls,
            // so it cannot be taken away by the toggle — including in the state 0055 added `Asked` for:
            // the server asking one seat to choose who goes first, *before* priority exists.
            val client = FakeGameClient()
            val viewModel = viewModel(client)
            viewModel.observe(GAME_ID)
            client.emitGameState(
                dealtState().copy(
                    viewerHasPriority = false,
                    prompt = GamePrompt.Target(message = "Select a starting player", isRequired = true),
                ),
            )

            viewModel.setControlsVisible(false)

            val hidden = viewModel.uiState.value
            assertFalse(hidden.areControlsVisible)
            assertEquals(PriorityUi.Asked, hidden.board.priority)
            assertEquals("The server is waiting on you", hidden.board.priority.headline)
            assertNotNull("the question itself is still known, so it can be shown again", hidden.controls)
        }

    @Test
    fun `hiding the controls keeps the priority statement while the viewer holds priority too`() =
        runTest {
            val client = FakeGameClient()
            val viewModel = viewModel(client)
            viewModel.observe(GAME_ID)
            client.emitGameState(selectState())

            viewModel.setControlsVisible(false)

            assertEquals(PriorityUi.Yours(anythingOffered = true), viewModel.uiState.value.board.priority)
        }

    @Test
    fun `showing and hiding the controls sends the server nothing`() =
        runTest {
            val client = FakeGameClient()
            val viewModel = viewModel(client)
            viewModel.observe(GAME_ID)
            client.emitGameState(selectState())

            viewModel.setControlsVisible(false)
            viewModel.setControlsVisible(true)

            assertEquals("looking at the board is not a game action", listOf("join:$GAME_ID"), client.calls)
        }

    @Test
    fun `expanding the hand is a view change and sends the server nothing`() =
        runTest {
            val client = FakeGameClient()
            val viewModel = viewModel(client)
            viewModel.observe(GAME_ID)

            viewModel.setHandExpanded(true)
            assertTrue(viewModel.uiState.value.isHandExpanded)
            viewModel.setHandExpanded(false)
            assertFalse(viewModel.uiState.value.isHandExpanded)

            assertEquals("looking at your own hand is not a game action", listOf("join:$GAME_ID"), client.calls)
        }

    @Test
    fun `tapping a card raises it and sends nothing, and tapping it again puts it down`() =
        runTest {
            // §5.1: the first tap raises the card and shows its detail. Committing is the *second*
            // gesture, on the detail's own button — which is what guards against a misplay.
            val client = FakeGameClient()
            val viewModel = viewModel(client)
            viewModel.observe(GAME_ID)
            client.emitGameState(selectState())

            viewModel.selectCard("h-1")
            assertEquals("h-1", viewModel.uiState.value.selectedObjectId)
            assertEquals(listOf("join:$GAME_ID"), client.calls)

            viewModel.selectCard("h-1")
            assertNull(viewModel.uiState.value.selectedObjectId)
        }

    @Test
    fun `a new question closes a detail opened against the old one`() =
        runTest {
            val client = FakeGameClient()
            val viewModel = viewModel(client)
            viewModel.observe(GAME_ID)
            client.emitGameState(selectState())
            viewModel.selectCard("h-1")

            client.emitGameState(dealtState().copy(prompt = GamePrompt.Ask(message = "Mulligan?")))

            assertNull("a detail must not outlive the question it was opened against", viewModel.uiState.value.selectedObjectId)
        }

    @Test
    fun `mana can only be spent from a seat we actually have`() =
        runTest {
            // A spectator has no seat (`viewerPlayerId` is null upstream), and upstream takes the pool's
            // owner explicitly — so there is nothing honest to send.
            val client = FakeGameClient()
            val viewModel = viewModel(client)
            viewModel.observe(GAME_ID)
            client.emitGameState(dealtState().copy(viewerPlayerId = null))
            client.calls.clear()

            viewModel.act(BoardAction.UnlockMana(ManaType.Red))

            assertEquals(emptyList<String>(), client.calls)
            assertEquals(NO_SEAT_FOR_MANA, viewModel.uiState.value.actionError)
        }

    @Test
    fun `a multi-amount answer keeps the server's row order`() =
        runTest {
            // Upstream parses the reply positionally, so a reordered list is a wrong answer.
            val client = FakeGameClient()
            val viewModel = viewModel(client)
            viewModel.observe(GAME_ID)
            client.emitGameState(
                dealtState().copy(
                    prompt =
                        GamePrompt.GetMultiAmount(
                            message = "Divide 2 damage",
                            entries = listOf(MultiAmountEntry("Goblin A", max = 2), MultiAmountEntry("Goblin B", max = 2)),
                        ),
                ),
            )
            client.calls.clear()

            viewModel.act(BoardAction.DistributeAmounts(listOf(2, 0)))

            assertEquals(listOf("multiAmount:$GAME_ID:2,0"), client.calls)
        }

    @Test
    fun `a choose-choice keeps the server's key and its special arm apart`() =
        runTest {
            val client = FakeGameClient()
            val viewModel = viewModel(client)
            viewModel.observe(GAME_ID)
            client.emitGameState(
                dealtState().copy(
                    prompt = GamePrompt.ChooseChoice(message = "Choose a colour", choices = listOf(ChoiceOption("R", "Red"))),
                ),
            )
            client.calls.clear()

            viewModel.act(BoardAction.ChooseChoice("R"))
            viewModel.act(BoardAction.ChooseChoice("R", special = true))

            assertEquals(listOf("choice:$GAME_ID:R:false", "choice:$GAME_ID:R:true"), client.calls)
        }

    // ---- fixtures ----------------------------------------------------------------------------------

    private fun viewModel(
        client: FakeGameClient,
        policy: PassPolicy = ManualPassPolicy,
    ) = GameBoardViewModel(client, policy)

    private fun forest(id: String) = GameCard(id = id, name = "Forest", setCode = "M21", collectorNumber = "272")

    private fun dealtState() =
        GameState(
            gameId = GAME_ID,
            turn = 1,
            hasSnapshot = true,
            viewerPlayerId = "p-you",
            activePlayerId = "p-you",
            players =
                listOf(
                    GamePlayer(
                        playerId = "p-opp",
                        name = "Computer",
                        life = 20,
                        isHuman = false,
                        battlefield = listOf(GamePermanent(card = forest("o-1"))),
                    ),
                    GamePlayer(
                        playerId = "p-you",
                        name = "you",
                        life = 20,
                        isViewer = true,
                        manaPool = ManaPool(red = 1),
                        battlefield = listOf(GamePermanent(card = forest("y-1"))),
                    ),
                ),
            hand = listOf(forest("h-1"), forest("h-2")),
        )

    /** A priority window: the server is asking, and it has offered something. */
    private fun selectState() =
        dealtState().copy(
            viewerHasPriority = true,
            playable = listOf(PlayableObject("h-1"), PlayableObject("y-1")),
            prompt = GamePrompt.Select(message = "Select an ability to play"),
        )

    /** A target prompt offering [candidates], as the server sends them. */
    private fun targetPromptState(candidates: List<String>) =
        dealtState().copy(
            prompt =
                GamePrompt.Target(
                    message = "Select targets (selected 0 of 2, min 1) to divide 2 damage",
                    isRequired = false,
                    options = PromptOptions(ids = mapOf(PromptOptions.POSSIBLE_TARGETS to candidates)),
                ),
        )

    private companion object {
        const val GAME_ID = "g-1"
    }
}
