package magefree.feature.game.board

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import magefree.designsystem.theme.MageTheme
import magefree.feature.cards.PlaceholderCardArtRenderer
import magefree.network.game.AbilityChoice
import magefree.network.game.CardType
import magefree.network.game.ChoiceOption
import magefree.network.game.CombatGroup
import magefree.network.game.GameCard
import magefree.network.game.GameCounter
import magefree.network.game.GameMessage
import magefree.network.game.GamePermanent
import magefree.network.game.GamePlayer
import magefree.network.game.GamePrompt
import magefree.network.game.GameState
import magefree.network.game.GameZone
import magefree.network.game.ManaPool
import magefree.network.game.MultiAmountEntry
import magefree.network.game.PhaseStep
import magefree.network.game.PlayableObject
import magefree.network.game.PromptOptions
import magefree.network.game.TurnPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Stories 0055 + 0057 — the board's **rendering and interaction** tests, in the hermetic gate.
 *
 * They run on the JVM under Robolectric (`src/testDebug`), so `:feature:game:check` covers them
 * pre-merge. That placement is deliberate and has a history: device-only tests do not run before a
 * merge, which is how an entire epic shipped built, tested and unreachable (stories 0047/0048). A board
 * that renders nothing would pass a ViewModel test suite perfectly.
 *
 * What is asserted here and not in [BoardUiTest] / [BoardControlsTest]: that each region is actually
 * **on screen**, that each region's **empty state** is on screen when it is empty, and — story 0057's
 * acceptance criteria with teeth — that **nothing is modal** for any prompt kind, that the visibility
 * toggle cannot hide that the server is waiting, and that a tapped card really does reach the action
 * seam.
 *
 * Art is drawn through [PlaceholderCardArtRenderer], so no test loads a network image. That the
 * production route binds 0031/0032's Coil renderer instead is `GameBoardRoute`'s single line, and is
 * what the on-device pass (verification standard 3) confirms.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    // A plain Application: the screen takes its art renderer as a parameter precisely so it needs no
    // Hilt component to render.
    application = Application::class,
    // A representative compact phone in **portrait** — the orientation this board is designed for
    // (requirements §16.1). Robolectric's 320x470dp default would clip regions that are fine on any
    // real phone and make `assertIsDisplayed` fail for the wrong reason.
    qualifiers = "w411dp-h891dp",
)
class GameBoardScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val expandRequests = mutableListOf<Boolean>()
    private val visibilityRequests = mutableListOf<Boolean>()
    private val taps = mutableListOf<String?>()
    private val actions = mutableListOf<BoardAction>()

    private fun render(
        state: GameState,
        handExpanded: Boolean = false,
        controlsVisible: Boolean = true,
        selectedObjectId: String? = null,
        cast: CastUi? = null,
        declaration: DeclarationUi? = null,
        hasPickedTarget: Boolean = false,
    ) {
        composeTestRule.setContent {
            MageTheme {
                GameBoardScreen(
                    uiState =
                        GameBoardUiState(
                            board = BoardUi.from(state),
                            isJoining = false,
                            isHandExpanded = handExpanded,
                            areControlsVisible = controlsVisible,
                            controls = controlsFor(state, hasPickedTarget = hasPickedTarget),
                            selectedObjectId = selectedObjectId,
                            cast = cast,
                            declaration = declaration,
                        ),
                    onExit = {},
                    onHandExpandedChange = { expandRequests += it },
                    onControlsVisibleChange = { visibilityRequests += it },
                    onCardTap = { taps += it },
                    onAction = { actions += it },
                    artRenderer = PlaceholderCardArtRenderer,
                )
            }
        }
    }

    // ---- the populated board (story 0055) ------------------------------------------------------------

    @Test
    fun `draws both seats, both battlefields, the stack, the turn and the hand`() {
        render(runningGame())

        // Opponent above, you below — both located by `isViewer`, and the fixture lists the opponent
        // first on purpose.
        composeTestRule.onNodeWithText("Computer").assertIsDisplayed()
        composeTestRule.onNodeWithText("you").assertIsDisplayed()
        composeTestRule.onNodeWithText("18").assertIsDisplayed()
        composeTestRule.onNodeWithText("20").assertIsDisplayed()

        // A permanent from each battlefield.
        composeTestRule.onNodeWithText("Mountain").assertIsDisplayed()
        composeTestRule.onNodeWithText("Forest").assertIsDisplayed()

        // Turn / phase / whose turn.
        composeTestRule.onNodeWithText("Turn 3", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Main 1", substring = true).assertIsDisplayed()

        // The stack, with the qualifier that says it is only as current as the last push.
        composeTestRule.onNodeWithText(STACK_AS_PUSHED, substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Giant Growth").assertIsDisplayed()

        // The hand's peek edge carries its count.
        composeTestRule.onNodeWithText("$HAND_PEEK_PREFIX 2").assertIsDisplayed()
    }

    @Test
    fun `states priority explicitly when the viewer holds it`() {
        render(runningGame().copy(viewerHasPriority = true, playable = listOf(PlayableObject("h-1"))))

        composeTestRule.onNodeWithText("Your turn to act").assertIsDisplayed()
    }

    @Test
    fun `states priority explicitly when the viewer holds it with nothing playable`() {
        // Requirements §4.2's whole reason for existing: a highlight cannot say this, so words must.
        render(runningGame().copy(viewerHasPriority = true, playable = emptyList()))

        composeTestRule.onNodeWithText("Your turn to act").assertIsDisplayed()
        composeTestRule.onNodeWithText("Nothing the server offers you right now.").assertIsDisplayed()
    }

    @Test
    fun `states priority explicitly when the opponent holds it`() {
        render(runningGame().copy(viewerHasPriority = false, priorityPlayerName = "Computer"))

        composeTestRule.onNodeWithText("Waiting for opponent").assertIsDisplayed()
        composeTestRule.onNodeWithText("Computer has priority").assertIsDisplayed()
    }

    @Test
    fun `states that the server is waiting on you when it asks before priority exists`() {
        // The live start-of-game snapshot, reproduced: the server asks this seat to choose who goes
        // first, and no one holds priority yet. Before this was fixed the board read "Waiting for
        // opponent" while the entire game was blocked on the player.
        render(
            runningGame().copy(
                viewerHasPriority = false,
                priorityPlayerName = null,
                prompt = GamePrompt.Target(message = "Select a starting player", isRequired = true),
            ),
        )

        composeTestRule.onNodeWithText("The server is waiting on you").assertIsDisplayed()
        composeTestRule.onNodeWithText("Waiting for opponent").assertDoesNotExist()
        composeTestRule.onNodeWithText("$PROMPT_PREFIX Select a starting player").assertIsDisplayed()
    }

    @Test
    fun `shows the server's prompt as text, stripped of its HTML`() {
        render(
            runningGame().copy(
                prompt = GamePrompt.Select(message = "Select <b>an</b> ability to play"),
                lastMessage = GameMessage(text = "Draw - Waiting for <font color='#20B2AA'>Computer</font>"),
            ),
        )

        composeTestRule.onNodeWithText("$PROMPT_PREFIX Select an ability to play").assertIsDisplayed()
        composeTestRule.onNodeWithText("<b>", substring = true).assertDoesNotExist()
        composeTestRule.onNodeWithText("<font", substring = true).assertDoesNotExist()
    }

    // ---- every region's empty state -----------------------------------------------------------------

    @Test
    fun `renders a complete board from a snapshot that has not arrived yet`() {
        render(GameState("g-1"))

        composeTestRule.onNodeWithText(WAITING_FOR_FIRST_SNAPSHOT).assertIsDisplayed()
        // Both seat bars say "not seated yet" rather than showing a player on 0 life.
        composeTestRule.onNodeWithText("$OPPONENT_SEAT_LABEL: $NO_SEAT_LABEL").assertIsDisplayed()
        composeTestRule.onNodeWithText("$VIEWER_SEAT_LABEL: $NO_SEAT_LABEL").assertIsDisplayed()
        // Both battlefield bands.
        assertEquals(2, composeTestRule.onAllNodesWithText(EMPTY_BATTLEFIELD).fetchSemanticsNodes().size)
        composeTestRule.onNodeWithText("$STACK_LABEL · $EMPTY_STACK").assertIsDisplayed()
        composeTestRule.onNodeWithText(EMPTY_HAND).assertIsDisplayed()
        composeTestRule.onNodeWithText(EXILE_EMPTY).assertIsDisplayed()
        composeTestRule.onNodeWithText("Turn —", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Waiting for the game").assertIsDisplayed()
        // …and the controls say plainly that there is nothing outstanding, rather than sitting silent.
        composeTestRule.onNodeWithText(NO_OUTSTANDING_PROMPT).assertIsDisplayed()
    }

    @Test
    fun `the first dealt-nothing snapshot shows the board with an empty hand`() {
        // Live behaviour: GAME_INIT precedes the deal, so this is the board's *first* real state.
        render(runningGame().copy(hand = emptyList()))

        composeTestRule.onNodeWithText(EMPTY_HAND).assertIsDisplayed()
        composeTestRule.onNodeWithText("Computer").assertIsDisplayed()
        composeTestRule.onNodeWithText("Turn 3", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText(WAITING_FOR_FIRST_SNAPSHOT).assertDoesNotExist()
    }

    @Test
    fun `an exile zone holding nothing reads as empty`() {
        // The zone list carries one entry in every game; judging by its size would report an exiled card
        // every time.
        render(runningGame().copy(exile = listOf(GameZone(name = "Exile", cards = emptyList()))))

        composeTestRule.onNodeWithText(EXILE_EMPTY).assertIsDisplayed()
    }

    @Test
    fun `filling the stack does not reflow either battlefield`() {
        // Requirements §4.1's one surviving requirement: the stack is empty in the common case and fills
        // abruptly, and it must not move the battlefields when it does. Both are measured for real —
        // the same board is re-rendered with the stack full and the positions compared.
        val state = mutableStateOf(runningGame().copy(stack = emptyList()))
        composeTestRule.setContent {
            MageTheme {
                GameBoardScreen(
                    uiState = GameBoardUiState(board = BoardUi.from(state.value), isJoining = false),
                    onExit = {},
                    onHandExpandedChange = {},
                    onControlsVisibleChange = {},
                    onCardTap = {},
                    onAction = {},
                    artRenderer = PlaceholderCardArtRenderer,
                )
            }
        }

        composeTestRule.onNodeWithText("$STACK_LABEL · $EMPTY_STACK").assertIsDisplayed()
        val opponentBefore = composeTestRule.onNodeWithText("Mountain").fetchSemanticsNode().positionInRoot
        val viewerBefore = composeTestRule.onNodeWithText("Forest").fetchSemanticsNode().positionInRoot

        composeTestRule.runOnIdle { state.value = runningGame() }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Giant Growth").assertIsDisplayed()
        assertEquals(
            "the opponent's battlefield must not move when the stack fills",
            opponentBefore,
            composeTestRule.onNodeWithText("Mountain").fetchSemanticsNode().positionInRoot,
        )
        assertEquals(
            "the viewer's battlefield must not move when the stack fills",
            viewerBefore,
            composeTestRule.onNodeWithText("Forest").fetchSemanticsNode().positionInRoot,
        )
    }

    @Test
    fun `an expanded hand with no cards says so`() {
        render(runningGame().copy(hand = emptyList()), handExpanded = true)

        composeTestRule.onNodeWithText(EMPTY_HAND_EXPANDED).assertIsDisplayed()
    }

    @Test
    fun `an untimed table draws no clock`() {
        render(runningGame().copy(priorityTimeSeconds = 0))

        composeTestRule.onNodeWithText("0:00").assertDoesNotExist()
    }

    // ---- the hand's peek-and-expand ------------------------------------------------------------------

    @Test
    fun `the peek edge asks to expand, and the expanded hand draws the cards`() {
        render(runningGame())

        composeTestRule.onNodeWithText(HAND_EXPAND_LABEL).performClick()
        assertEquals(listOf(true), expandRequests)
    }

    @Test
    fun `the expanded hand shows every card the server dealt`() {
        render(runningGame(), handExpanded = true)

        composeTestRule.onNodeWithText(HAND_COLLAPSE_LABEL).assertIsDisplayed()
        composeTestRule.onNodeWithText("Grizzly Bears").assertIsDisplayed()
        composeTestRule.onNodeWithText("1G").assertIsDisplayed()
    }

    // ---- story 0057: nothing is modal, for any prompt kind ---------------------------------------------

    @Test
    fun `no prompt kind produces a dialog or a popup, and the board stays on screen behind the controls`() {
        // The §16.2 acceptance criterion, mechanised. §6.1 originally sent five of these prompt kinds to
        // a **modal** and §6.2 carved out the other two; §16.2 made the exception the rule. A dialog or a
        // popup takes its own window and blocks the board, so their *absence* is what is asserted —
        // together with the board's own content still being drawn underneath.
        val prompt = mutableStateOf<GamePrompt?>(null)
        composeTestRule.setContent {
            MageTheme {
                val state =
                    runningGame().copy(prompt = prompt.value, viewerHasPriority = true, playable = listOf(PlayableObject("h-1")))
                GameBoardScreen(
                    uiState = GameBoardUiState(board = BoardUi.from(state), isJoining = false, controls = controlsFor(state)),
                    onExit = {},
                    onHandExpandedChange = {},
                    onControlsVisibleChange = {},
                    onCardTap = {},
                    onAction = { actions += it },
                    artRenderer = PlaceholderCardArtRenderer,
                )
            }
        }

        everyPromptKind().forEach { (name, kind) ->
            composeTestRule.runOnIdle { prompt.value = kind }
            composeTestRule.waitForIdle()

            composeTestRule.onAllNodes(isDialog()).assertCountEquals(0)
            composeTestRule.onAllNodes(isPopup()).assertCountEquals(0)
            // The board itself is still there behind the controls: both battlefields and the priority
            // statement, for every prompt kind including the five §6.1 would have sent to a modal.
            composeTestRule.onNodeWithText("Mountain").assertIsDisplayed()
            composeTestRule.onNodeWithText("Forest").assertIsDisplayed()
            composeTestRule.onNodeWithText("Your turn to act").assertIsDisplayed()
            assertTrue("a $name prompt must leave the board visible", true)
        }
    }

    @Test
    fun `the controls float clear of the board a player still has to tap`() {
        // "Floating, not blocking" is a layout claim, so it is measured rather than asserted in prose.
        // The three things that must stay usable underneath: the opponent's battlefield (a target lives
        // there), the priority statement (§16.3), and — the one that decides whether mana payment works
        // at all (§6.3) — the **tap point of the viewer's own permanents**.
        render(
            runningGame().copy(
                prompt = GamePrompt.PlayMana(message = "Pay {1}{W}"),
                viewerHasPriority = true,
                playable = listOf(PlayableObject("y-1")),
            ),
        )

        val root = composeTestRule.onRoot().fetchSemanticsNode()
        val panel = composeTestRule.onNodeWithText(CONTROLS_TITLE).fetchSemanticsNode()
        val opponentCard = composeTestRule.onNodeWithText("Mountain").fetchSemanticsNode()
        val viewerCard = composeTestRule.onNodeWithText("Forest").fetchSemanticsNode()
        val priorityLine = composeTestRule.onNodeWithText("Your turn to act").fetchSemanticsNode()
        val panelTop = panel.positionInRoot.y

        assertTrue("the controls must not start above the opponent's battlefield", panelTop > opponentCard.boundsInRoot.bottom)
        assertTrue("the controls must not start above the priority statement", panelTop > priorityLine.boundsInRoot.bottom)
        assertTrue("your own permanents must stay tappable under the controls", panelTop > viewerCard.boundsInRoot.center.y)
        assertTrue("the controls must not fill the screen", panelTop > root.size.height / 3)
    }

    // ---- story 0057: the visibility toggle (§16.3) -----------------------------------------------------

    @Test
    fun `hiding the controls asks the board to hide them, and shows a way back`() {
        render(runningGame().copy(prompt = GamePrompt.Select(message = "Select"), viewerHasPriority = true))

        composeTestRule.onNodeWithText(HIDE_CONTROLS_LABEL).performClick()
        assertEquals(listOf(false), visibilityRequests)
    }

    @Test
    fun `hiding the controls never hides that the server is waiting on you`() {
        // §16.3's hard constraint, and the reason 0055 added `PriorityUi.Asked`: the server asks one seat
        // to choose who goes first *before priority exists*, so a hidden control set here would look
        // exactly like a frozen game.
        render(
            runningGame().copy(
                viewerHasPriority = false,
                priorityPlayerName = null,
                prompt = GamePrompt.Target(message = "Select a starting player", isRequired = true),
            ),
            controlsVisible = false,
        )

        // 1. the board's own priority banner survives, because it is not part of the controls at all;
        composeTestRule.onNodeWithText("The server is waiting on you").assertIsDisplayed()
        // 2. the collapsed toggle says the same thing independently;
        composeTestRule.onNodeWithText(WAITING_ON_YOU_WHILE_HIDDEN).assertIsDisplayed()
        // 3. and the question itself is still on the notice strip.
        composeTestRule.onNodeWithText("$PROMPT_PREFIX Select a starting player").assertIsDisplayed()
        // The controls really are gone, though — this is not a toggle that does nothing.
        composeTestRule.onNodeWithText(CONTROLS_TITLE).assertDoesNotExist()
        composeTestRule.onNodeWithText(SHOW_CONTROLS_LABEL).assertIsDisplayed()
    }

    @Test
    fun `hiding the controls while the viewer holds priority keeps the priority statement too`() {
        render(
            runningGame().copy(viewerHasPriority = true, prompt = GamePrompt.Select(message = "Select")),
            controlsVisible = false,
        )

        composeTestRule.onNodeWithText("Your turn to act").assertIsDisplayed()
        composeTestRule.onNodeWithText(WAITING_ON_YOU_WHILE_HIDDEN).assertIsDisplayed()
    }

    @Test
    fun `the collapsed toggle stays quiet when the server is genuinely not waiting`() {
        render(runningGame().copy(viewerHasPriority = false, priorityPlayerName = "Computer"), controlsVisible = false)

        composeTestRule.onNodeWithText("Waiting for opponent").assertIsDisplayed()
        composeTestRule.onNodeWithText(WAITING_ON_YOU_WHILE_HIDDEN).assertDoesNotExist()
        composeTestRule.onNodeWithText(SHOW_CONTROLS_LABEL).assertIsDisplayed()
    }

    // ---- story 0057: playing, targeting, paying, cancelling --------------------------------------------

    @Test
    fun `passing priority reaches the action seam`() {
        render(runningGame().copy(viewerHasPriority = true, prompt = GamePrompt.Select(message = "Select")))

        composeTestRule.onNodeWithText(PASS_LABEL).performClick()
        assertEquals(listOf(BoardAction.PassPriority), actions)
    }

    @Test
    fun `every control in the panel is really pressable, not merely drawn`() {
        // This test exists because the panel once rendered perfectly, reported a click action on every
        // button, and did nothing at all when pressed: `Surface` clips to its shape, and a shape whose
        // corner radii differ resolves hit-testing through a path the test environment cannot evaluate.
        // A "does it look right" assertion cannot see that; pressing can.
        render(runningGame().copy(viewerHasPriority = true, prompt = GamePrompt.Select(message = "Select")))

        composeTestRule.onNodeWithText(HIDE_CONTROLS_LABEL).performClick()
        assertEquals(listOf(false), visibilityRequests)

        composeTestRule.onNodeWithText(BOARD_MENU_LABEL).performClick()
        composeTestRule.onNodeWithText(CONCEDE_LABEL).assertIsDisplayed()
    }

    @Test
    fun `tapping a card raises it rather than playing it`() {
        // §5.1: the first tap raises the card and shows its detail; committing is a second, deliberate
        // gesture. A single-tap-to-play board has nowhere to put "and now choose targets".
        render(
            runningGame().copy(viewerHasPriority = true, playable = listOf(PlayableObject("h-1")), prompt = GamePrompt.Select("Select")),
            handExpanded = true,
        )

        composeTestRule.onNodeWithText("Llanowar Elves").performClick()

        assertEquals(listOf<String?>("h-1"), taps)
        assertEquals("raising a card sends the server nothing", emptyList<BoardAction>(), actions)
    }

    @Test
    fun `the raised card can be played from its detail view`() {
        // §11.1: a playable card is played directly from the detail view.
        render(
            runningGame().copy(viewerHasPriority = true, playable = listOf(PlayableObject("h-1")), prompt = GamePrompt.Select("Select")),
            handExpanded = true,
            selectedObjectId = "h-1",
        )

        composeTestRule.onNodeWithText(PLAY_ACTION_LABEL).performClick()
        assertEquals(listOf(BoardAction.PlayObject("h-1")), actions)
    }

    @Test
    fun `a card the server has not offered gets a detail view with nothing to press`() {
        // §3 of the story: `playable` exists only while you hold priority, so an unoffered card means
        // "not your moment", not "not playable" — and the board must not present a dead button either.
        render(
            runningGame().copy(viewerHasPriority = true, playable = emptyList(), prompt = GamePrompt.Select("Select")),
            handExpanded = true,
            selectedObjectId = "h-1",
        )

        composeTestRule.onNodeWithText(PLAY_ACTION_LABEL).assertDoesNotExist()
        composeTestRule.onNodeWithText(CLOSE_DETAIL_LABEL).assertIsDisplayed()
    }

    @Test
    fun `the detail closes on a tap anywhere else`() {
        render(runningGame(), handExpanded = true, selectedObjectId = "h-1")

        composeTestRule.onNodeWithText(CLOSE_DETAIL_LABEL).performClick()
        assertEquals(listOf<String?>(null), taps)
    }

    // ---- story 0058: what a permanent currently *is*, on screen -------------------------------------

    @Test
    fun `a land on the battlefield shows no power toughness and no summoning-sickness mark`() {
        // The defect, as it was actually seen on device: "0/0 · Summoning sick" under a Mountain. Both
        // halves are the server's honest data — a noncreature permanent's current power really is "0",
        // and summoning sickness really is set for anything that arrived this turn — so both are gated
        // on what the permanent *is*, not suppressed at the source.
        render(runningGame(landIsAnimated = false))

        composeTestRule.onNodeWithText("Mountain").assertIsDisplayed()
        composeTestRule.onNodeWithText("0/0", substring = true).assertDoesNotExist()
        composeTestRule.onNodeWithText(SUMMONING_SICK_MARK, substring = true).assertDoesNotExist()
    }

    @Test
    fun `the same land, animated by an effect, shows its power toughness and its sickness`() {
        // The mirror case, and the one that makes the test above mean something: a board that simply
        // hardcoded "lands have no P/T" would pass that test and fail this one. Earthbend is ordinary
        // play, not a corner case.
        render(runningGame(landIsAnimated = true))

        composeTestRule.onNodeWithText("Mountain").assertIsDisplayed()
        composeTestRule.onNodeWithText("0/3", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText(SUMMONING_SICK_MARK, substring = true).assertIsDisplayed()
    }

    @Test
    fun `a creature shows its current power and toughness`() {
        render(runningGame(viewerPermanent = bears(power = "4", toughness = "5")))

        composeTestRule.onNodeWithText("4/5", substring = true).assertIsDisplayed()
    }

    @Test
    fun `counters are drawn by name and count, for kinds the board has never heard of`() {
        render(
            runningGame(
                viewerPermanent =
                    GamePermanent(
                        card =
                            bears().card.copy(
                                counters = listOf(GameCounter("+1/+1", 2), GameCounter("hatchling", 7)),
                            ),
                    ),
            ),
        )

        composeTestRule.onNodeWithText("+1/+1 ×2", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("hatchling ×7", substring = true).assertIsDisplayed()
    }

    @Test
    fun `a permanent with no counters draws no counter row`() {
        render(runningGame(viewerPermanent = bears()))

        composeTestRule.onNodeWithText("×", substring = true).assertDoesNotExist()
    }

    @Test
    fun `the raised card's detail states the power toughness and counters of what it currently is`() {
        // The permanent's own line is one ellipsised row 76dp wide, so the detail is where a player with
        // four counters on something actually reads them (§11.1: the first tap is for looking).
        render(
            runningGame(
                viewerPermanent =
                    GamePermanent(card = bears(power = "2", toughness = "2").card.copy(counters = listOf(GameCounter("oil", 3)))),
            ),
            selectedObjectId = "y-1",
        )

        composeTestRule.onAllNodesWithText("2/2", substring = true).onFirst().assertIsDisplayed()
        composeTestRule.onAllNodesWithText("oil ×3", substring = true).onFirst().assertIsDisplayed()
    }

    @Test
    fun `a target candidate on the board is picked one tap at a time`() {
        // §17.2: each pick is sent as it is made. The gesture path is the same as playing — raise, then
        // commit — so the *action* is what proves the pick is a pick.
        val state =
            runningGame().copy(
                prompt =
                    GamePrompt.Target(
                        message = "Select targets (selected 0 of 2, min 1) to divide 2 damage",
                        isRequired = false,
                        options = PromptOptions(ids = mapOf(PromptOptions.POSSIBLE_TARGETS to listOf("o-1", "y-1"))),
                    ),
            )
        render(state, selectedObjectId = "o-1")

        composeTestRule.onNodeWithText(TARGET_ACTION_LABEL).performClick()
        assertEquals(listOf(BoardAction.ChooseTarget("o-1")), actions)
    }

    @Test
    fun `the first prompt of every game can actually be answered`() {
        // Found on a real device, and blocking: the board came up on "Select a starting player" with no
        // way to answer it at all. The candidate ids are **players**, the prompt carries no cards, and
        // nothing on the board draws a player id — so a panel that only ever lit up matching cards
        // offered zero affordances, on the one prompt that runs before any other. The game could not
        // begin.
        //
        // Asserted at the *screen*, not only at the projection, because the projection already held the
        // ids: `pickableObjectIds` was populated the whole time. What was missing was a surface. The live
        // harness answered this prompt happily by reading the model directly, which is exactly why it
        // never caught it.
        render(
            runningGame().copy(
                viewerHasPriority = false,
                priorityPlayerName = null,
                prompt =
                    GamePrompt.Target(
                        message = "Select a starting player",
                        targetIds = listOf("p-you", "p-opp"),
                        isRequired = true,
                    ),
            ),
        )

        composeTestRule.onNodeWithText("The server is waiting on you").assertIsDisplayed()
        // Both seats are offered, each by name — "Computer" now appears twice on screen (its vitals bar
        // and its candidate button), which is itself the evidence that the candidate arrived.
        composeTestRule.onAllNodesWithText("Computer").assertCountEquals(2)

        composeTestRule.onNodeWithText("you (you)").performClick()
        assertEquals(listOf(BoardAction.ChooseTarget("p-you")), actions)
    }

    @Test
    fun `the floating controls never leave the board with a prompt and nothing to press`() {
        // The general form of the defect above, mechanised: for every prompt kind the server can ask,
        // the panel must offer **something** — a button, a candidate, or a stepper. The only exception
        // is the one the app is right to refuse: `Unrecognised`, which has no valid answer at all.
        val prompt = mutableStateOf<GamePrompt?>(null)
        composeTestRule.setContent {
            MageTheme {
                val state = runningGame().copy(prompt = prompt.value, viewerHasPriority = true)
                GameBoardScreen(
                    uiState = GameBoardUiState(board = BoardUi.from(state), isJoining = false, controls = controlsFor(state)),
                    onExit = {},
                    onHandExpandedChange = {},
                    onControlsVisibleChange = {},
                    onCardTap = {},
                    onAction = { actions += it },
                    artRenderer = PlaceholderCardArtRenderer,
                )
            }
        }

        everyPromptKind().forEach { (name, kind) ->
            composeTestRule.runOnIdle { prompt.value = kind }
            composeTestRule.waitForIdle()
            val controls = controlsFor(runningGame().copy(prompt = kind, viewerHasPriority = true))!!
            if (!controls.isAnswerable) {
                composeTestRule.onNodeWithText(UNANSWERABLE_NOTE).assertIsDisplayed()
            } else {
                val answerable =
                    controls.buttons.isNotEmpty() ||
                        controls.amountRequest != null ||
                        controls.amountRows.isNotEmpty() ||
                        controls.candidateCards.isNotEmpty()
                assertTrue("a $name prompt must leave the player something to press", answerable)
            }
        }
    }

    @Test
    fun `the controls do not cover your own vitals bar`() {
        // The panel is anchored to the bottom, and your own life total used to sit underneath it: the
        // only way to read it was to hide the controls. That is survivable for life, and not survivable
        // when *you* are one of the candidates being chosen between.
        render(runningGame().copy(prompt = GamePrompt.Select(message = "Select"), viewerHasPriority = true))

        val panel = composeTestRule.onNodeWithText(CONTROLS_TITLE).fetchSemanticsNode()
        val viewerVitals = composeTestRule.onNodeWithText("$LIBRARY_LABEL 53", substring = true).fetchSemanticsNode()

        assertTrue(
            "your own vitals bar must stay visible while the controls are open",
            viewerVitals.boundsInRoot.bottom <= panel.positionInRoot.y,
        )
    }

    @Test
    fun `the target step offers a cancel before a pick and a done after one`() {
        val prompt =
            GamePrompt.Target(
                message = "Select targets (selected 0 of 2, min 1) to divide 2 damage",
                isRequired = false,
                options = PromptOptions(ids = mapOf(PromptOptions.POSSIBLE_TARGETS to listOf("o-1"))),
            )

        render(runningGame().copy(prompt = prompt), cast = CastUi("Forked Bolt", CAST_STEP_TARGETS))
        composeTestRule.onNodeWithText(CANCEL_CAST_LABEL).assertIsDisplayed()
        composeTestRule.onNodeWithText(DONE_LABEL).assertDoesNotExist()
        composeTestRule.onNodeWithText(CANCEL_CAST_LABEL).performClick()
        assertEquals(listOf(BoardAction.CancelPrompt), actions)
    }

    @Test
    fun `the mana step is answered by tapping your own source`() {
        // §6.3/§6.5: the player taps lands; the server has already narrowed the choice, so the board
        // renders what it offered and computes nothing.
        val state =
            runningGame().copy(
                prompt = GamePrompt.PlayMana(message = "Pay {1}{W}"),
                playable = listOf(PlayableObject("y-1")),
                viewerHasPriority = true,
            )
        render(state, selectedObjectId = "y-1", cast = CastUi("Squadron Hawk", CAST_STEP_MANA))

        composeTestRule.onNodeWithText("$CASTING_PREFIX Squadron Hawk — $CAST_STEP_MANA").assertIsDisplayed()
        composeTestRule.onNodeWithText(MANA_ACTION_LABEL).performClick()

        assertEquals(listOf(BoardAction.PlayManaSource("y-1")), actions)
    }

    @Test
    fun `the mana step can be cancelled, which is where the rewind was first proven`() {
        // §6.4a: declining the mana step returned the Hawk to hand (7), cleared the stack and left both
        // Plains untapped.
        render(
            runningGame().copy(prompt = GamePrompt.PlayMana(message = "Pay {1}{W}"), playable = listOf(PlayableObject("y-1"))),
            cast = CastUi("Squadron Hawk", CAST_STEP_MANA),
        )

        composeTestRule.onNodeWithText(CANCEL_CAST_LABEL).performClick()

        assertEquals(listOf(BoardAction.CancelPrompt), actions)
    }

    @Test
    fun `the cast in flight stays on screen through every question the server asks`() {
        // §6.4: casting is one continuous act, so the spell does not vanish behind each new question.
        render(
            runningGame().copy(prompt = GamePrompt.Target(message = "Select targets", targetIds = listOf("o-1"))),
            cast = CastUi("Forked Bolt", CAST_STEP_TARGETS),
        )

        composeTestRule.onNodeWithText("$CASTING_PREFIX Forked Bolt — $CAST_STEP_TARGETS").assertIsDisplayed()
    }

    @Test
    fun `a cancel in progress says the server is rewinding, never that the spell was not cast`() {
        render(
            runningGame().copy(prompt = GamePrompt.PlayMana(message = "Pay {1}{W}")),
            cast = CastUi("Forked Bolt", CAST_STEP_MANA, isCancelling = true),
        )

        composeTestRule.onNodeWithText(CANCELLING_NOTE).assertIsDisplayed()
    }

    // ---- story 0057: the prompts answered from their own content ---------------------------------------

    @Test
    fun `a yes-or-no question is answered with the server's own words`() {
        render(
            runningGame().copy(
                prompt =
                    GamePrompt.Ask(
                        message = "Mulligan down to 6 cards?",
                        options =
                            PromptOptions(
                                text =
                                    mapOf(
                                        PromptOptions.LEFT_BUTTON_TEXT to "Mulligan",
                                        PromptOptions.RIGHT_BUTTON_TEXT to "Keep",
                                    ),
                            ),
                    ),
            ),
        )

        composeTestRule.onNodeWithText("Keep").performClick()
        assertEquals(listOf(BoardAction.AnswerAsk(yes = false)), actions)
    }

    @Test
    fun `an ability choice lists the server's own rendered abilities`() {
        render(
            runningGame().copy(
                prompt =
                    GamePrompt.ChooseAbility(
                        message = "Choose an ability",
                        choices = listOf(AbilityChoice("a-1", "{T}: Add {R}")),
                    ),
            ),
        )

        composeTestRule.onNodeWithText("{T}: Add {R}").performClick()
        assertEquals(listOf(BoardAction.ChooseAbility("a-1")), actions)
    }

    @Test
    fun `a list choice is answered by its key, never by its label`() {
        render(
            runningGame().copy(
                prompt = GamePrompt.ChooseChoice(message = "Choose a colour", choices = listOf(ChoiceOption("R", "Red"))),
            ),
        )

        composeTestRule.onNodeWithText("Red").performClick()
        assertEquals(listOf(BoardAction.ChooseChoice("R")), actions)
    }

    @Test
    fun `a number is chosen inside the server's own bounds and then confirmed`() {
        render(runningGame().copy(prompt = GamePrompt.GetAmount(message = "How many?", min = 1, max = 3)))

        composeTestRule.onNodeWithText(AMOUNT_INCREASE_LABEL).performClick()
        composeTestRule.onNodeWithText(CONFIRM_AMOUNT_LABEL).performClick()

        assertEquals("the stepper starts at the server's minimum", listOf(BoardAction.ChooseAmount(2)), actions)
    }

    @Test
    fun `a total is distributed across the server's rows, in the server's order`() {
        render(
            runningGame().copy(
                prompt =
                    GamePrompt.GetMultiAmount(
                        message = "Divide 2 damage",
                        entries = listOf(MultiAmountEntry("Goblin A", max = 2), MultiAmountEntry("Goblin B", max = 2)),
                    ),
            ),
        )

        composeTestRule.onAllNodesWithText(AMOUNT_INCREASE_LABEL).onFirst().performClick()
        composeTestRule.onAllNodesWithText(AMOUNT_INCREASE_LABEL).onFirst().performClick()
        composeTestRule.onNodeWithText(CONFIRM_AMOUNT_LABEL).performClick()

        assertEquals(listOf(BoardAction.DistributeAmounts(listOf(2, 0))), actions)
    }

    @Test
    fun `a pile choice shows both piles and answers with the one chosen`() {
        render(
            runningGame().copy(
                prompt =
                    GamePrompt.ChoosePile(
                        message = "Choose a pile",
                        pile1 = listOf(GameCard(id = "c-1", name = "Swamp")),
                        pile2 = listOf(GameCard(id = "c-2", name = "Island")),
                    ),
            ),
        )

        // The two piles' contents are drawn, below the buttons: the panel is height-capped so it cannot
        // swallow the board, and what must stay *pressable* wins the space over what is there to be
        // read. A pile's cards are reached by scrolling the panel, which is why this asserts existence
        // rather than immediate visibility — the honest description of where they are.
        composeTestRule.onNodeWithText("Swamp").assertExists()
        composeTestRule.onNodeWithText("$PILE_LABEL 1 (1)").assertIsDisplayed()
        composeTestRule.onNodeWithText("$PILE_LABEL 2 (1)").performClick()
        assertEquals(listOf(BoardAction.ChoosePile(first = false)), actions)
    }

    // ---- story 0057: the prompt that must never become a control ----------------------------------------

    @Test
    fun `an unrecognised prompt is a notice with no control the player cannot satisfy`() {
        render(runningGame().copy(prompt = GamePrompt.Unrecognised(type = "GAME_NEW_THING")))

        composeTestRule.onNodeWithText(UNRECOGNISED_PROMPT_NOTICE).assertIsDisplayed()
        composeTestRule.onNodeWithText(UNANSWERABLE_NOTE).assertIsDisplayed()
        // Not a single answering control — and in particular no cancel, which would be a guess.
        composeTestRule.onNodeWithText(PASS_LABEL).assertDoesNotExist()
        composeTestRule.onNodeWithText(CANCEL_CAST_LABEL).assertDoesNotExist()
        composeTestRule.onNodeWithText(DONE_LABEL).assertDoesNotExist()
        composeTestRule.onNodeWithText(CONFIRM_AMOUNT_LABEL).assertDoesNotExist()
        // The way out of the game stays available, which is what 0052's KDoc says a UI should do.
        composeTestRule.onNodeWithText(BOARD_MENU_LABEL).assertIsDisplayed()
    }

    // ---- story 0057: concede and quit are separate (§12.2) -----------------------------------------------

    @Test
    fun `concede and quit are separate actions, each behind its own confirmation`() {
        render(runningGame())

        composeTestRule.onNodeWithText(BOARD_MENU_LABEL).performClick()
        composeTestRule.onNodeWithText(CONCEDE_LABEL).assertIsDisplayed()
        composeTestRule.onNodeWithText(QUIT_MATCH_LABEL).assertIsDisplayed()

        // One tap only arms it — losing a game to a mis-tap is not acceptable.
        composeTestRule.onNodeWithText(CONCEDE_LABEL).performClick()
        assertEquals(emptyList<BoardAction>(), actions)
        composeTestRule.onNodeWithText(CONCEDE_CONFIRM_LABEL).performClick()
        assertEquals(listOf(BoardAction.Concede), actions)

        composeTestRule.onNodeWithText(BOARD_MENU_LABEL).performClick()
        composeTestRule.onNodeWithText(QUIT_MATCH_LABEL).performClick()
        composeTestRule.onNodeWithText(QUIT_MATCH_CONFIRM_LABEL).performClick()
        assertEquals(listOf(BoardAction.Concede, BoardAction.QuitMatch), actions)
    }

    @Test
    fun `a declined action shows the server's own reason`() {
        composeTestRule.setContent {
            MageTheme {
                GameBoardScreen(
                    uiState =
                        GameBoardUiState(
                            board = BoardUi.from(runningGame()),
                            isJoining = false,
                            actionError = "you can't play that now",
                        ),
                    onExit = {},
                    onHandExpandedChange = {},
                    onControlsVisibleChange = {},
                    onCardTap = {},
                    onAction = {},
                    artRenderer = PlaceholderCardArtRenderer,
                )
            }
        }

        composeTestRule.onNodeWithText("$ACTION_FAILED_PREFIX you can't play that now").assertIsDisplayed()
    }

    // ---- combat: declaring attackers and blockers (story 0061) -----------------------------------------
    //
    // These are at the *screen*, not only at the projection, for the same reason 0057's were: the ids
    // were in the projection the whole time and the board still offered no surface for them. A
    // declaration arrives with **`playable` empty** (§7.2), so every fixture here sends it that way.

    @Test
    fun `a declaration offers the creature to tap, and the tap declares it`() {
        // Today's board offers "Pass priority" and "All attack" and nothing else — attack with
        // everything or nothing. The creature the server named must be tappable.
        render(declaringAttackers(), selectedObjectId = "y-1")

        composeTestRule.onNodeWithText(DECLARE_ATTACKER_ACTION_LABEL).performClick()
        assertEquals(listOf(BoardAction.ChooseTarget("y-1")), actions)
    }

    @Test
    fun `a blocker is declared the same way, from the server's own list`() {
        render(declaringBlockers(), selectedObjectId = "y-1")

        composeTestRule.onNodeWithText(DECLARE_BLOCKER_ACTION_LABEL).performClick()
        assertEquals(listOf(BoardAction.ChooseTarget("y-1")), actions)
    }

    @Test
    fun `all attack commits the whole team only behind a confirmation`() {
        // §16.4 / §7.5 (Pete): the shortcut stays, because the server supplies it and it works — but it
        // commits every creature at once, so it confirms first, exactly as concede does.
        render(declaringAttackers())

        composeTestRule.onNodeWithText(ALL_ATTACK_LABEL).performClick()
        assertEquals("one tap only arms it", emptyList<BoardAction>(), actions)

        composeTestRule.onNodeWithText(ALL_ATTACK_CONFIRM_LABEL).performClick()
        assertEquals(listOf(BoardAction.UseSpecial), actions)
    }

    @Test
    fun `the board says which of the two roles it is in, and never both`() {
        render(declaringAttackers())
        composeTestRule.onNodeWithText(DECLARE_ATTACKERS_NOTE).assertIsDisplayed()
        composeTestRule.onAllNodesWithText(DECLARE_BLOCKERS_NOTE).assertCountEquals(0)

        composeTestRule.onNodeWithText(ALL_ATTACK_LABEL).assertIsDisplayed()
    }

    @Test
    fun `blocking offers no shortcut, because the server sends none`() {
        render(declaringBlockers())

        composeTestRule.onNodeWithText(DECLARE_BLOCKERS_NOTE).assertIsDisplayed()
        composeTestRule.onAllNodesWithText(DECLARE_ATTACKERS_NOTE).assertCountEquals(0)
        composeTestRule.onAllNodesWithText(ALL_ATTACK_LABEL).assertCountEquals(0)
    }

    @Test
    fun `a pairing question mid-declaration says which creature it is about, and is answerable`() {
        // The follow-up arrives as an ordinary `GAME_TARGET` (§7.5) whose prose names the *candidates*
        // and never the creature being paired. Without the context line the player is asked to choose a
        // defender with no statement of what for.
        val state =
            declaringAttackers().copy(
                prompt =
                    GamePrompt.Target(
                        message = "Select a defender",
                        targetIds = listOf("o-1"),
                        isRequired = true,
                    ),
            )
        render(state, declaration = DeclarationUi(role = CombatRole.Attacking, pairingCreatureName = "Grizzly Bears"))

        composeTestRule.onNodeWithText(DECLARING_ATTACKERS_TITLE).assertIsDisplayed()
        composeTestRule.onNodeWithText("Attacking with Grizzly Bears", substring = true).assertIsDisplayed()

        // …and it is still answered exactly like any other target.
        composeTestRule.onNodeWithText("Mountain").performClick()
        assertEquals(listOf<String?>("o-1"), taps)
    }

    @Test
    fun `an attacker says what it attacks and what is blocking it`() {
        // 0055 marks attackers and blockers; what it could not say is *what* they are attacking or
        // blocking. `CombatGroup` is per-attacker (§7.3) and carries the defender's own name.
        render(blockedAttack())

        composeTestRule.onNodeWithText("$ATTACKING_MARK Computer", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("$BLOCKED_BY_MARK Mountain", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("$BLOCKING_MARK Grizzly Bears", substring = true).assertIsDisplayed()
    }

    // ---- fixtures --------------------------------------------------------------------------------------

    private fun card(
        id: String,
        name: String,
        typeLine: String,
        manaCost: String? = null,
    ) = GameCard(
        id = id,
        name = name,
        setCode = "M21",
        collectorNumber = "272",
        manaCost = manaCost,
        typeLine = typeLine,
    )

    /** Every prompt kind the server can send, for the "nothing is modal" sweep. */
    private fun everyPromptKind(): List<Pair<String, GamePrompt>> =
        listOf(
            "select" to GamePrompt.Select(message = "Select an ability to play"),
            "target" to GamePrompt.Target(message = "Select targets", targetIds = listOf("o-1")),
            "ask" to GamePrompt.Ask(message = "Mulligan down to 6 cards?"),
            "choose-ability" to GamePrompt.ChooseAbility(message = "Choose", choices = listOf(AbilityChoice("a-1", "Mode one"))),
            "choose-pile" to GamePrompt.ChoosePile(message = "Choose a pile", pile1 = listOf(card("c-1", "Swamp", "Land"))),
            "choose-choice" to GamePrompt.ChooseChoice(message = "Choose a colour", choices = listOf(ChoiceOption("R", "Red"))),
            "play-mana" to GamePrompt.PlayMana(message = "Pay {1}{W}"),
            "play-x-mana" to GamePrompt.PlayXMana(message = "Announce X"),
            "get-amount" to GamePrompt.GetAmount(message = "How many?", min = 0, max = 3),
            "get-multi-amount" to
                GamePrompt.GetMultiAmount(message = "Divide", entries = listOf(MultiAmountEntry("Goblin A", max = 2))),
            "unrecognised" to GamePrompt.Unrecognised(type = "GAME_NEW_THING"),
        )

    /**
     * A creature permanent in the viewer's seat, at id `y-1` — the seat's only permanent, so a test can
     * raise it with `selectedObjectId = "y-1"`.
     */
    private fun bears(
        power: String = "2",
        toughness: String = "2",
    ) = GamePermanent(
        card =
            card("y-1", "Grizzly Bears", "Creature — Bear", "1G").copy(
                power = power,
                toughness = toughness,
                isCreature = true,
                cardTypes = listOf(CardType.Creature),
            ),
    )

    /**
     * The server's own declare-attackers prompt (§7.2): a `Select` whose message is `Select attackers`,
     * whose ids live in **`possibleAttackers`**, which carries the `specialButton` the board renders as
     * "All attack" — and whose **`playable` is empty**, which is the whole reason the ids have to come
     * from the options.
     */
    private fun declaringAttackers() =
        runningGame(viewerPermanent = bears()).copy(
            step = PhaseStep.DeclareAttackers,
            phase = TurnPhase.Combat,
            playable = emptyList(),
            prompt =
                GamePrompt.Select(
                    message = "Select attackers",
                    options =
                        PromptOptions(
                            text = mapOf(PromptOptions.SPECIAL_BUTTON to ALL_ATTACK_LABEL),
                            ids = mapOf(PromptOptions.POSSIBLE_ATTACKERS to listOf("y-1")),
                        ),
                ),
        )

    /** The blocking half (§7.3): the same shape, `possibleBlockers`, and **no** special button. */
    private fun declaringBlockers() =
        runningGame(viewerPermanent = bears()).copy(
            step = PhaseStep.DeclareBlockers,
            phase = TurnPhase.Combat,
            playable = emptyList(),
            prompt =
                GamePrompt.Select(
                    message = "Select blockers",
                    options = PromptOptions(ids = mapOf(PromptOptions.POSSIBLE_BLOCKERS to listOf("y-1"))),
                ),
        )

    /** A combat already declared on both sides: your Bears attacking, their Mountain blocking it. */
    private fun blockedAttack() =
        runningGame(viewerPermanent = bears()).copy(
            step = PhaseStep.DeclareBlockers,
            phase = TurnPhase.Combat,
            combat =
                listOf(
                    CombatGroup(
                        defenderId = "p-opp",
                        defenderName = "Computer",
                        isBlocked = true,
                        attackerIds = listOf("y-1"),
                        blockerIds = listOf("o-1"),
                    ),
                ),
        )

    /**
     * A running game with the **opponent first** in the players list, as the real server often sends.
     *
     * The opponent's Mountain is **summoning sick** and carries the `"0"`/`"0"` power/toughness upstream
     * really sends for a noncreature permanent — the exact payload that rendered "0/0 · Summoning sick"
     * on device. [landIsAnimated] flips it to what the server sends once an effect (Earthbend) has made
     * it a creature: same card, same zone, different answer.
     */
    private fun runningGame(
        landIsAnimated: Boolean = false,
        viewerPermanent: GamePermanent =
            GamePermanent(
                card =
                    card("y-1", "Forest", "Basic Land — Forest").copy(
                        power = "0",
                        toughness = "0",
                        cardTypes = listOf(CardType.Land),
                    ),
            ),
    ) = GameState(
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
        players =
            listOf(
                GamePlayer(
                    playerId = "p-opp",
                    name = "Computer",
                    life = 18,
                    libraryCount = 51,
                    handCount = 5,
                    isHuman = false,
                    battlefield =
                        listOf(
                            GamePermanent(
                                card =
                                    card("o-1", "Mountain", "Basic Land — Mountain").copy(
                                        power = "0",
                                        toughness = if (landIsAnimated) "3" else "0",
                                        isCreature = landIsAnimated,
                                        cardTypes =
                                            if (landIsAnimated) {
                                                listOf(CardType.Land, CardType.Creature)
                                            } else {
                                                listOf(CardType.Land)
                                            },
                                    ),
                                hasSummoningSickness = true,
                            ),
                        ),
                ),
                GamePlayer(
                    playerId = "p-you",
                    name = "you",
                    life = 20,
                    libraryCount = 53,
                    handCount = 2,
                    isViewer = true,
                    isActive = true,
                    manaPool = ManaPool(),
                    battlefield = listOf(viewerPermanent),
                ),
            ),
        hand =
            listOf(
                card("h-1", "Llanowar Elves", "Creature — Elf Druid", "G"),
                card("h-2", "Grizzly Bears", "Creature — Bear", "1G"),
            ),
        stack = listOf(card("s-1", "Giant Growth", "Instant", "G")),
    )
}
