package magefree.feature.connect

import android.app.Application
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import magefree.designsystem.theme.MageTheme
import magefree.model.ServerTarget
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The server list's offline-deckbuilding entry, in the hermetic gate.
 *
 * The graph half — that the entry lands on a mounted destination — is asserted by `:app`'s root
 * reachability test. This is the other half: that the control is **on screen** and actually raises
 * the hoisted callback. A control that renders, reports a click and does nothing looks identical to
 * a working one from the graph's side, which is why both halves are needed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    application = Application::class,
    // Robolectric's default window clips bottom-bar content out of view and makes `assertIsDisplayed`
    // fail on chrome that is fine on any real phone. Pin a representative compact phone.
    qualifiers = "w411dp-h891dp",
)
class ServerListDecksEntryTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private var openDecksCount = 0
    private var openCatalogCount = 0

    private fun show(uiState: ServerListUiState) {
        composeTestRule.setContent {
            MageTheme {
                ServerListScreen(
                    uiState = uiState,
                    onSelectServer = {},
                    onAddServer = {},
                    onOpenDecks = { openDecksCount++ },
                    onOpenCatalog = { openCatalogCount++ },
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
    }

    @Test
    fun theDecksEntryIsOnScreenWithNoServersConfigured() {
        // The case that matters most: a first launch has no servers, and deckbuilding is the one thing
        // the app can do before anything is set up.
        show(ServerListUiState(servers = emptyList(), isLoading = false))

        composeTestRule.onNodeWithText(OPEN_DECKS_LABEL).assertIsDisplayed().assertHasClickAction()
    }

    @Test
    fun theDecksEntryIsOnScreenAlongsideSavedServers() {
        show(ServerListUiState(servers = listOf(ServerTarget(host = "localhost", port = 8080)), isLoading = false))

        composeTestRule.onNodeWithText(OPEN_DECKS_LABEL).assertIsDisplayed()
    }

    @Test
    fun tappingTheDecksEntryRaisesTheHoistedAction() {
        show(ServerListUiState(servers = emptyList(), isLoading = false))

        composeTestRule.onNodeWithText(OPEN_DECKS_LABEL).performClick()

        assertEquals("the entry must raise its action, not merely render", 1, openDecksCount)
    }

    @Test
    fun theCatalogEntrySitsBesideDecksWithNoServersConfigured() {
        // Both are offline surfaces and both are peers on this screen, so a first launch offers them
        // together rather than hiding one behind sign-in and a settings screen.
        show(ServerListUiState(servers = emptyList(), isLoading = false))

        composeTestRule.onNodeWithText(OPEN_DECKS_LABEL).assertIsDisplayed()
        composeTestRule.onNodeWithText(OPEN_CATALOG_LABEL).assertIsDisplayed().assertHasClickAction()
    }

    @Test
    fun tappingTheCatalogEntryRaisesItsOwnActionAndNotTheOther() {
        // Two controls on one row is exactly where a mis-wiring hides: both render, and the wrong one
        // fires.
        show(ServerListUiState(servers = emptyList(), isLoading = false))

        composeTestRule.onNodeWithText(OPEN_CATALOG_LABEL).performClick()

        assertEquals("the entry must raise its action, not merely render", 1, openCatalogCount)
        assertEquals("the catalog entry must not fire the decks action", 0, openDecksCount)
    }
}
