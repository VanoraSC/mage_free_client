package magefree.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import magefree.app.screens.DecksPlaceholderScreen
import magefree.app.screens.HomeScreen
import magefree.app.screens.ProfilePlaceholderScreen
import magefree.app.screens.SettingsPlaceholderScreen

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
                // Stub until EPIC-06 delivers real matchmaking/lobby. Home layout, not the play flow.
                onPlayClick = { /* TODO(EPIC-06): enter matchmaking/lobby */ },
                onDecksClick = { navigateToTab(DecksRoute) },
                onProfileClick = { navigateToTab(ProfileRoute) },
                onSettingsClick = { navigateToTab(SettingsRoute) },
            )
        }
        composable<DecksRoute> { DecksPlaceholderScreen() }
        composable<ProfileRoute> { ProfilePlaceholderScreen() }
        composable<SettingsRoute> { SettingsPlaceholderScreen() }
    }
}
