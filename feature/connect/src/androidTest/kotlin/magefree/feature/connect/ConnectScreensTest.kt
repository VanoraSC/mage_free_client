package magefree.feature.connect

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import magefree.designsystem.theme.MageTheme
import magefree.model.ServerTarget
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented Compose UI tests for the stateless connect screens. NOT part of the hermetic
 * `./gradlew check` gate — run on a device/emulator via `:feature:connect:connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class ConnectScreensTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val server = ServerTarget(host = "bridge.local", port = 9000, displayName = "Local bridge")

    @Test
    fun serverRowSelectionInvokesCallback() {
        var selected: ServerTarget? = null
        composeTestRule.setContent {
            MageTheme {
                ServerListScreen(
                    uiState = ServerListUiState(isLoading = false, servers = listOf(server)),
                    onSelectServer = { selected = it },
                    onAddServer = {},
                    onEditServer = {},
                    onRemoveServer = {},
                    onEditorNameChange = {},
                    onEditorHostChange = {},
                    onEditorPortChange = {},
                    onEditorSecureChange = {},
                    onEditorSave = {},
                    onEditorDismiss = {},
                )
            }
        }
        composeTestRule.onNodeWithText("Local bridge").performClick()
        assertEquals(server, selected)
    }

    @Test
    fun connectButtonDisabledUntilUsernameEntered() {
        composeTestRule.setContent {
            MageTheme {
                SignInScreen(
                    uiState = SignInUiState(server = server, username = "", phase = ConnectPhase.Idle),
                    onUsernameChange = {},
                    onPasswordChange = {},
                    onConnect = {},
                    onRetry = {},
                    onCancel = {},
                    onContinue = {},
                    onBack = {},
                )
            }
        }
        composeTestRule.onNodeWithText(CONNECT_LABEL).assertIsNotEnabled()
    }

    @Test
    fun authFailedSurfaceOffersRetry() {
        var retried = 0
        composeTestRule.setContent {
            MageTheme {
                AuthFailedSurface(onRetry = { retried++ }, onCancel = {})
            }
        }
        val retry = composeTestRule.onNodeWithText(CONNECT_RETRY_LABEL)
        retry.assertIsDisplayed()
        retry.assertHasClickAction()
        retry.performClick()
        assertEquals(1, retried)
    }
}
