package magefree.feature.game.table

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import magefree.designsystem.card.CardDisplay
import magefree.designsystem.theme.MageTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A graveyard, opened.
 *
 * Opening a zone is a look, so the assertions are about it behaving like every other floating surface:
 * every card is there, a press outside puts it down, and a press on a card opens the card. The one
 * that would be easy to get wrong is the last two together — a scrim wrapped *around* the panel makes
 * every press inside it dismiss, which is a bug that only shows when somebody taps a card.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w891dp-h411dp")
class GraveyardOverlayTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private var dismissed = 0
    private val inspected = mutableListOf<String>()

    private fun show(zone: TableGraveyard) {
        composeTestRule.setContent {
            MageTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    GraveyardOverlay(
                        graveyard = zone,
                        onDismiss = { dismissed += 1 },
                        onInspect = { inspected += it },
                    )
                }
            }
        }
    }

    @Test
    fun `every card in the zone is there, in the server's order`() {
        show(zone())

        listOf("gy1", "gy2", "gy3").forEach { id ->
            composeTestRule.onNodeWithTag(GraveyardOverlayTestTags.card(id)).assertIsDisplayed()
        }
    }

    @Test
    fun `a press outside puts it down`() {
        show(zone())

        // A corner, not the centre: the centre of the scrim is behind the panel, and the point of the
        // scrim is the part of it that is *not*.
        composeTestRule.onNodeWithTag(GraveyardOverlayTestTags.SCRIM).performTouchInput {
            click(Offset(4f, 4f))
        }

        assertEquals(1, dismissed)
    }

    @Test
    fun `a press on a card opens the card, and does not put the zone down`() {
        show(zone())

        composeTestRule.onNodeWithTag(GraveyardOverlayTestTags.card("gy2")).performClick()

        assertEquals(listOf("gy2"), inspected)
        assertEquals("the zone should still be open", 0, dismissed)
    }

    @Test
    fun `an opened but empty graveyard says so rather than showing a blank panel`() {
        show(TableGraveyard(playerId = "me", isViewer = true))

        composeTestRule.onNodeWithTag(GraveyardOverlayTestTags.EMPTY).assertIsDisplayed()
    }

    private fun zone() =
        TableGraveyard(
            playerId = "me",
            isViewer = true,
            cards =
                listOf(
                    TableCard(id = "gy1", card = CardDisplay(name = "Llanowar Elves")),
                    TableCard(id = "gy2", card = CardDisplay(name = "Rod of Ruin")),
                    TableCard(id = "gy3", card = CardDisplay(name = "Serra Angel")),
                ),
        )
}
