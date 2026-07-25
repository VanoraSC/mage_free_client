package magefree.app.navigation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.testing.TestNavHostController
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import magefree.app.game.EXIT_GAME_CONTENT_DESCRIPTION
import magefree.app.game.IMMERSIVE_GAME_LABEL
import magefree.app.screens.ENTER_GAME_STUB_LABEL
import magefree.app.screens.HOME_TITLE
import magefree.app.theme.MageTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented Compose UI test for the **root** [AppNavHost]. NOT part of the hermetic
 * `./gradlew check` gate — run with a device/emulator via `./gradlew :app:connectedDebugAndroidTest`.
 *
 * It proves story-0011's core flow: the shell (with tab chrome) is the start; the dev stub enters
 * the immersive [GameRoute][magefree.app.game.GameRoute], which shows the full-bleed placeholder
 * with **no** bottom-bar chrome; and both the on-screen exit control and system back return to the
 * shell (restoring the chrome). A stateless connection strip keeps the test free of Hilt.
 */
@RunWith(AndroidJUnit4::class)
class AppNavHostTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun setNavHost() {
        composeTestRule.setContent {
            val navController =
                TestNavHostController(androidx.compose.ui.platform.LocalContext.current).apply {
                    navigatorProvider.addNavigator(ComposeNavigator())
                }
            MageTheme {
                AppNavHost(
                    navController = navController,
                    connectionStatusBar = {},
                )
            }
        }
    }

    private fun enterGameFromShell() {
        // Reach the dev stub via the Settings tab, then enter the immersive route.
        composeTestRule
            .onNodeWithContentDescription(TopLevelDestination.SETTINGS.contentDescription)
            .performClick()
        composeTestRule.onNodeWithText(ENTER_GAME_STUB_LABEL).performClick()
    }

    @Test
    fun startsOnShellWithTabChrome() {
        setNavHost()
        composeTestRule.onNodeWithText(HOME_TITLE).assertIsDisplayed()
        composeTestRule
            .onNodeWithContentDescription(TopLevelDestination.HOME.contentDescription)
            .assertIsDisplayed()
    }

    @Test
    fun enteringGameShowsImmersivePlaceholderWithoutTabChrome() {
        setNavHost()
        enterGameFromShell()

        composeTestRule.onNodeWithText(IMMERSIVE_GAME_LABEL).assertIsDisplayed()
        composeTestRule
            .onNodeWithContentDescription(EXIT_GAME_CONTENT_DESCRIPTION)
            .assertIsDisplayed()
        // The tab chrome must be gone on the immersive route.
        composeTestRule
            .onNodeWithContentDescription(TopLevelDestination.HOME.contentDescription)
            .assertDoesNotExist()
    }

    @Test
    fun exitControlReturnsToShell() {
        setNavHost()
        enterGameFromShell()

        composeTestRule
            .onNodeWithContentDescription(EXIT_GAME_CONTENT_DESCRIPTION)
            .performClick()

        // Back in the shell: tab chrome restored.
        composeTestRule
            .onNodeWithContentDescription(TopLevelDestination.HOME.contentDescription)
            .assertIsDisplayed()
    }

    @Test
    fun systemBackLeavesImmersiveMode() {
        setNavHost()
        enterGameFromShell()

        // System back on the game route leaves the immersive mode (handled by ImmersiveGameScreen's
        // BackHandler -> onExit -> popBackStack).
        Espresso.pressBack()

        composeTestRule
            .onNodeWithContentDescription(TopLevelDestination.HOME.contentDescription)
            .assertIsDisplayed()
    }
}
