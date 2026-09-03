package magefree.app.navigation

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.testing.TestNavHostController
import magefree.app.catalog.CatalogRoute
import magefree.app.screens.HOME_TITLE
import magefree.app.screens.SIGN_OUT_LABEL
import magefree.designsystem.theme.MageTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.reflect.KClass

/**
 * The **cold-start reachability** test for the connect flow (verification standard 2).
 *
 * It drives the real root [AppNavHost] graph on the JVM (Robolectric), so it is part of the hermetic
 * `:app:testDebugUnitTest` gate rather than a device-only androidTest, and proves the three things
 * that were broken without it:
 *
 * 1. a launch with **no session** lands on the connect destination, not inside the shell;
 * 2. sign-in success hands off to the shell and does **not** leave connect on the back stack;
 * 3. signing out tears the session down *and* returns the user to connect, so they can sign in again.
 *
 * `ConnectFlow` itself resolves its ViewModels through Hilt, so this test substitutes a Hilt-free
 * stand-in with the same contract (raise `onConnected` when the player is signed in and continues) via
 * [AppNavHost]'s hoisted `connectFlow` parameter — the same pattern the shell already uses for its
 * connection strip and deck library. What is asserted here is the **graph**: which destination a cold
 * launch resolves to, what the hand-off pops, and where sign-out goes. That the connect destination
 * renders the real `ConnectFlow` in production is the parameter's default and is covered by the
 * on-device pass (verification standard 3).
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    // A plain Application: the graph under test needs no Hilt component, and asking for one would
    // reintroduce exactly the DI coupling the hoisted parameters exist to avoid.
    application = Application::class,
    // Robolectric's default window is 320x470dp, which clips the shell's bottom navigation bar out of
    // view and makes `assertIsDisplayed` fail on chrome that is fine on any real phone. Pin a
    // representative compact phone instead, so the assertions stay strict.
    qualifiers = "w411dp-h891dp",
)
class ConnectEntryReachabilityTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var navController: TestNavHostController

    /** Counts real session teardowns ([AppNavHost]'s hoisted `onSignOut`, production: `signOut()`). */
    private var signOutCount = 0

    private fun launchColdWithNoSession() {
        composeTestRule.setContent {
            val context = LocalContext.current
            val controller =
                remember {
                    TestNavHostController(context).apply {
                        navigatorProvider.addNavigator(ComposeNavigator())
                    }
                }
            navController = controller
            MageTheme {
                AppNavHost(
                    navController = controller,
                    connectionStatusBar = {},
                    onSignOut = { signOutCount++ },
                    connectFlow = { onConnected, onOpenDecks, onOpenCatalog ->
                        // A Column, not bare Buttons: without a layout they draw on top of each
                        // other at the same origin and a click lands on whichever is painted last.
                        Column {
                            Button(onClick = onConnected) { Text(CONNECT_STAND_IN_LABEL) }
                            Button(onClick = onOpenDecks) { Text(DECKS_STAND_IN_LABEL) }
                            Button(onClick = onOpenCatalog) { Text(CATALOG_STAND_IN_LABEL) }
                        }
                    },
                    // Both offline destinations resolve ViewModels through the DI container, which this
                    // graph test deliberately runs without; the markers stand in for their content.
                    decksScreen = { onBrowseCards ->
                        Button(onClick = onBrowseCards) { Text(DECKS_LIBRARY_STAND_IN_LABEL) }
                    },
                    cardsScreen = { Text(CARDS_STAND_IN_LABEL) },
                    // The catalog resolves the Coil-backed art renderer through the DI container, so
                    // it needs a stand-in here for the same reason the other offline screens do.
                    catalogScreen = { _, _ -> Text(CATALOG_SCREEN_STAND_IN_LABEL) },
                )
            }
        }
    }

    private fun currentRouteIs(route: KClass<*>): Boolean = navController.currentBackStackEntry?.destination?.hasRoute(route) == true

    private fun isOnBackStack(route: Any): Boolean = runCatching { navController.getBackStackEntry(route) }.isSuccess

    /**
     * The shell's primary-navigation item for [destination]. Matched by its `Tab` role plus its visible
     * label rather than by the icon's content description: Material3 does not surface the icon's
     * description on the merged `NavigationBarItem` node, and the label alone is ambiguous (Home's
     * secondary entries carry the same words).
     */
    private fun shellTab(destination: TopLevelDestination) =
        composeTestRule.onNode(
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab) and hasText(destination.label),
        )

    @Test
    fun coldLaunchWithNoSessionLandsOnTheConnectDestination() {
        launchColdWithNoSession()

        assertTrue("cold launch must resolve to ConnectRoute", currentRouteIs(ConnectRoute::class))
        composeTestRule.onNodeWithText(CONNECT_STAND_IN_LABEL).assertIsDisplayed()
        // Sign-in is chrome-free: no shell tabs and no Home behind it.
        composeTestRule.onNodeWithText(HOME_TITLE).assertDoesNotExist()
        shellTab(TopLevelDestination.HOME).assertDoesNotExist()
    }

    @Test
    fun signInSuccessEntersTheShellAndPopsConnect() {
        launchColdWithNoSession()

        composeTestRule.onNodeWithText(CONNECT_STAND_IN_LABEL).performClick()

        assertTrue("sign-in success must land in the shell", currentRouteIs(ShellRoute::class))
        composeTestRule.onNodeWithText(HOME_TITLE).assertIsDisplayed()
        shellTab(TopLevelDestination.HOME).assertIsDisplayed()
        assertTrue("connect must not stay on the back stack behind a live session", !isOnBackStack(ConnectRoute))
    }

    @Test
    fun signingOutTearsDownTheSessionAndMakesConnectReachableAgain() {
        launchColdWithNoSession()
        composeTestRule.onNodeWithText(CONNECT_STAND_IN_LABEL).performClick()

        shellTab(TopLevelDestination.SETTINGS).performClick()
        composeTestRule.onNodeWithText(SIGN_OUT_LABEL).performClick()

        assertEquals("sign-out must tear the session down, not just navigate", 1, signOutCount)
        assertTrue("sign-out must return to ConnectRoute", currentRouteIs(ConnectRoute::class))
        composeTestRule.onNodeWithText(CONNECT_STAND_IN_LABEL).assertIsDisplayed()
        assertTrue("the signed-out shell must not stay on the back stack", !isOnBackStack(ShellRoute))
    }

    @Test
    fun decksAreReachableFromTheConnectDestinationWithNoSession() {
        // Deckbuilding is fully offline, so it must be usable before there is a session at all. This
        // asserts the graph half — that the entry the connect destination raises actually lands on a
        // mounted destination. That the server list renders the control is `:feature:connect`'s test.
        launchColdWithNoSession()

        composeTestRule.onNodeWithText(DECKS_STAND_IN_LABEL).performClick()

        assertTrue("the offline decks entry must land on DecksRoute", currentRouteIs(DecksRoute::class))
        assertTrue(
            "connect must stay on the back stack, so Back returns to the server list",
            isOnBackStack(ConnectRoute),
        )
    }

    @Test
    fun theComponentCatalogIsReachableFromTheConnectDestinationWithNoSession() {
        // The catalog needs no session either, and it is reviewed constantly while the board
        // components are being built — so it sits beside deckbuilding rather than behind sign-in and
        // a settings screen. This asserts the graph half; that the server list renders both controls
        // side by side is `:feature:connect`'s test.
        launchColdWithNoSession()

        composeTestRule.onNodeWithText(CATALOG_STAND_IN_LABEL).performClick()

        assertTrue("the catalog entry must land on CatalogRoute", currentRouteIs(CatalogRoute::class))
        assertTrue(
            "connect must stay on the back stack, so Back returns to the server list",
            isOnBackStack(ConnectRoute),
        )
    }

    @Test
    fun reachingTheCatalogWithoutASessionDoesNotEnterTheShell() {
        launchColdWithNoSession()

        composeTestRule.onNodeWithText(CATALOG_STAND_IN_LABEL).performClick()

        assertTrue("reaching the catalog must not enter the shell", !isOnBackStack(ShellRoute))
        composeTestRule.onNodeWithText(HOME_TITLE).assertDoesNotExist()
    }

    @Test
    fun reachingDecksWithoutASessionDoesNotEnterTheShell() {
        // The entry policy is untouched by the second mount point: the shell is still only ever
        // entered with a live session, so none of its chrome may appear on this path.
        launchColdWithNoSession()

        composeTestRule.onNodeWithText(DECKS_STAND_IN_LABEL).performClick()

        assertTrue("reaching decks must not enter the shell", !isOnBackStack(ShellRoute))
        composeTestRule.onNodeWithText(HOME_TITLE).assertDoesNotExist()
        shellTab(TopLevelDestination.HOME).assertDoesNotExist()
    }

    private companion object {
        /** Marker rendered by the Hilt-free stand-in for `ConnectFlow`. */
        const val CONNECT_STAND_IN_LABEL = "connect-flow-stand-in"

        /** The stand-in's offline-decks entry, standing for the server list's own control. */
        const val DECKS_STAND_IN_LABEL = "offline-decks-stand-in"

        /** The stand-in's component-catalog entry, standing for the server list's own control. */
        const val CATALOG_STAND_IN_LABEL = "catalog-stand-in"

        /** Markers for the offline destinations' own content. */
        const val DECKS_LIBRARY_STAND_IN_LABEL = "offline-decks-library-stand-in"
        const val CARDS_STAND_IN_LABEL = "offline-cards-stand-in"
        const val CATALOG_SCREEN_STAND_IN_LABEL = "component-catalog-stand-in"
    }
}
