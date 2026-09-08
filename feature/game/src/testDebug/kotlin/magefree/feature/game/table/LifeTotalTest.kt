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
import magefree.network.game.GamePlayer
import magefree.network.game.GameState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A player's life total, which is also how a player is targeted.
 *
 * **The property worth holding is that a player is answered like anything else.** Every other
 * candidate for a target question is a card on the board that says so with a green border; a player
 * was answered from a list of names in the prompt panel instead — a second vocabulary for the same
 * act. What these assert is that the same two facts a card carries (this can answer the question;
 * this already does) are carried here, and that a press sends the **player's own server id**, because
 * that is what upstream targets a player by.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w891dp-h411dp")
class LifeTotalTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val picked = mutableListOf<String>()

    private fun show(state: LifeTotalState) {
        composeTestRule.setContent {
            MageTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    LifeTotal(
                        state = state,
                        onPick = if (state.isPickable) ({ picked += state.playerId }) else null,
                    )
                }
            }
        }
    }

    @Test
    fun `pressing a targetable player answers with their own server id`() {
        show(LifeTotalState(playerId = "p-them", life = 20, isPickable = true))

        composeTestRule.onNodeWithTag(LifeTotalTestTags.total("p-them")).performClick()

        assertEquals(listOf("p-them"), picked)
    }

    @Test
    fun `a player the question is not about cannot be pressed`() {
        // The board decides nothing here — `isPickable` is the prompt's own candidate list — but a
        // life total that answered a question it was not part of would send the server a target it
        // never offered.
        show(LifeTotalState(playerId = "p-them", life = 20, isPickable = false))

        composeTestRule.onNodeWithTag(LifeTotalTestTags.total("p-them")).performClick()

        assertEquals(emptyList<String>(), picked)
    }

    @Test
    fun `the total is on screen whether or not anything is being targeted`() {
        // It left the status rail to be read, not only to be pressed.
        show(LifeTotalState(playerId = "p-me", life = 12))

        composeTestRule.onNodeWithTag(LifeTotalTestTags.total("p-me")).assertIsDisplayed()
    }

    @Test
    fun `each seat's life carries what the prompt says about that player`() {
        val state =
            GameState(
                gameId = "g",
                viewerPlayerId = "p-me",
                players =
                    listOf(
                        GamePlayer(playerId = "p-them", name = "Them", life = 20),
                        GamePlayer(playerId = "p-me", name = "Me", isViewer = true, life = 12),
                    ),
            )

        val totals =
            lifeTotals(
                tableVitals(state),
                PromptPicks(pickable = setOf("p-them"), picked = setOf("p-them")),
            )

        assertEquals(listOf("p-them"), totals.opponents.map { it.playerId })
        assertEquals("p-me", totals.viewer?.playerId)
        assertEquals(20, totals.opponents.single().life)
        assertTrue(totals.opponents.single().isPickable)
        assertTrue(totals.opponents.single().isSelected)
        assertFalse("the viewer was not offered", totals.viewer!!.isPickable)
    }
}
