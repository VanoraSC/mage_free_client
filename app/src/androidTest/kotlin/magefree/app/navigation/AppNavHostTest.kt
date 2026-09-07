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
import androidx.test.ext.junit.runners.AndroidJUnit4
import magefree.app.screens.HOME_TITLE
import magefree.designsystem.theme.MageTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented Compose UI test for the **root** [AppNavHost]. NOT part of the hermetic
 * `./gradlew check` gate — run with a device/emulator via `./gradlew :app:connectedDebugAndroidTest`.
 *
 * It proves that a launch with no session begins on the connect flow, and that signing in reaches the
 * shell with its tab chrome.
 *
 * **The game route is no longer driven from here.** It used to be: a dev stub on the Settings screen
 * navigated into a full-bleed placeholder, and three tests here checked that the chrome disappeared
 * and that both exits came back. 0112 replaced the placeholder with the real board and the stub with
 * the table room's match-start hand-off — which needs a live session and a server, so it is not a path
 * an instrumented test can walk. What those tests were protecting is covered without a device now:
 * `FeatureDestinationWiringTest` proves the root graph mounts the board, `GameRouteTest` proves the
 * route is not one of the tabs (which is what keeps the chrome off it), and `TableBoardScreenTest`
 * proves the board renders and that there is always a way off it.
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
                    connectFlow = { onConnected, _, _ ->
                        Button(onClick = onConnected) { Text(CONNECT_STAND_IN_LABEL) }
                    },
                )
            }
        }
    }

    @Test
    fun startsOnConnectAndSigningInShowsTheShellWithTabChrome() {
        setNavHost()

        // a launch with no session begins on the connect flow, outside the shell chrome.
        composeTestRule.onNodeWithText(CONNECT_STAND_IN_LABEL).assertIsDisplayed()
        composeTestRule.onNodeWithText(HOME_TITLE).assertDoesNotExist()

        composeTestRule.onNodeWithText(CONNECT_STAND_IN_LABEL).performClick()
        composeTestRule.onNodeWithText(HOME_TITLE).assertIsDisplayed()
        composeTestRule
            .onNodeWithContentDescription(TopLevelDestination.HOME.contentDescription)
            .assertIsDisplayed()
    }

    private companion object {
        /** Marker rendered by the Hilt-free stand-in for `ConnectFlow`. */
        const val CONNECT_STAND_IN_LABEL = "connect-flow-stand-in"
    }
}
