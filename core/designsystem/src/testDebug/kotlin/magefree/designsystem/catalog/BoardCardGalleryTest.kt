package magefree.designsystem.catalog

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import magefree.designsystem.card.BoardCardSignal
import magefree.designsystem.theme.MageTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Board tier's gallery has to compose, because it is where the tier is judged by eye.
 *
 * `BoardCardTest` proves the component behaves; this proves the surface that displays every state
 * actually renders one card per signal. A gallery that silently dropped a state would leave a signal
 * unreviewed while every component test stayed green.
 *
 * The viewport is taller and wider than a real one on purpose: the gallery scrolls where it is hosted,
 * so asserting on what is currently visible would be asserting about scroll position.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w2000dp-h2000dp")
class BoardCardGalleryTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `every signal gets its own labelled card`() {
        composeTestRule.setContent { MageTheme { BoardCardGallery() } }

        BoardCardSignal.entries.forEach { signal ->
            assertTrue(
                "the gallery does not show ${signal.name} on its own, so it cannot be judged",
                composeTestRule.onAllNodesWithText(signal.name.lowercase()).fetchSemanticsNodes().isNotEmpty(),
            )
        }
    }

    @Test
    fun `the states that are not signals are shown too`() {
        composeTestRule.setContent { MageTheme { BoardCardGallery() } }

        listOf("resting", "tapped", "counters", "no stats").forEach { label ->
            assertTrue(
                "the gallery does not show the $label state",
                composeTestRule.onAllNodesWithText(label, substring = true).fetchSemanticsNodes().isNotEmpty(),
            )
        }
    }
}
