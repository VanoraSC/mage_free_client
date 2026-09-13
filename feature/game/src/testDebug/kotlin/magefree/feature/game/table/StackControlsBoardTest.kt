package magefree.feature.game.table

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import magefree.designsystem.theme.MageTheme
import magefree.feature.cards.PlaceholderCardArtRenderer
import magefree.feature.game.board.BoardAction
import magefree.feature.game.board.BoardUi
import magefree.feature.game.board.GameBoardUiState
import magefree.feature.game.board.RESOLVE_STACK_LABEL
import magefree.feature.game.board.TriggerOrderTestTags
import magefree.feature.game.board.controlsFor
import magefree.network.game.GameCard
import magefree.network.game.GamePlayer
import magefree.network.game.GamePrompt
import magefree.network.game.GameState
import magefree.network.game.PromptOptions
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The stack's controls on the real board: the trigger-ordering panel where the question is, the pass that
 * resolves the stack, and the menu that takes standing rules back.
 *
 * The panel and the buttons have tests of their own; this is the one that fails if the board forgets to
 * draw them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w891dp-h411dp")
class StackControlsBoardTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val actions = mutableListOf<BoardAction>()

    private fun render(state: GameState) {
        composeTestRule.setContent {
            MageTheme {
                TableBoardScreen(
                    uiState =
                        GameBoardUiState(
                            board = BoardUi.from(state),
                            snapshot = state,
                            isJoining = false,
                            areControlsVisible = true,
                            controls = controlsFor(state),
                        ),
                    onExit = {},
                    onControlsVisibleChange = {},
                    onCardTap = {},
                    onAction = { actions += it },
                    artRenderer = PlaceholderCardArtRenderer,
                )
            }
        }
    }

    @Test
    fun `a trigger-ordering question draws the panel, and one press sends the arrangement`() {
        render(game(prompt = triggerQuestion()))

        composeTestRule.onNodeWithTag(TriggerOrderTestTags.PANEL, useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag(TriggerOrderTestTags.CONFIRM).performScrollTo().performClick()

        assertEquals(listOf(BoardAction.OrderTriggers(resolveOrder = listOf("t1", "t2", "t3"))), actions)
    }

    @Test
    fun `with a spell on the stack, resolving it is one press`() {
        render(
            game(
                prompt = GamePrompt.Select(message = "Play spells and abilities"),
                stack = listOf(GameCard(id = "s1", name = "Lightning Bolt")),
            ),
        )

        composeTestRule.onNodeWithText(RESOLVE_STACK_LABEL).performScrollTo().performClick()

        assertEquals(listOf<BoardAction>(BoardAction.ResolveStack), actions)
    }

    @Test
    fun `the corner menu takes back always-first and always-yes rules`() {
        render(game(prompt = GamePrompt.Select(message = "Play spells and abilities")))

        composeTestRule.onNodeWithTag(TableBoardTestTags.MENU).performClick()
        composeTestRule.onNodeWithTag(TableBoardTestTags.RESET_TRIGGER_ORDER).performClick()
        composeTestRule.onNodeWithTag(TableBoardTestTags.MENU).performClick()
        composeTestRule.onNodeWithTag(TableBoardTestTags.RESET_AUTO_ANSWERS).performClick()

        assertEquals(listOf(BoardAction.ResetTriggerOrder, BoardAction.ResetAutoAnswers), actions)
    }

    private fun triggerQuestion() =
        GamePrompt.Target(
            message = "Pick triggered ability (goes to the stack first)",
            cards =
                listOf(
                    GameCard(
                        id = "t1",
                        name = "Soul Warden",
                        rules = listOf("Whenever another creature enters the battlefield, you gain 1 life."),
                    ),
                    GameCard(
                        id = "t2",
                        name = "Soul Warden",
                        rules = listOf("Whenever another creature enters the battlefield, you gain 1 life."),
                    ),
                    GameCard(
                        id = "t3",
                        name = "Auriok Champion",
                        rules = listOf("Whenever another creature enters the battlefield, you may gain 1 life."),
                    ),
                ),
            targetIds = listOf("t1", "t2", "t3"),
            isRequired = true,
            options = PromptOptions(text = mapOf(PromptOptions.QUERY_TYPE to PromptOptions.PICK_ABILITY)),
        )

    private fun game(
        prompt: GamePrompt,
        stack: List<GameCard> = emptyList(),
    ) = GameState(
        gameId = "g-1",
        hasSnapshot = true,
        turn = 3,
        viewerPlayerId = "p-you",
        activePlayerId = "p-you",
        viewerHasPriority = true,
        prompt = prompt,
        stack = stack,
        players =
            listOf(
                GamePlayer(playerId = "p-opp", name = "Computer", life = 20, libraryCount = 40, handCount = 4, isHuman = false),
                GamePlayer(playerId = "p-you", name = "you", isViewer = true, life = 20, libraryCount = 40),
            ),
    )
}
