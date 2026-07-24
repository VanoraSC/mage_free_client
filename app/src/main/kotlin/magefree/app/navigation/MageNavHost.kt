package magefree.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import magefree.app.screens.DecksPlaceholderScreen
import magefree.app.screens.HomePlaceholderScreen
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
    NavHost(
        navController = navController,
        startDestination = HomeRoute,
        modifier = modifier,
    ) {
        composable<HomeRoute> { HomePlaceholderScreen() }
        composable<DecksRoute> { DecksPlaceholderScreen() }
        composable<ProfileRoute> { ProfilePlaceholderScreen() }
        composable<SettingsRoute> { SettingsPlaceholderScreen() }
    }
}
