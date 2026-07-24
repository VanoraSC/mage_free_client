package magefree.app.connection

import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.testing.TestNavHostController
import androidx.test.ext.junit.runners.AndroidJUnit4
import magefree.app.connection.ui.ConnectionStatusBar
import magefree.app.navigation.AppShell
import magefree.app.navigation.TopLevelDestination
import magefree.app.screens.DECKS_SCREEN_LABEL
import magefree.app.screens.SETTINGS_SCREEN_LABEL
import magefree.app.theme.MageTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented Compose UI test for the connection-status surface. NOT part of the hermetic
 * `./gradlew check` gate — run on a device/emulator via `./gradlew :app:connectedDebugAndroidTest`.
 *
 * It mounts the [AppShell] with a stateless [ConnectionStatusBar] driven by an explicit state and
 * asserts the bar persists (by its content description) across top-level destinations, and that
 * distinct states render distinct text + content descriptions.
 */
@RunWith(AndroidJUnit4::class)
class ConnectionStatusBarTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun setShell(state: ConnectionState) {
        composeTestRule.setContent {
            val navController =
                TestNavHostController(LocalContext.current).apply {
                    navigatorProvider.addNavigator(ComposeNavigator())
                }
            MageTheme {
                AppShell(
                    widthSizeClass = WindowWidthSizeClass.Compact,
                    navController = navController,
                    connectionStatusBar = {
                        ConnectionStatusBar(uiState = state.toUiState(), onRetry = {})
                    },
                )
            }
        }
    }

    @Test
    fun statusBarIsVisibleAcrossAllTopLevelDestinations() {
        val ui = ConnectionState.Connected.toUiState()
        setShell(ConnectionState.Connected)

        // Home (start destination).
        composeTestRule.onNodeWithContentDescription(ui.contentDescription).assertIsDisplayed()

        // Decks.
        composeTestRule
            .onNodeWithContentDescription(TopLevelDestination.DECKS.contentDescription)
            .performClick()
        composeTestRule.onNodeWithText(DECKS_SCREEN_LABEL).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(ui.contentDescription).assertIsDisplayed()

        // Settings.
        composeTestRule
            .onNodeWithContentDescription(TopLevelDestination.SETTINGS.contentDescription)
            .performClick()
        composeTestRule.onNodeWithText(SETTINGS_SCREEN_LABEL).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(ui.contentDescription).assertIsDisplayed()
    }

    @Test
    fun disconnectedStateShowsItsLabelAndDescription() {
        val ui = ConnectionState.Disconnected.toUiState()
        setShell(ConnectionState.Disconnected)

        composeTestRule.onNodeWithText(ui.label).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(ui.contentDescription).assertIsDisplayed()
    }
}
