package magefree.feature.game.table

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import magefree.designsystem.theme.MageTheme
import magefree.network.game.CardType
import magefree.network.game.GameCard
import magefree.network.game.GamePermanent
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
 * The status rail on the board.
 *
 * The rail is the one region that keeps its height when it is empty, and that is the assertion worth
 * having: an empty graveyard draws a named placeholder the size of the card that is not there. A rail
 * that drew nothing would look correct on every board that has had something die on it, which is every
 * board anybody builds to check this.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w891dp-h411dp")
class StatusRailTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val opened = mutableListOf<String>()

    private fun show(state: GameState) {
        composeTestRule.setContent {
            MageTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    BattlefieldLayout(
                        model = battlefieldModel(state),
                        vitals = tableVitals(state),
                        graveyards = tableGraveyards(state),
                        onOpenGraveyard = { opened += it },
                    )
                }
            }
        }
    }

    @Test
    fun `an empty graveyard is a named placeholder, not nothing`() {
        show(twoSeats())

        composeTestRule
            .onNodeWithTag(StatusRailTestTags.graveyardPlaceholder("them"), useUnmergedTree = true)
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(GRAVEYARD_LABEL).assertIsDisplayed()
    }

    @Test
    fun `a graveyard with cards in it draws the one on top, and how many there are`() {
        show(twoSeats())

        composeTestRule.onNodeWithTag(StatusRailTestTags.graveyard("me")).assertIsDisplayed()
        composeTestRule
            .onNodeWithTag(StatusRailTestTags.graveyardCount("me"), useUnmergedTree = true)
            .assertIsDisplayed()
    }

    @Test
    fun `pressing a graveyard asks to open it — including an empty one`() {
        // An empty zone is an answer, and a control that only sometimes responds teaches the player
        // not to trust it.
        show(twoSeats())

        composeTestRule.onNodeWithTag(StatusRailTestTags.graveyard("me")).performClick()
        composeTestRule
            .onNodeWithTag(StatusRailTestTags.graveyardPlaceholder("them"), useUnmergedTree = true)
            .performClick()

        assertEquals(listOf("me", "them"), opened)
    }

    @Test
    fun `the rail is one column on the left, clear of the battlefield`() {
        show(twoSeats())

        val rail = composeTestRule.onNodeWithTag(StatusRailTestTags.RAIL).fetchSemanticsNode()
        val creatures =
            composeTestRule
                .onNodeWithTag(BattlefieldTestTags.row("me", "front"))
                .fetchSemanticsNode()

        assertTrue(
            "the rail ends at ${rail.positionInRoot.x + rail.size.width}, the creatures start at ${creatures.positionInRoot.x}",
            rail.positionInRoot.x + rail.size.width <= creatures.positionInRoot.x,
        )
    }

    @Test
    fun `the viewer's own status is at the bottom of the rail and the opponent's at the top`() {
        show(twoSeats())

        val mine = composeTestRule.onNodeWithTag(StatusRailTestTags.graveyard("me")).fetchSemanticsNode()
        val theirs =
            composeTestRule
                .onNodeWithTag(StatusRailTestTags.graveyardPlaceholder("them"), useUnmergedTree = true)
                .fetchSemanticsNode()

        assertTrue("the opponent's graveyard should be above mine", theirs.positionInRoot.y < mine.positionInRoot.y)
    }

    private fun twoSeats() =
        GameState(
            gameId = "g1",
            players =
                listOf(
                    GamePlayer(
                        playerId = "me",
                        name = "You",
                        isViewer = true,
                        life = 20,
                        graveyardCount = 2,
                        graveyard = listOf(card("gy1", "Llanowar Elves"), card("gy2", "Serra Angel")),
                        battlefield = listOf(GamePermanent(card = card("bears", "Grizzly Bears"))),
                    ),
                    GamePlayer(
                        playerId = "them",
                        name = "Opponent",
                        life = 20,
                        battlefield = listOf(GamePermanent(card = card("wurm", "Craw Wurm"))),
                    ),
                ),
        )

    private fun card(
        id: String,
        name: String,
    ) = GameCard(id = id, name = name, cardTypes = listOf(CardType.Creature), isCreature = true)
}
