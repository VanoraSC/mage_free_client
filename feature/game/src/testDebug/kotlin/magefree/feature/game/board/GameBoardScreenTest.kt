package magefree.feature.game.board

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import magefree.designsystem.theme.MageTheme
import magefree.feature.cards.PlaceholderCardArtRenderer
import magefree.network.game.GameCard
import magefree.network.game.GameMessage
import magefree.network.game.GamePermanent
import magefree.network.game.GamePlayer
import magefree.network.game.GamePrompt
import magefree.network.game.GameState
import magefree.network.game.GameZone
import magefree.network.game.PhaseStep
import magefree.network.game.PlayableObject
import magefree.network.game.TurnPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Story 0055 — the board's **rendering** tests, in the hermetic gate.
 *
 * They run on the JVM under Robolectric (`src/testDebug`), so `:feature:game:check` covers them
 * pre-merge. That placement is deliberate and has a history: device-only tests do not run before a
 * merge, which is how an entire epic shipped built, tested and unreachable (stories 0047/0048). A board
 * that renders nothing would pass a ViewModel test suite perfectly.
 *
 * What is asserted here and not in [BoardUiTest]: that each region is actually **on screen**, that each
 * region's **empty state** is on screen when it is empty, and — the acceptance criterion with teeth —
 * that the board **offers no way to act**.
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

    private var expandRequests = mutableListOf<Boolean>()

    private fun render(
        state: GameState,
        handExpanded: Boolean = false,
    ) {
        composeTestRule.setContent {
            MageTheme {
                GameBoardScreen(
                    uiState =
                        GameBoardUiState(
                            board = BoardUi.from(state),
                            isJoining = false,
                            isHandExpanded = handExpanded,
                        ),
                    onExit = {},
                    onHandExpandedChange = { expandRequests += it },
                    artRenderer = PlaceholderCardArtRenderer,
                )
            }
        }
    }

    // ---- the populated board -----------------------------------------------------------------------

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

    // ---- read-only ------------------------------------------------------------------------------------

    @Test
    fun `the board says out loud that it cannot be acted on`() {
        render(runningGame())

        composeTestRule.onNodeWithText(READ_ONLY_NOTICE).assertIsDisplayed()
    }

    @Test
    fun `offers no way to act — the only clickable things are the hand toggle and back`() {
        // The acceptance criterion, mechanised. A control that *looks* like it plays a card and does
        // nothing is worse than no control, so this counts the clickable nodes rather than trusting the
        // absence of a handler to be noticed in review.
        render(
            runningGame().copy(
                viewerHasPriority = true,
                playable = listOf(PlayableObject("h-1"), PlayableObject("y-1")),
                prompt = GamePrompt.Select(message = "Select an ability to play"),
            ),
            handExpanded = true,
        )

        val clickable =
            composeTestRule
                .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick))
                .fetchSemanticsNodes()
        assertEquals(
            "only the back button and the hand peek toggle may be clickable; found ${clickable.size}",
            2,
            clickable.size,
        )
        assertTrue(
            "nothing on the board may be a game action",
            clickable.all { node ->
                val text = node.config.getOrNull(SemanticsProperties.Text).orEmpty()
                text.isEmpty() || text.any { it.text == HAND_EXPAND_LABEL || it.text == HAND_COLLAPSE_LABEL }
            },
        )
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

    /** A running game with the **opponent first** in the players list, as the real server often sends. */
    private fun runningGame() =
        GameState(
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
                        battlefield = listOf(GamePermanent(card = card("o-1", "Mountain", "Basic Land — Mountain"))),
                    ),
                    GamePlayer(
                        playerId = "p-you",
                        name = "you",
                        life = 20,
                        libraryCount = 53,
                        handCount = 2,
                        isViewer = true,
                        isActive = true,
                        battlefield = listOf(GamePermanent(card = card("y-1", "Forest", "Basic Land — Forest"))),
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
