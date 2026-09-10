package magefree.feature.game.table

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import magefree.designsystem.card.CardDisplay
import magefree.designsystem.theme.MageTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * One pile, opened.
 *
 * **The assertions that matter are about order.** A graveyard is not a set — what died last is on top
 * — and half the reason to open one is to answer *what just went there*. A viewer that showed the
 * right cards in the wrong order would look entirely correct and answer that question backwards.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w891dp-h411dp")
class ZoneViewerTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val dismissed = mutableListOf<Unit>()
    private val inspected = mutableListOf<String>()

    private fun show(vararg piles: TableZonePile) {
        composeTestRule.setContent {
            MageTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    ZoneViewer(
                        piles = piles.toList(),
                        onDismiss = { dismissed += Unit },
                        onInspect = { inspected += it },
                    )
                }
            }
        }
    }

    @Test
    fun `the card on top of the pile is the one at the top of the list`() {
        // The server's order is oldest first, so the list reverses it. This is the one thing a player
        // opens a graveyard to find out, and it is the one thing a plain rendering gets backwards.
        show(graveyard())

        val angel = composeTestRule.onNodeWithTag(ZoneViewerTestTags.card("gy-3")).fetchSemanticsNode()
        val elves = composeTestRule.onNodeWithTag(ZoneViewerTestTags.card("gy-1")).fetchSemanticsNode()

        assertTrue(
            "the last card to enter should be drawn above the first",
            angel.positionInRoot.y < elves.positionInRoot.y,
        )
    }

    @Test
    fun `every card in the pile is in the list`() {
        show(graveyard())

        listOf("gy-1", "gy-2", "gy-3").forEach { id ->
            composeTestRule.onNodeWithTag(ZoneViewerTestTags.card(id)).assertExists()
        }
    }

    @Test
    fun `the count is shown, because the picture is not a number`() {
        // 0123 is explicit that the top card does not replace the count anywhere it appears. Here the
        // list could be scrolled halfway and the number is still the answer to *how many*.
        show(graveyard())

        composeTestRule.onNodeWithTag(ZoneViewerTestTags.count(TableZoneKind.Graveyard)).assertIsDisplayed()
    }

    @Test
    fun `an empty pile says so rather than opening onto nothing`() {
        // An empty graveyard is a real answer about the game. A panel with nothing in it reads as a
        // failure to load, which is the one thing it must not be mistaken for.
        show(graveyard(cards = emptyList()))

        composeTestRule.onNodeWithTag(ZoneViewerTestTags.empty(TableZoneKind.Graveyard)).assertIsDisplayed()
    }

    @Test
    fun `a card in a pile opens the same detail a card on the battlefield opens`() {
        // One gesture everywhere. A target the server offered out of a graveyard has to be answerable
        // from where the player found it.
        show(graveyard())

        composeTestRule.onNodeWithTag(ZoneViewerTestTags.card("gy-2")).performClick()

        assertEquals(listOf("gy-2"), inspected)
    }

    @Test
    fun `several piles open side by side, each with its own name and count`() {
        // What a press on the counts produces. Side by side rather than one list with headings,
        // because these are *different piles*: run together, "what is in exile" becomes a question
        // about how far down you had scrolled.
        show(
            pile(TableZoneKind.Exile, listOf(tableCard("ex-1", "Chandra, Torch of Defiance"))),
            pile(TableZoneKind.Revealed, listOf(tableCard("rev-1", "Shivan Dragon"))),
        )

        composeTestRule.onNodeWithTag(ZoneViewerTestTags.title(TableZoneKind.Exile)).assertIsDisplayed()
        composeTestRule.onNodeWithTag(ZoneViewerTestTags.title(TableZoneKind.Revealed)).assertIsDisplayed()
        composeTestRule.onNodeWithTag(ZoneViewerTestTags.card("ex-1")).assertExists()
        composeTestRule.onNodeWithTag(ZoneViewerTestTags.card("rev-1")).assertExists()
    }

    @Test
    fun `an empty exile beside a full pile still says it is empty`() {
        // The case a press on the counts produces most often: exile is offered whether or not there is
        // anything in it, so its column has to be able to say *nothing here* while its neighbour is
        // full — an absent column would read as the pile not existing.
        show(
            pile(TableZoneKind.Exile, emptyList()),
            pile(TableZoneKind.Revealed, listOf(tableCard("rev-1", "Shivan Dragon"))),
        )

        composeTestRule.onNodeWithTag(ZoneViewerTestTags.empty(TableZoneKind.Exile)).assertIsDisplayed()
        composeTestRule.onNodeWithTag(ZoneViewerTestTags.card("rev-1")).assertExists()
    }

    @Test
    fun `a press outside puts it down, and a press inside does not`() {
        show(graveyard())

        // The panel is where the content is and a stray press must not take it away — the same rule
        // the card preview learned, and the reason the scrim is a sibling behind the panel rather
        // than a wrapper around it.
        composeTestRule.onNodeWithTag(ZoneViewerTestTags.PANEL).performClick()
        assertEquals(0, dismissed.size)

        // In a corner, deliberately: the scrim fills the screen and its centre is behind the panel,
        // so a press there would land on the panel and prove nothing.
        composeTestRule.onNodeWithTag(ZoneViewerTestTags.SCRIM).performTouchInput { click(topLeft) }
        assertEquals(1, dismissed.size)
    }

    private fun graveyard(
        cards: List<TableCard> =
            listOf(
                tableCard("gy-1", "Llanowar Elves"),
                tableCard("gy-2", "Rod of Ruin"),
                tableCard("gy-3", "Serra Angel"),
            ),
    ) = TableZonePile(
        playerId = "me",
        isViewer = true,
        kind = TableZoneKind.Graveyard,
        cards = cards,
    )

    private fun pile(
        kind: TableZoneKind,
        cards: List<TableCard>,
    ) = TableZonePile(playerId = "me", isViewer = true, kind = kind, cards = cards)

    private fun tableCard(
        id: String,
        name: String,
    ) = TableCard(id = id, card = CardDisplay(name = name))
}
