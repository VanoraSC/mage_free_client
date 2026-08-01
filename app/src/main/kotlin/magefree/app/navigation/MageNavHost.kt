package magefree.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable
import magefree.app.screens.DecksPlaceholderScreen
import magefree.app.screens.HomeScreen
import magefree.app.screens.ProfilePlaceholderScreen
import magefree.app.screens.SettingsPlaceholderScreen
import magefree.feature.cards.CardsRoute as CardsFeatureRoute
import magefree.feature.lobby.LobbyRoute as LobbyFeatureRoute

/**
 * Type-safe route for the read-only lobby browser (story 0029), reached from the home "Play" entry.
 * It is a nested destination inside the shell — not a top-level tab — so the tab chrome and the
 * connection strip stay visible above it and Back returns to Home.
 */
@Serializable
data object LobbyRoute

/**
 * Type-safe route for the read-only card catalog browser (story 0032), reached from the Decks
 * destination's "Browse cards" entry. A nested destination inside the shell (not a top-level tab), so
 * the tab chrome and connection strip stay visible above it and Back returns to Decks.
 */
@Serializable
data object CardsRoute

/**
 * The Navigation-Compose host for the top-level destinations, wired with **type-safe** routes:
 * each `composable<Route>` entry is keyed by a [Serializable][kotlinx.serialization.Serializable]
 * route type, not a string. [HomeRoute] is the start destination.
 *
 * Only the four top-level placeholder screens live here; nested/detail navigation within a
 * destination is added by the owning feature epics (out of scope for story 0008).
 */
@Composable
fun MageNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
    onEnterGame: () -> Unit = {},
    onOpenCatalog: () -> Unit = {},
) {
    // Navigate to a top-level route while preserving each tab's own back stack/state, mirroring the
    // tab-selection behaviour in [AppShell] so the hub's secondary entries and the nav chrome stay
    // in sync (single-top, state saved/restored around the start destination).
    val navigateToTab: (Any) -> Unit = { route ->
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    NavHost(
        navController = navController,
        startDestination = HomeRoute,
        modifier = modifier,
    ) {
        composable<HomeRoute> {
            HomeScreen(
                // Story 0029: the Play entry now opens the read-only lobby browser (EPIC-06). Joining
                // a table is still EPIC-07. Kept within the Home tab's back stack so Back returns here.
                onPlayClick = { navController.navigate(LobbyRoute) },
                onDecksClick = { navigateToTab(DecksRoute) },
                onProfileClick = { navigateToTab(ProfileRoute) },
                onSettingsClick = { navigateToTab(SettingsRoute) },
            )
        }
        composable<LobbyRoute> {
            LobbyFeatureRoute(onBack = { navController.popBackStack() })
        }
        composable<DecksRoute> {
            // Story 0032: the deck builder (Epic 9) will live here; for now Decks offers a read-only
            // entry into the card catalog browser. Kept within the Decks back stack so Back returns here.
            DecksPlaceholderScreen(onBrowseCards = { navController.navigate(CardsRoute) })
        }
        composable<CardsRoute> {
            CardsFeatureRoute(onBack = { navController.popBackStack() })
        }
        composable<ProfileRoute> { ProfilePlaceholderScreen() }
        composable<SettingsRoute> {
            SettingsPlaceholderScreen(
                // Stub entry into the immersive game route; real entry arrives via lobby/table
                // flows in EPIC-06/07.
                onEnterGame = onEnterGame,
                // Debug-only entry into the design-system component catalog (story 0015).
                onOpenCatalog = onOpenCatalog,
            )
        }
    }
}
