package magefree.feature.game.table

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import magefree.designsystem.theme.MageTheme
import magefree.feature.cards.PlaceholderCardArtRenderer
import magefree.feature.game.board.BoardUi
import magefree.feature.game.board.GameBoardUiState
import magefree.feature.game.board.controlsFor
import magefree.network.game.CardType
import magefree.network.game.GameCard
import magefree.network.game.GamePlayer
import magefree.network.game.GameState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A spell the server resolves at once is still seen on the board's stack.
 *
 * [StackDwellTest] proves the presented stack keeps it; this proves the board draws the presented stack
 * rather than the server's — the wiring a unit test of the function cannot see.
 *
 * The clock is held from the first frame, because an idling test would let the spell's time on the stack
 * run out before the snapshot that resolves it arrives.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w891dp-h411dp")
class StackDwellBoardTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val snapshot = mutableStateOf(GameState(gameId = GAME))

    /** Announces what was written and draws it — a held clock idles nothing on its own. */
    private fun settle(frames: Int = 3) {
        Snapshot.sendApplyNotifications()
        repeat(frames) { composeTestRule.mainClock.advanceTimeByFrame() }
    }

    @Test
    fun `a spell that has just resolved is still drawn on the stack, and then goes`() {
        composeTestRule.mainClock.autoAdvance = false
        snapshot.value = table(stack = listOf(bolt()))
        composeTestRule.setContent {
            MageTheme {
                val state = snapshot.value
                TableBoardScreen(
                    uiState =
                        GameBoardUiState(
                            board = BoardUi.from(state),
                            snapshot = state,
                            isJoining = false,
                            controls = controlsFor(state),
                        ),
                    onExit = {},
                    onControlsVisibleChange = {},
                    onCardTap = {},
                    onAction = {},
                    artRenderer = PlaceholderCardArtRenderer,
                )
            }
        }
        settle()
        composeTestRule.onNodeWithTag(StackTestTags.entry(SPELL), useUnmergedTree = true).assertExists()

        // The server resolves it: the stack is empty in the very next snapshot.
        snapshot.value = table(stack = emptyList())
        settle()

        composeTestRule.onNodeWithTag(StackTestTags.entry(SPELL), useUnmergedTree = true).assertExists()

        composeTestRule.mainClock.advanceTimeBy(1_000)
        settle()

        composeTestRule.onNodeWithTag(StackTestTags.entry(SPELL), useUnmergedTree = true).assertDoesNotExist()
    }

    private fun table(stack: List<GameCard>) =
        GameState(
            gameId = GAME,
            hasSnapshot = true,
            turn = 3,
            viewerPlayerId = ME,
            activePlayerId = THEM,
            stack = stack,
            players =
                listOf(
                    GamePlayer(playerId = THEM, name = "Computer", life = 20, libraryCount = 40, handCount = 4, isHuman = false),
                    GamePlayer(playerId = ME, name = "you", isViewer = true, life = 20, libraryCount = 40),
                ),
        )

    private fun bolt() =
        GameCard(
            id = SPELL,
            name = "Lightning Bolt",
            setCode = "10E",
            collectorNumber = "203",
            manaCost = "{R}",
            cardTypes = listOf(CardType.Instant),
            rules = listOf("Lightning Bolt deals 3 damage to any target."),
        )
}

private const val GAME = "g-1"
private const val ME = "p-you"
private const val THEM = "p-opp"
private const val SPELL = "spell-1"
