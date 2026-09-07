package magefree.feature.game.table

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import magefree.designsystem.theme.MageTheme
import magefree.network.game.GameCommandObject
import magefree.network.game.GameCounter
import magefree.network.game.GamePlayer
import magefree.network.game.GameState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Vitals on the board, and the list behind them.
 *
 * §7.15's argument is that most of this is zero most of the time, *"so it earns its room by asking for
 * almost none until there is something to say"*. The assertion that carries that is the negative one:
 * a strip on an ordinary board shows no counters at all. It is invisible in a screenshot of a board
 * that happens to have poison on it, which is exactly the board anybody builds to check this.
 *
 * The strip's own parts are read from the **unmerged** tree. The strip is one tappable target — a press
 * anywhere on it expands the seat — so its `clickable` merges the chips into a single accessibility
 * node, which is right for a screen reader and hides them from the merged tree.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w891dp-h411dp")
class VitalsStripTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val expanded = mutableListOf<String>()

    private fun show(state: GameState) {
        composeTestRule.setContent {
            MageTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    BattlefieldLayout(
                        model = battlefieldModel(state),
                        vitals = tableVitals(state),
                        onExpandVitals = { expanded += it.playerId },
                    )
                }
            }
        }
    }

    @Test
    fun `both seats get a strip, with life and the library count`() {
        show(twoSeats())

        composeTestRule.onNodeWithTag(VitalsTestTags.strip("me")).assertIsDisplayed()
        composeTestRule.onNodeWithTag(VitalsTestTags.strip("them")).assertIsDisplayed()
        composeTestRule.onNodeWithTag(VitalsTestTags.life("me"), useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithTag(VitalsTestTags.library("me"), useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `a strip per seat in the rail, saying which seat it is by where it is`() {
        // This is what lets the strips carry no name: they are in the status rail, the opponent's at
        // the top of it and the viewer's at the bottom, the same way the two halves of the board are
        // arranged. A label repeating that would cost room the numbers need.
        show(twoSeats())

        val opponent = composeTestRule.onNodeWithTag(VitalsTestTags.strip("them")).fetchSemanticsNode()
        val viewer = composeTestRule.onNodeWithTag(VitalsTestTags.strip("me")).fetchSemanticsNode()
        val rail = composeTestRule.onNodeWithTag(StatusRailTestTags.RAIL).fetchSemanticsNode()

        assertTrue(
            "the opponent's strip should be above the viewer's",
            opponent.positionInRoot.y < viewer.positionInRoot.y,
        )
        listOf("the opponent's" to opponent, "the viewer's" to viewer).forEach { (who, strip) ->
            assertTrue(
                "$who strip should be inside the rail, which ends at ${rail.positionInRoot.x + rail.size.width}",
                strip.positionInRoot.x >= rail.positionInRoot.x &&
                    strip.positionInRoot.x < rail.positionInRoot.x + rail.size.width,
            )
        }
    }

    @Test
    fun `the bars carry no player name`() {
        show(twoSeats())

        composeTestRule.onNodeWithText("Me").assertDoesNotExist()
        composeTestRule.onNodeWithText("Them").assertDoesNotExist()
    }

    @Test
    fun `an ordinary board shows no counter chips at all`() {
        show(twoSeats())

        composeTestRule.onNodeWithTag(VitalsTestTags.counter("me", "poison"), useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onNodeWithTag(VitalsTestTags.counter("me", "energy"), useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `poison gets a chip as soon as there is any`() {
        show(twoSeats(counters = listOf(GameCounter("poison", 3))))

        composeTestRule.onNodeWithTag(VitalsTestTags.counter("me", "poison"), useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `an empty zone shows no count for it`() {
        // The same rule as the counters, and the same reason: a graveyard of nothing is not news.
        show(twoSeats())

        composeTestRule.onNodeWithTag(VitalsTestTags.graveyard("me"), useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onNodeWithTag(VitalsTestTags.exile("me"), useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `monarch and initiative appear only when they are held`() {
        show(twoSeats())
        composeTestRule.onNodeWithTag(VitalsTestTags.monarch("me"), useUnmergedTree = true).assertDoesNotExist()

        composeTestRule.onNodeWithTag(VitalsTestTags.strip("me")).performClick()
        assertEquals(listOf("me"), expanded)
    }

    @Test
    fun `expanding names every counter and lists what is acting on the game`() {
        val state =
            twoSeats(
                counters = listOf(GameCounter("poison", 3), GameCounter("energy", 2)),
                isMonarch = true,
                designationNames = listOf("City's Blessing"),
                commandList = listOf(GameCommandObject(id = "e1", name = "Emblem — Elspeth")),
            )
        composeTestRule.setContent {
            MageTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    VitalsOverlay(vitals = tableVitals(state).first { it.isViewer }, onDismiss = { expanded += "closed" })
                }
            }
        }

        composeTestRule.onNodeWithTag(VitalsOverlayTestTags.counter("poison")).assertIsDisplayed()
        composeTestRule.onNodeWithTag(VitalsOverlayTestTags.counter("energy")).assertIsDisplayed()
        composeTestRule.onNodeWithTag(VitalsOverlayTestTags.designation("Monarch")).assertIsDisplayed()
        composeTestRule.onNodeWithTag(VitalsOverlayTestTags.designation("City's Blessing")).assertIsDisplayed()
        composeTestRule.onNodeWithTag(VitalsOverlayTestTags.command("Emblem — Elspeth")).assertIsDisplayed()
        composeTestRule.onNodeWithText("Life").assertIsDisplayed()

        // A press outside closes it; a press on the panel does not, for the reason the card preview
        // learned — the panel is where the content is and a stray press must not take it away.
        composeTestRule.onNodeWithTag(VitalsOverlayTestTags.PANEL).performClick()
        assertEquals(emptyList<String>(), expanded)

        composeTestRule.onNodeWithTag(VitalsOverlayTestTags.SCRIM).performTouchInput { click(topLeft) }
        assertEquals(listOf("closed"), expanded)
    }
}

private fun twoSeats(
    counters: List<GameCounter> = emptyList(),
    isMonarch: Boolean = false,
    designationNames: List<String> = emptyList(),
    commandList: List<GameCommandObject> = emptyList(),
) = GameState(
    gameId = "g",
    viewerPlayerId = "me",
    players =
        listOf(
            GamePlayer(
                playerId = "me",
                name = "Me",
                isViewer = true,
                life = 20,
                libraryCount = 30,
                counters = counters,
                isMonarch = isMonarch,
                designationNames = designationNames,
                commandList = commandList,
            ),
            GamePlayer(playerId = "them", name = "Them", life = 18, libraryCount = 28),
        ),
)

private const val SCREEN_WIDTH_PX = 891f
private const val SCREEN_HEIGHT_PX = 411f
