package magefree.app.game

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import magefree.designsystem.theme.MageTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented Compose UI test for [ImmersiveGameScreen]. NOT part of the hermetic `./gradlew check`
 * gate — run with a device/emulator via `./gradlew :app:connectedDebugAndroidTest`.
 *
 * Drives the stateless screen with a tracking callback so the placeholder surface, and the exit
 * control's presence, labelling, touch-target size, and callback wiring, are verifiable without the
 * navigation graph.
 */
@RunWith(AndroidJUnit4::class)
class ImmersiveGameScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private var exitClicks = 0

    private fun setScreen() {
        composeTestRule.setContent {
            MageTheme {
                ImmersiveGameScreen(onExit = { exitClicks++ })
            }
        }
    }

    @Test
    fun showsPlaceholderSurface() {
        setScreen()
        composeTestRule.onNodeWithText(IMMERSIVE_GAME_LABEL).assertIsDisplayed()
    }

    @Test
    fun exitControlIsPresentLabelledAndInvokesCallback() {
        setScreen()

        val exit = composeTestRule.onNodeWithContentDescription(EXIT_GAME_CONTENT_DESCRIPTION)
        exit.assertIsDisplayed()
        exit.assertHasClickAction()

        exit.performClick()
        assertEquals(1, exitClicks)
    }

    @Test
    fun exitControlMeetsMinimumTouchTarget() {
        setScreen()
        composeTestRule
            .onNodeWithContentDescription(EXIT_GAME_CONTENT_DESCRIPTION)
            .assertHeightIsAtLeast(48.dp)
    }
}
