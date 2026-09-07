package magefree.feature.game.table

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import magefree.designsystem.theme.MageTheme
import magefree.feature.cards.PlaceholderCardArtRenderer
import magefree.feature.game.board.BoardAction
import magefree.feature.game.board.BoardUi
import magefree.feature.game.board.GameBoardUiState
import magefree.feature.game.board.NO_OUTSTANDING_PROMPT
import magefree.feature.game.board.PASS_LABEL
import magefree.feature.game.board.PLAY_ACTION_LABEL
import magefree.feature.game.board.WAITING_FOR_FIRST_SNAPSHOT
import magefree.feature.game.board.WAITING_ON_YOU_WHILE_HIDDEN
import magefree.feature.game.board.controlsFor
import magefree.network.game.CardType
import magefree.network.game.GameCard
import magefree.network.game.GamePermanent
import magefree.network.game.GamePlayer
import magefree.network.game.GamePrompt
import magefree.network.game.GameState
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
 * The rebuilt board, playing a game.
 *
 * These run on the JVM under Robolectric (`src/testDebug`), so `:feature:game:check` covers them
 * pre-merge. That placement is deliberate and has a history: device-only tests do not run before a
 * merge, which is how an entire epic shipped built, tested and unreachable. A board that renders
 * nothing would pass a ViewModel suite perfectly.
 *
 * What is asserted here rather than in the table tier's own tests is the **joining**: that the board
 * is drawn from the server's snapshot at all, that the prompt surfaces reach it, that hiding them
 * cannot hide that the server is waiting, and that a press on a card really does arrive at the action
 * seam. How the battlefield itself arranges is [BattlefieldLayoutTest]'s question.
 *
 * Art goes through [PlaceholderCardArtRenderer] and the board's own resolver is left null, so no test
 * loads a network image. That production binds the real ones is `GameBoardRoute`'s and `AppNavHost`'s
 * single lines each, and is what the on-device pass confirms.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    // A plain Application: the screen takes its renderers as parameters precisely so it needs no DI
    // container to render.
    application = Application::class,
    // Landscape, which is the only orientation the new UI has.
    qualifiers = "w891dp-h411dp",
)
class TableBoardScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val visibilityRequests = mutableListOf<Boolean>()
    private val taps = mutableListOf<String?>()
    private val actions = mutableListOf<BoardAction>()
    private var exits = 0

    private fun render(
        state: GameState?,
        controlsVisible: Boolean = true,
        selectedObjectId: String? = null,
        joinError: String? = null,
    ) {
        composeTestRule.setContent {
            MageTheme {
                TableBoardScreen(
                    uiState =
                        GameBoardUiState(
                            board = state?.let(BoardUi::from) ?: BoardUi(gameId = "g-1"),
                            snapshot = state,
                            isJoining = false,
                            joinError = joinError,
                            areControlsVisible = controlsVisible,
                            controls = state?.let { controlsFor(it) },
                            selectedObjectId = selectedObjectId,
                        ),
                    onExit = { exits += 1 },
                    onControlsVisibleChange = { visibilityRequests += it },
                    onCardTap = { taps += it },
                    onAction = { actions += it },
                    artRenderer = PlaceholderCardArtRenderer,
                )
            }
        }
    }

    // ---- the board is on screen -----------------------------------------------------------------

    @Test
    fun `a snapshot draws the rebuilt board, not a placeholder`() {
        // The story in one assertion. Before 0112 the live route composed the portrait board, and
        // every one of the table tier's stories had been judged only in the catalog.
        render(runningGame())

        composeTestRule.onNodeWithTag(BattlefieldTestTags.BOARD).assertIsDisplayed()
        composeTestRule.onNodeWithTag(StatusRailTestTags.RAIL).assertIsDisplayed()
        composeTestRule.onNodeWithTag(HandTestTags.HAND).assertIsDisplayed()
    }

    @Test
    fun `before the first snapshot the board says so rather than looking finished`() {
        // **The seed, not a null.** `observeGame` opens with an empty `GameState` and the flow emits
        // it immediately, so this is the state the board is actually in while it waits — and a board
        // that keyed the line off having no snapshot at all would never show it. The board still
        // renders behind the line, empty.
        render(GameState(gameId = "g-1"))

        composeTestRule.onNodeWithText(WAITING_FOR_FIRST_SNAPSHOT).assertIsDisplayed()
    }

    @Test
    fun `a game in progress says nothing about waiting`() {
        render(runningGame())

        composeTestRule.onNodeWithText(WAITING_FOR_FIRST_SNAPSHOT).assertDoesNotExist()
    }

    @Test
    fun `a declined join keeps the server's own reason on screen`() {
        render(GameState(gameId = "g-1"), joinError = "table is full")

        composeTestRule.onNodeWithText("Couldn't join the game: table is full").assertIsDisplayed()
    }

    // ---- the question ---------------------------------------------------------------------------

    @Test
    fun `holding priority draws the controls the server's prompt asks for`() {
        render(priorityGame())

        composeTestRule.onNodeWithText(PASS_LABEL).assertIsDisplayed()
    }

    @Test
    fun `nothing outstanding still says so, so a quiet game never reads as a stuck one`() {
        render(runningGame())

        composeTestRule.onNodeWithText(NO_OUTSTANDING_PROMPT).assertIsDisplayed()
    }

    @Test
    fun `hiding the controls cannot hide that the server is waiting`() {
        // The one guarantee the toggle owes: a hidden control set must never become an invisible
        // stall. The collapsed toggle restates it, so the finger that hid the panel is told.
        render(priorityGame(), controlsVisible = false)

        composeTestRule.onNodeWithText(PASS_LABEL).assertDoesNotExist()
        composeTestRule.onNodeWithText(WAITING_ON_YOU_WHILE_HIDDEN).assertIsDisplayed()
    }

    @Test
    fun `nothing on the board is modal`() {
        // §7.4's rule, and the reason the controls float rather than opening a dialog: a board the
        // player cannot look at while answering a question about it is a board they answer blind.
        render(priorityGame())

        composeTestRule.onNode(isDialog()).assertDoesNotExist()
        composeTestRule.onNode(isPopup()).assertDoesNotExist()
        composeTestRule.onRoot().assertIsDisplayed()
    }

    // ---- a press reaches the seam ---------------------------------------------------------------

    @Test
    fun `pressing a card in hand raises it rather than acting on it`() {
        render(priorityGame())

        composeTestRule.onNodeWithTag(HandTestTags.card("h-1")).performClick()

        assertEquals("a press raises the card; the detail commits it", listOf<String?>("h-1"), taps)
        assertTrue("nothing may be sent by a press alone", actions.isEmpty())
    }

    @Test
    fun `the raised card offers the action the server allows, and committing sends it`() {
        // The server said `h-1` is playable, so `controlsFor` offers it and the detail says what a
        // press means. Nothing here decides that — it reads `PromptControlsUi.actionFor`.
        render(priorityGame(), selectedObjectId = "h-1")

        composeTestRule.onNodeWithText(PLAY_ACTION_LABEL).assertIsDisplayed()
        composeTestRule.onNodeWithText(PLAY_ACTION_LABEL).performClick()

        assertEquals(listOf<BoardAction>(BoardAction.PlayObject("h-1")), actions)
    }

    @Test
    fun `a card the server has not offered is raised but has nothing to commit`() {
        // The other half, and the one that matters: the board must not invent an action. `h-2` is in
        // the same hand and is not in `playable`.
        render(priorityGame(), selectedObjectId = "h-2")

        composeTestRule.onNodeWithText(PLAY_ACTION_LABEL).assertDoesNotExist()
    }

    // ---- the exit -------------------------------------------------------------------------------

    @Test
    fun `there is always a way off the board`() {
        // The board has no top bar to go back from — it takes the whole window — so the exit is a
        // control of its own, and it is on screen whatever else is.
        render(priorityGame())

        composeTestRule.onNodeWithTag(TableBoardTestTags.EXIT).performClick()

        assertEquals(1, exits)
    }

    // ---- fixtures -------------------------------------------------------------------------------

    private fun card(
        id: String,
        name: String,
        typeLine: String,
        manaCost: String? = null,
        types: List<CardType> = emptyList(),
    ) = GameCard(
        id = id,
        name = name,
        setCode = "M21",
        collectorNumber = "272",
        manaCost = manaCost,
        typeLine = typeLine,
        cardTypes = types,
    )

    /** A game in progress with the opponent holding priority — the board being watched. */
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
                    // Deliberately opponent-first: player order is not viewer-first, and the board
                    // must not care — it locates seats by `isViewer`.
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
                                    card = card("o-1", "Mountain", "Basic Land — Mountain", types = listOf(CardType.Land)),
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
                        battlefield =
                            listOf(
                                GamePermanent(
                                    card = card("y-1", "Forest", "Basic Land — Forest", types = listOf(CardType.Land)),
                                ),
                            ),
                    ),
                ),
            hand =
                listOf(
                    card("h-1", "Llanowar Elves", "Creature — Elf Druid", "G", listOf(CardType.Creature)),
                    card("h-2", "Grizzly Bears", "Creature — Bear", "1G", listOf(CardType.Creature)),
                ),
        )

    /** The same game with the viewer holding priority and one card offered — the board being played. */
    private fun priorityGame() =
        runningGame().copy(
            viewerHasPriority = true,
            priorityPlayerName = "you",
            playable = listOf(PlayableObject("h-1")),
            prompt = GamePrompt.Select(message = "Select an ability to play"),
        )
}
