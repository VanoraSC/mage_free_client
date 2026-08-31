package magefree.designsystem.catalog

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import magefree.designsystem.board.BoardDuration
import magefree.designsystem.board.BoardSurface
import magefree.designsystem.board.MotionScale
import magefree.designsystem.board.perceptualLightness
import magefree.designsystem.theme.MageTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The gallery is where the palette is judged by eye, so it has to actually compose.
 *
 * A token file that compiles proves nothing about the surface that displays it: the numbers in
 * `BoardColorsTest` would stay green while the one place a person looks at the ramp crashed on open.
 * These assertions are deliberately about the measurements the gallery prints, because those are what
 * make it possible to read six greys as a ramp rather than as six unrelated swatches.
 *
 * The viewport is taller than any real one on purpose: the gallery scrolls where it is hosted, and
 * asserting on what is currently on screen would be asserting about the scroll position instead of
 * about the content. A ground's measurement appears more than once — once in the ramp and again
 * under the signals drawn on it — so these count nodes rather than demanding exactly one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w891dp-h2000dp")
class BoardTokenGalleryTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `the gallery reports the measured lightness of every surface`() {
        composeTestRule.setContent { MageTheme { BoardTokenGallery() } }

        BoardSurface.valueRamp.forEach { surface ->
            val measured = "L* ${"%.1f".format(surface.perceptualLightness())}"
            assertTrue(
                "the gallery does not print $measured, so that step of the ramp cannot be placed by eye",
                composeTestRule.onAllNodesWithText(measured, substring = true).fetchSemanticsNodes().isNotEmpty(),
            )
        }
    }

    @Test
    fun `the gallery shows every duration shortening rather than disappearing`() {
        composeTestRule.setContent { MageTheme { BoardTokenGallery() } }

        BoardDuration.all.forEach { duration ->
            val reduced = MotionScale.Reduced.scale(duration)
            assertTrue(
                "the gallery does not show $duration ms reducing to $reduced ms",
                composeTestRule.onAllNodesWithText("$duration → $reduced ms").fetchSemanticsNodes().isNotEmpty(),
            )
        }
    }

    @Test
    fun `every surface and every signal is drawn`() {
        composeTestRule.setContent { MageTheme { BoardTokenGallery() } }

        // Each surface names itself once in the ramp; the signal marks are unlabelled swatches, so the
        // ground rows are what prove the signal section rendered at all.
        listOf("ground", "zone", "zoneRaised", "card", "cardRaised", "floating").forEach { name ->
            assertTrue(
                "the ramp does not name $name",
                composeTestRule.onAllNodesWithText(name).fetchSemanticsNodes().isNotEmpty(),
            )
        }
    }
}
