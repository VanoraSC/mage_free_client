package magefree.app.navigation

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import magefree.app.connection.ui.ConnectionStatusBar

/**
 * The app shell: a [Scaffold] hosting [MageNavHost] plus adaptive primary navigation. At
 * [Compact][WindowWidthSizeClass.Compact] width the destinations live in a thumb-reachable bottom
 * [NavigationBar]; at medium/expanded width they move to a side [NavigationRail]. The chrome is
 * chosen from the [WindowSizeClass] derived from the available space, so the same shell adapts
 * across phone ↔ tablet/foldable.
 *
 * This overload computes the size class from the layout constraints and delegates to the stateless
 * [AppShell] below (which takes an explicit [WindowWidthSizeClass]) so both layouts are directly
 * testable.
 */
@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@Composable
fun AppShell(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    connectionStatusBar: @Composable () -> Unit = { ConnectionStatusBar() },
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val windowSizeClass = WindowSizeClass.calculateFromSize(DpSize(maxWidth, maxHeight))
        AppShell(
            widthSizeClass = windowSizeClass.widthSizeClass,
            navController = navController,
            connectionStatusBar = connectionStatusBar,
        )
    }
}

/**
 * Stateless shell that renders a bottom [NavigationBar] when [widthSizeClass] is
 * [Compact][WindowWidthSizeClass.Compact] and a side [NavigationRail] otherwise.
 *
 * Selecting a tab navigates with `launchSingleTop = true`, `restoreState = true`, and
 * `popUpTo(startDestination) { saveState = true }` so each tab keeps its own back stack/state and
 * re-tapping never stacks duplicates. The selected item follows the current back-stack destination.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppShell(
    widthSizeClass: WindowWidthSizeClass,
    navController: NavHostController,
    modifier: Modifier = Modifier,
    connectionStatusBar: @Composable () -> Unit = { ConnectionStatusBar() },
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val useRail = widthSizeClass != WindowWidthSizeClass.Compact

    val onSelect: (TopLevelDestination) -> Unit = { destination ->
        navController.navigate(destination.route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    if (useRail) {
        Row(modifier = modifier.fillMaxSize()) {
            NavigationRail {
                TopLevelDestination.entries.forEach { destination ->
                    NavigationRailItem(
                        selected = currentDestination.isOn(destination),
                        onClick = { onSelect(destination) },
                        icon = {
                            Icon(
                                imageVector = destination.icon,
                                contentDescription = destination.contentDescription,
                            )
                        },
                        label = { Text(destination.label) },
                    )
                }
            }
            Column(modifier = Modifier.fillMaxSize()) {
                // Shell-wide status surface: sits above content so it persists across destinations.
                connectionStatusBar()
                MageNavHost(
                    navController = navController,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    } else {
        Scaffold(
            modifier = modifier.fillMaxSize(),
            bottomBar = {
                NavigationBar {
                    TopLevelDestination.entries.forEach { destination ->
                        NavigationBarItem(
                            selected = currentDestination.isOn(destination),
                            onClick = { onSelect(destination) },
                            icon = {
                                Icon(
                                    imageVector = destination.icon,
                                    contentDescription = destination.contentDescription,
                                )
                            },
                            label = { Text(destination.label) },
                        )
                    }
                }
            },
        ) { innerPadding ->
            Column(modifier = Modifier.padding(innerPadding)) {
                // Shell-wide status surface: sits above content so it persists across destinations.
                connectionStatusBar()
                MageNavHost(
                    navController = navController,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/** True when [this] back-stack destination (or an ancestor) is the given top-level [destination]. */
private fun NavDestination?.isOn(destination: TopLevelDestination): Boolean =
    this?.hierarchy?.any { it.hasRoute(destination.routeClass) } == true
