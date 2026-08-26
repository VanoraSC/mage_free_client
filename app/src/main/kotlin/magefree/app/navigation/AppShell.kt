package magefree.app.navigation

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import magefree.app.screens.DecksPlaceholderScreen
import magefree.designsystem.layout.WindowWidthClass
import magefree.designsystem.layout.windowWidthClass
import magefree.feature.decks.DecksRoute as DecksLibraryRoute

/**
 * The app shell: a [Scaffold] hosting [MageNavHost] plus adaptive primary navigation. At
 * [Compact][WindowWidthClass.Compact] width the destinations live in a thumb-reachable bottom
 * [NavigationBar]; at medium/expanded width they move to a side [NavigationRail]. The chrome is
 * chosen from the [WindowWidthClass] derived from the available space, so the same shell adapts
 * across phone ↔ tablet/foldable.
 *
 * This overload obtains the width class from the design system's single access point
 * ([windowWidthClass]) — the one place that touches the experimental window size-class API — and
 * delegates to the stateless [AppShell] below (which takes an explicit [WindowWidthClass]) so both
 * layouts are directly testable.
 *
 * Per the inset-ownership convention (`:core:designsystem` `layout/Insets.kt`), the shell owns the
 * system-bar / chrome insets: it applies them and marks them consumed so screens do not double-count.
 */
@Composable
fun AppShell(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    onEnterGame: () -> Unit = {},
    onOpenCatalog: () -> Unit = {},
    onSignOut: () -> Unit = {},
    connectionStatusBar: @Composable () -> Unit = { ConnectionStatusBar() },
    // the Decks tab hosts the deck library + builder (`:feature:decks`). Built here (not in
    // MageNavHost) so the shell can be driven Hilt-free in tests via the stateless overload's default.
    // "Browse cards" navigates to [CardsRoute] within the Decks back stack (0032 browser stays reachable).
    decksScreen: @Composable () -> Unit = { DecksLibraryRoute(onBrowseCards = { navController.navigate(CardsRoute) }) },
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val widthClass = windowWidthClass(DpSize(maxWidth, maxHeight))
        AppShell(
            widthClass = widthClass,
            navController = navController,
            onEnterGame = onEnterGame,
            onOpenCatalog = onOpenCatalog,
            onSignOut = onSignOut,
            connectionStatusBar = connectionStatusBar,
            decksScreen = decksScreen,
        )
    }
}

/**
 * Stateless shell that renders a bottom [NavigationBar] when [widthClass] is
 * [Compact][WindowWidthClass.Compact] and a side [NavigationRail] otherwise.
 *
 * Selecting a tab navigates with `launchSingleTop = true`, `restoreState = true`, and
 * `popUpTo(startDestination) { saveState = true }` so each tab keeps its own back stack/state and
 * re-tapping never stacks duplicates. The selected item follows the current back-stack destination.
 *
 * The shell owns the chrome insets in both layouts: the bottom-bar [Scaffold] consumes its
 * `innerPadding`, and the rail layout consumes the vertical system-bar insets, so the nav host and any
 * screen inside can apply only their content insets (`Modifier.contentInsets()`) without double-count.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppShell(
    widthClass: WindowWidthClass,
    navController: NavHostController,
    modifier: Modifier = Modifier,
    onEnterGame: () -> Unit = {},
    onOpenCatalog: () -> Unit = {},
    // deliberate sign-out, surfaced on the Settings destination and hoisted to the root
    // graph, which tears the session down and returns to the connect flow. Defaults to a no-op so the
    // shell stays drivable without a session (and without Hilt) in tests.
    onSignOut: () -> Unit = {},
    connectionStatusBar: @Composable () -> Unit = { ConnectionStatusBar() },
    // Defaults to the Hilt-free placeholder so `AppShell` can be driven without a Hilt graph in tests;
    // production supplies the real `:feature:decks` library via the overload above.
    decksScreen: @Composable () -> Unit = { DecksPlaceholderScreen() },
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    val onSelect: (TopLevelDestination) -> Unit = { destination ->
        navController.navigate(destination.route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    if (widthClass.usesNavigationRail) {
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
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        // Shell owns the vertical chrome insets: apply + consume the top/bottom
                        // system-bar insets so the strip and screens sit clear of them. The rail
                        // consumes the start inset; screens add horizontal content insets themselves.
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical)),
            ) {
                // Shell-wide status surface: sits above content so it persists across destinations.
                connectionStatusBar()
                MageNavHost(
                    navController = navController,
                    onEnterGame = onEnterGame,
                    onOpenCatalog = onOpenCatalog,
                    onSignOut = onSignOut,
                    decksScreen = decksScreen,
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
            Column(
                modifier =
                    Modifier
                        .padding(innerPadding)
                        // Mark the chrome insets the Scaffold applied as consumed, so a screen's
                        // Modifier.contentInsets() adds only what's left instead of re-padding them.
                        .consumeWindowInsets(innerPadding),
            ) {
                // Shell-wide status surface: sits above content so it persists across destinations.
                connectionStatusBar()
                MageNavHost(
                    navController = navController,
                    onEnterGame = onEnterGame,
                    onOpenCatalog = onOpenCatalog,
                    onSignOut = onSignOut,
                    decksScreen = decksScreen,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/** True when [this] back-stack destination (or an ancestor) is the given top-level [destination]. */
private fun NavDestination?.isOn(destination: TopLevelDestination): Boolean =
    this?.hierarchy?.any { it.hasRoute(destination.routeClass) } == true
