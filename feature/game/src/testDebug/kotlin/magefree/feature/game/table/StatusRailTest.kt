package magefree.feature.game.table

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
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
 * It is a column of numbers and nothing else. It drew the top card of every pile for a while, which
 * cost four card-heights of a column one card wide and left every pile too small to read — so the
 * assertions here are about what replaced that: the counts are on the rail, the rail is narrow, and
 * pressing a seat is what gets you the cards.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w891dp-h411dp")
class StatusRailTest {
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
    fun `every zone a player has a count of is on the rail`() {
        show(twoSeats())

        listOf(
            VitalsTestTags.hand("me"),
            VitalsTestTags.library("me"),
            VitalsTestTags.graveyard("me"),
            VitalsTestTags.exile("me"),
        ).forEach { tag ->
            composeTestRule.onNodeWithTag(tag, useUnmergedTree = true).assertIsDisplayed()
        }
    }

    @Test
    fun `an empty library still shows, because an empty library is a game state`() {
        // Most counts disappear at zero. This one is a loss on the next draw, so it does not — and
        // neither does exile, for 0123's own reason: an empty exile is a thing a player goes looking
        // for. What still vanishes is the graveyard, which is the ordinary case.
        show(twoSeats(libraryCount = 0, exileCount = 0, graveyardCount = 0))

        composeTestRule.onNodeWithTag(VitalsTestTags.library("me"), useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithTag(VitalsTestTags.exile("me"), useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithTag(VitalsTestTags.graveyard("me"), useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `pressing a seat opens that seat`() {
        show(twoSeats())

        composeTestRule.onNodeWithTag(VitalsTestTags.strip("them")).performClick()

        assertEquals(listOf("them"), expanded)
    }

    @Test
    fun `the rail is one narrow column on the left, clear of the battlefield`() {
        // Narrow is the point: it holds numbers, and it held a card's width while it was drawing the
        // top of every pile.
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
        assertTrue("a rail ${rail.size.width}px wide is not a column of numbers", rail.size.width <= MAX_RAIL_PX)
    }

    @Test
    fun `the viewer's numbers are at the bottom and the opponent's at the top`() {
        show(twoSeats())

        val mine = composeTestRule.onNodeWithTag(VitalsTestTags.strip("me")).fetchSemanticsNode()
        val theirs = composeTestRule.onNodeWithTag(VitalsTestTags.strip("them")).fetchSemanticsNode()

        assertTrue("the opponent's numbers should be above mine", theirs.positionInRoot.y < mine.positionInRoot.y)
    }

    private fun twoSeats(
        libraryCount: Int = 30,
        graveyardCount: Int = 2,
        exileCount: Int = 1,
    ) = GameState(
        gameId = "g1",
        players =
            listOf(
                GamePlayer(
                    playerId = "me",
                    name = "You",
                    isViewer = true,
                    life = 20,
                    libraryCount = libraryCount,
                    handCount = 4,
                    graveyardCount = graveyardCount,
                    exileCount = exileCount,
                    battlefield = listOf(GamePermanent(card = card("bears", "Grizzly Bears"))),
                ),
                GamePlayer(
                    playerId = "them",
                    name = "Opponent",
                    life = 20,
                    libraryCount = libraryCount,
                    battlefield = listOf(GamePermanent(card = card("wurm", "Craw Wurm"))),
                ),
            ),
    )

    private fun card(
        id: String,
        name: String,
    ) = GameCard(id = id, name = name, cardTypes = listOf(CardType.Creature), isCreature = true)

    private companion object {
        /** A column of four numbers and a life total. Past this it is holding something else. */
        const val MAX_RAIL_PX = 100
    }
}
