package magefree.app.navigation

import androidx.compose.material3.Button
import androidx.compose.material3.Text
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
import magefree.designsystem.theme.MageTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented Compose UI test for the **root** [AppNavHost]. NOT part of the hermetic
 * `./gradlew check` gate — run with a device/emulator via `./gradlew :app:connectedDebugAndroidTest`.
 *
 * It proves the core flow: the shell (with tab chrome) is reached from the connect flow; the
 * dev stub enters the immersive [GameRoute][magefree.app.game.GameRoute], which shows the full-bleed
 * placeholder with **no** bottom-bar chrome; and both the on-screen exit control and system back
 * return to the shell (restoring the chrome).
 *
 * A stateless connection strip and a stateless stand-in for the connect flow (made
 * [ConnectRoute] the start destination) keep the test free of Hilt. The cold-start entry policy itself
 * is covered hermetically by `ConnectEntryReachabilityTest` in `:app`'s unit tests.
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
                    connectFlow = { onConnected ->
                        Button(onClick = onConnected) { Text(CONNECT_STAND_IN_LABEL) }
                    },
                )
            }
        }
    }

    /** Cold launch lands on the connect flow (0047); sign in to get into the shell. */
    private fun signInToShell() {
        composeTestRule.onNodeWithText(CONNECT_STAND_IN_LABEL).performClick()
    }

    private fun enterGameFromShell() {
        // Reach the dev stub via the Settings tab, then enter the immersive route.
        composeTestRule
            .onNodeWithContentDescription(TopLevelDestination.SETTINGS.contentDescription)
            .performClick()
        composeTestRule.onNodeWithText(ENTER_GAME_STUB_LABEL).performClick()
    }

    @Test
    fun startsOnConnectAndSigningInShowsTheShellWithTabChrome() {
        setNavHost()

        // a launch with no session begins on the connect flow, outside the shell chrome.
        composeTestRule.onNodeWithText(CONNECT_STAND_IN_LABEL).assertIsDisplayed()
        composeTestRule.onNodeWithText(HOME_TITLE).assertDoesNotExist()

        signInToShell()
        composeTestRule.onNodeWithText(HOME_TITLE).assertIsDisplayed()
        composeTestRule
            .onNodeWithContentDescription(TopLevelDestination.HOME.contentDescription)
            .assertIsDisplayed()
    }

    @Test
    fun enteringGameShowsImmersivePlaceholderWithoutTabChrome() {
        setNavHost()
        signInToShell()
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
        signInToShell()
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
        signInToShell()
        enterGameFromShell()

        // System back on the game route leaves the immersive mode (handled by ImmersiveGameScreen's
        // BackHandler -> onExit -> popBackStack).
        Espresso.pressBack()

        composeTestRule
            .onNodeWithContentDescription(TopLevelDestination.HOME.contentDescription)
            .assertIsDisplayed()
    }

    private companion object {
        /** Marker rendered by the Hilt-free stand-in for `ConnectFlow`. */
        const val CONNECT_STAND_IN_LABEL = "connect-flow-stand-in"
    }
}
