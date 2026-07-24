package magefree.app.screens

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
 * Instrumented Compose UI test for [HomeScreen]. NOT part of the hermetic `./gradlew check` gate —
 * run with a device/emulator via `./gradlew :app:connectedDebugAndroidTest`.
 *
 * Drives the stateless [HomeScreen] with tracking callbacks so the Play CTA's presence, labelling,
 * touch-target size, and callback wiring — plus the secondary entries' stubs — are all verifiable
 * without the navigation shell.
 */
@RunWith(AndroidJUnit4::class)
class HomeScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private var playClicks = 0
    private var decksClicks = 0
    private var profileClicks = 0
    private var settingsClicks = 0

    private fun setHome() {
        composeTestRule.setContent {
            MageTheme {
                HomeScreen(
                    onPlayClick = { playClicks++ },
                    onDecksClick = { decksClicks++ },
                    onProfileClick = { profileClicks++ },
                    onSettingsClick = { settingsClicks++ },
                )
            }
        }
    }

    @Test
    fun playCtaIsPresentLabelledAndInvokesCallback() {
        setHome()

        val play = composeTestRule.onNodeWithContentDescription(HOME_PLAY_CONTENT_DESCRIPTION)
        play.assertIsDisplayed()
        play.assertHasClickAction()

        play.performClick()
        assertEquals(1, playClicks)
    }

    @Test
    fun playCtaMeetsMinimumTouchTarget() {
        setHome()

        // Comfortably above the 48dp accessibility floor; the Play CTA is the emphasized element.
        composeTestRule
            .onNodeWithContentDescription(HOME_PLAY_CONTENT_DESCRIPTION)
            .assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun secondaryEntriesInvokeTheirStubs() {
        setHome()

        composeTestRule.onNodeWithText("Decks").performClick()
        composeTestRule.onNodeWithText("Profile").performClick()
        composeTestRule.onNodeWithText("Settings").performClick()

        assertEquals(1, decksClicks)
        assertEquals(1, profileClicks)
        assertEquals(1, settingsClicks)
        // Secondary taps must not trigger the primary action.
        assertEquals(0, playClicks)
    }
}
