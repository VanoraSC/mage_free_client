package magefree.app.navigation

import android.app.Application
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.testing.TestNavHostController
import magefree.app.catalog.CatalogRoute
import magefree.app.game.GameRoute
import magefree.designsystem.theme.MageTheme
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.reflect.KClass

/**
 * Story 0048 — the **destination** half of the wiring guards (see `src/test`'s
 * `FeatureWiringGuardTest` for the classpath/reference half).
 *
 * `FeatureWiringGuardTest` proves each feature ships and that `:app`'s code calls into it. This proves
 * the third thing a user actually depends on: that the **real navigation graphs** have a destination
 * to carry them there. It composes the production [AppNavHost] and [MageNavHost] — no stub graph — and
 * enumerates the destinations they register.
 *
 * A destination silently deleted from either graph fails these tests, which is the story-0047 shape:
 * everything builds, everything is on the classpath, and the screen is simply unreachable.
 *
 * Deliberately kept to graph *structure*, not rendering — but the reason has changed. It used to be
 * that the feature routes resolved their view models through Hilt, and standing up a Hilt component
 * here would have made a cheap gate expensive. Since story 0081 that cost is gone: Koin's container
 * is a `startKoin` call, and
 * [magefree.app.di.ScreenViewModelResolutionTest] does render each destination for real. This test
 * stays structural because the two answer different questions — this one asks whether a destination
 * *exists* (the story-0047 shape: everything builds and the screen is simply unreachable), that one
 * asks whether it can *resolve what it needs*. A destination deleted from the graph would make the
 * other test pass by never visiting it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h891dp")
class FeatureDestinationWiringTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var navController: TestNavHostController

    /** Compose the real **root** graph (the one a launch enters) and hand back its destinations. */
    private fun rootGraph(): NavGraph {
        composeTestRule.setContent {
            val context = LocalContext.current
            val nav =
                remember {
                    TestNavHostController(context).apply { navigatorProvider.addNavigator(ComposeNavigator()) }
                }
            navController = nav
            MageTheme {
                AppNavHost(
                    navController = nav,
                    connectionStatusBar = {},
                    // A stand-in only so the graph can be built without Hilt; that the *production*
                    // default is `:feature:connect`'s ConnectFlow is asserted by the constant-pool
                    // guard in FeatureWiringGuardTest.
                    connectFlow = { onConnected -> Button(onClick = onConnected) { Text("stand-in") } },
                )
            }
        }
        composeTestRule.waitForIdle()
        return navController.graph
    }

    /** Compose the real **shell** graph (the tabbed browsing graph inside [ShellRoute]). */
    private fun shellGraph(): NavGraph {
        composeTestRule.setContent {
            val context = LocalContext.current
            val nav =
                remember {
                    TestNavHostController(context).apply { navigatorProvider.addNavigator(ComposeNavigator()) }
                }
            navController = nav
            MageTheme { MageNavHost(navController = nav) }
        }
        composeTestRule.waitForIdle()
        return navController.graph
    }

    private fun NavGraph.routeClasses(): List<NavDestination> = iterator().asSequence().toList()

    private fun assertHasDestinations(
        graphName: String,
        graph: NavGraph,
        expected: Map<KClass<*>, String>,
    ) {
        val destinations = graph.routeClasses()
        val missing = expected.filterKeys { route -> destinations.none { it.hasRoute(route) } }
        if (missing.isNotEmpty()) {
            fail(
                buildString {
                    appendLine("$graphName is missing destination(s) — the screen(s) below are UNREACHABLE:")
                    missing.forEach { (route, why) -> appendLine("  - ${route.simpleName}: $why") }
                    appendLine()
                    appendLine("Destinations actually registered: ${destinations.map { it.route ?: it.id.toString() }}")
                    appendLine("Restore the destination; do not relax this test.")
                },
            )
        }
    }

    @Test
    fun theRootGraphMountsConnectTheShellTheGameAndTheCatalog() {
        assertHasDestinations(
            "AppNavHost (root graph)",
            rootGraph(),
            mapOf(
                ConnectRoute::class to ":feature:connect — sign-in; without it the APK can never open a session",
                ShellRoute::class to "the tabbed browsing shell — without it a signed-in user has nowhere to go",
                GameRoute::class to "the immersive game surface (story 0011)",
                CatalogRoute::class to "the design-system component catalog (story 0015)",
            ),
        )
    }

    @Test
    fun aColdStartLandsWhereSignInIsReachable() {
        val graph = rootGraph()

        // The cold-start entry policy (story 0047): a launch with no session must begin somewhere
        // sign-in is reachable. Asserted against the graph's declared start destination, so a change of
        // policy has to be a deliberate edit here rather than an accident in AppNavHost.
        val start = graph.findStartDestination()
        assertTrue(
            "cold start must land on ConnectRoute (sign-in reachable with no session), " +
                "but the root graph starts on ${start?.route ?: start?.id}",
            start?.hasRoute(ConnectRoute::class) == true,
        )
        assertTrue(
            "a cold launch must actually resolve to ConnectRoute",
            navController.currentBackStackEntry?.destination?.hasRoute(ConnectRoute::class) == true,
        )
    }

    private fun NavGraph.findStartDestination(): NavDestination? = findNode(startDestinationId)

    @Test
    fun theShellGraphMountsEveryFeatureDestinationTheAppExposes() {
        assertHasDestinations(
            "MageNavHost (shell graph)",
            shellGraph(),
            mapOf(
                HomeRoute::class to "the home hub — the shell's start destination",
                LobbyRoute::class to ":feature:lobby — Home's \"Play\" entry",
                CardsRoute::class to ":feature:cards — the deck library's \"Browse cards\" entry",
                DecksRoute::class to ":feature:decks — the Decks tab's library + builder",
                HostTableNavRoute::class to ":feature:tables — hosting a table from the lobby",
                JoinTableNavRoute::class to ":feature:tables — joining a lobby table",
                TableRoomNavRoute::class to ":feature:tables — the table room (seats/ready/start)",
                GameBoardNavRoute::class to
                    ":feature:game — the read-only board the room's MatchStarting hand-off opens; " +
                    "without it the game id the server sends has nowhere to go",
                ProfileRoute::class to "the Profile tab",
                SettingsRoute::class to "the Settings tab (which owns sign-out)",
            ),
        )
    }
}
