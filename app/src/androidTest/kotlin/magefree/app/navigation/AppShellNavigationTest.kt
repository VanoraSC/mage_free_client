package magefree.app.navigation

import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.testing.TestNavHostController
import androidx.test.ext.junit.runners.AndroidJUnit4
import magefree.app.screens.DECKS_SCREEN_LABEL
import magefree.app.screens.HOME_TITLE
import magefree.app.screens.PROFILE_SCREEN_LABEL
import magefree.app.screens.SETTINGS_SCREEN_LABEL
import magefree.designsystem.theme.MageTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented Compose UI test for the navigation shell. NOT part of the hermetic `./gradlew check`
 * gate — run with a device/emulator attached via `./gradlew :app:connectedDebugAndroidTest`.
 *
 * It drives the stateless [AppShell] with an explicit [WindowWidthSizeClass] and a
 * [TestNavHostController], so both the bottom-bar (compact) and rail (expanded) layouts are covered
 * without needing to resize a real window.
 */
@RunWith(AndroidJUnit4::class)
class AppShellNavigationTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun setShell(widthSizeClass: WindowWidthSizeClass) {
        composeTestRule.setContent {
            val navController =
                TestNavHostController(androidx.compose.ui.platform.LocalContext.current).apply {
                    navigatorProvider.addNavigator(ComposeNavigator())
                }
            MageTheme {
                // Pass a stateless status bar so this navigation test needs no Hilt graph; the
                // connection surface has its own coverage in ConnectionStatusBarTest.
                AppShell(
                    widthSizeClass = widthSizeClass,
                    navController = navController,
                    connectionStatusBar = {},
                )
            }
        }
    }

    @Test
    fun startsOnHomeWithHomeSelected() {
        setShell(WindowWidthSizeClass.Compact)

        composeTestRule.onNodeWithText(HOME_TITLE).assertIsDisplayed()
        composeTestRule
            .onNodeWithContentDescription(TopLevelDestination.HOME.contentDescription)
            .assertIsSelected()
    }

    @Test
    fun tappingEachDestinationShowsItsScreenAndSelectsItInBottomBar() {
        setShell(WindowWidthSizeClass.Compact)
        assertTabNavigates(TopLevelDestination.DECKS, DECKS_SCREEN_LABEL)
        assertTabNavigates(TopLevelDestination.PROFILE, PROFILE_SCREEN_LABEL)
        assertTabNavigates(TopLevelDestination.SETTINGS, SETTINGS_SCREEN_LABEL)
        assertTabNavigates(TopLevelDestination.HOME, HOME_TITLE)
    }

    @Test
    fun railLayoutNavigatesAtExpandedWidth() {
        setShell(WindowWidthSizeClass.Expanded)
        assertTabNavigates(TopLevelDestination.DECKS, DECKS_SCREEN_LABEL)
        assertTabNavigates(TopLevelDestination.SETTINGS, SETTINGS_SCREEN_LABEL)
    }

    private fun assertTabNavigates(
        destination: TopLevelDestination,
        screenLabel: String,
    ) {
        composeTestRule
            .onNodeWithContentDescription(destination.contentDescription)
            .performClick()
        composeTestRule.onNodeWithText(screenLabel).assertIsDisplayed()
        composeTestRule
            .onNodeWithContentDescription(destination.contentDescription)
            .assertIsSelected()
    }
}
