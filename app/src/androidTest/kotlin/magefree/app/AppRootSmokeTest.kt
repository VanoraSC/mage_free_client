package magefree.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import magefree.app.theme.MageTheme
import org.junit.Rule
import org.junit.Test

/**
 * Instrumented Compose smoke test: proves [AppRoot] renders its placeholder inside [MageTheme] on a
 * device/emulator. NOT part of the hermetic `./gradlew check` gate — run it with a device attached
 * via `./gradlew :app:connectedDebugAndroidTest` (see app/README.md).
 */
class AppRootSmokeTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun appRootShowsPlaceholderText() {
        composeTestRule.setContent {
            MageTheme {
                AppRoot()
            }
        }

        composeTestRule.onNodeWithText(APP_ROOT_PLACEHOLDER).assertIsDisplayed()
    }
}
