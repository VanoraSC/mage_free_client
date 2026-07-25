package magefree.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.serialization.Serializable
import magefree.app.catalog.CatalogRoute
import magefree.app.catalog.ComponentCatalogScreen
import magefree.app.connection.ui.ConnectionStatusBar
import magefree.app.game.GameRoute
import magefree.app.game.ImmersiveGameScreen

/**
 * Type-safe route for the tabbed browsing shell — the [AppShell] with its bottom-bar / rail chrome
 * and the four top-level destinations. It is the start destination of the root [AppNavHost].
 */
@Serializable
data object ShellRoute

/**
 * The **root** Navigation-Compose host, sitting *around* the shell so the immersive
 * [GameRoute] can render **outside** the tab chrome (story 0011's chosen approach — a top-level
 * `NavHost` above the shell).
 *
 * - [ShellRoute] renders the full [AppShell], which owns its **own** inner nav controller for the
 *   Home/Decks/Profile/Settings tabs. The connection strip lives inside the shell.
 * - [GameRoute] renders the full-screen [ImmersiveGameScreen] with no shell chrome at all — no
 *   bottom bar / rail, no connection strip — so the game surface is edge-to-edge and immersive.
 * - [CatalogRoute] renders the debug-only [ComponentCatalogScreen] (story 0015), also outside the
 *   shell chrome; it is reached from the Settings dev entry.
 *
 * Entering the game / opening the catalog are hoisted actions ([AppShell]'s `onEnterGame` /
 * `onOpenCatalog`) so the shell needs no knowledge of the root graph; exiting simply pops back.
 */
@Composable
fun AppNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    connectionStatusBar: @Composable () -> Unit = { ConnectionStatusBar() },
) {
    NavHost(
        navController = navController,
        startDestination = ShellRoute,
        modifier = modifier,
    ) {
        composable<ShellRoute> {
            AppShell(
                onEnterGame = { navController.navigate(GameRoute) },
                onOpenCatalog = { navController.navigate(CatalogRoute) },
                connectionStatusBar = connectionStatusBar,
            )
        }
        composable<GameRoute> {
            ImmersiveGameScreen(onExit = { navController.popBackStack() })
        }
        composable<CatalogRoute> {
            ComponentCatalogScreen(onExit = { navController.popBackStack() })
        }
    }
}
