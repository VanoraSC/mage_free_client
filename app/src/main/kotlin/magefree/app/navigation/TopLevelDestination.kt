package magefree.app.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.serialization.Serializable
import kotlin.reflect.KClass

/**
 * Type-safe Navigation-Compose routes for the four top-level destinations.
 *
 * Each is a [Serializable] `data object` used with type-safe `composable<Route> { … }` entries in
 * [MageNavHost] and `navController.navigate(Route)` in [AppShell]. Content of each destination is a
 * stub (story 0008) — real screens arrive in their owning epics.
 */
@Serializable
data object HomeRoute

@Serializable
data object DecksRoute

@Serializable
data object ProfileRoute

@Serializable
data object SettingsRoute

/**
 * The ordered set of Arena-style top-level destinations that drive the adaptive navigation chrome
 * (bottom [androidx.compose.material3.NavigationBar] / [androidx.compose.material3.NavigationRail]).
 *
 * The nav UI iterates [entries]; each item carries its type-safe [route], a visible [label], an
 * [icon], and a [contentDescription] for accessibility. Home/Play is first, per the IA.
 */
enum class TopLevelDestination(
    val route: Any,
    val routeClass: KClass<*>,
    val label: String,
    val icon: ImageVector,
    val contentDescription: String,
) {
    HOME(
        route = HomeRoute,
        routeClass = HomeRoute::class,
        label = "Home",
        icon = Icons.Filled.Home,
        contentDescription = "Home and play",
    ),
    DECKS(
        route = DecksRoute,
        routeClass = DecksRoute::class,
        label = "Decks",
        icon = Icons.AutoMirrored.Filled.List,
        contentDescription = "Decks",
    ),
    PROFILE(
        route = ProfileRoute,
        routeClass = ProfileRoute::class,
        label = "Profile",
        icon = Icons.Filled.Person,
        contentDescription = "Profile and social",
    ),
    SETTINGS(
        route = SettingsRoute,
        routeClass = SettingsRoute::class,
        label = "Settings",
        icon = Icons.Filled.Settings,
        contentDescription = "Settings",
    ),
}
