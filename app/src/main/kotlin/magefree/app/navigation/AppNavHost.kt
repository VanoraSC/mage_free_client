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
import magefree.feature.connect.ConnectFlow

/**
 * Type-safe route for the tabbed browsing shell — the [AppShell] with its bottom-bar / rail chrome
 * and the four top-level destinations. It is entered from [ConnectRoute] once a session is live.
 */
@Serializable
data object ShellRoute

/**
 * Type-safe route for the connect + sign-in flow (`:feature:connect`, EPIC-04), mounted by story 0047.
 *
 * It is the **start destination** of the root [AppNavHost] and renders *outside* the shell chrome, the
 * same way [GameRoute] does: no tab bar and no connection strip over a sign-in form.
 */
@Serializable
data object ConnectRoute

/**
 * The **root** Navigation-Compose host, sitting *around* the shell so the connect flow and the
 * immersive [GameRoute] can render **outside** the tab chrome (story 0011's chosen approach — a
 * top-level `NavHost` above the shell).
 *
 * - [ConnectRoute] renders the connect + sign-in flow, chrome-free. **The start destination.**
 * - [ShellRoute] renders the full [AppShell], which owns its **own** inner nav controller for the
 *   Home/Decks/Profile/Settings tabs. The connection strip lives inside the shell.
 * - [GameRoute] renders the full-screen [ImmersiveGameScreen] with no shell chrome at all — no
 *   bottom bar / rail, no connection strip — so the game surface is edge-to-edge and immersive.
 * - [CatalogRoute] renders the debug-only [ComponentCatalogScreen] (story 0015), also outside the
 *   shell chrome; it is reached from the Settings dev entry.
 *
 * Entering the game / opening the catalog are hoisted actions ([AppShell]'s `onEnterGame` /
 * `onOpenCatalog`) so the shell needs no knowledge of the root graph; exiting simply pops back.
 *
 * ## Entry policy (story 0047)
 *
 * **A launch begins on the connect flow, and the shell is only ever entered with a live session.**
 *
 * A cold process provably has no session: `ConnectionRepository` holds the connect intent in memory
 * only (a `MutableStateFlow<Command?>` seeded `null`, cleared by `signOut()`), and nothing anywhere
 * persists credentials or a session token across process death. So "start on the connect flow when
 * there is no session" is, at cold start, unconditional — which is why this graph states it as a fixed
 * [startDestination][NavHost] rather than a runtime branch that could only ever go one way. Making it
 * a branch would be a gate that reads state nothing produces, which is exactly the defect class this
 * story fixes.
 *
 * The two transitions keep that invariant true:
 * - **sign-in success** ([connectFlow]'s `onConnected`) navigates to [ShellRoute] popping [ConnectRoute]
 *   inclusively, so Back from Home leaves the app instead of dropping into a sign-in form behind a live
 *   session;
 * - **sign-out** ([AppShell]'s `onSignOut`, surfaced in Settings) tears the session down via [onSignOut]
 *   and navigates back to [ConnectRoute] popping [ShellRoute] inclusively, so the user lands on sign-in
 *   again with nothing stale behind them.
 *
 * ## Who owns "get me connected"
 *
 * - **The connect flow owns the *first* connection.** It is the only place in the app that holds
 *   credentials, and `SignInViewModel.connect(...)` → `ConnectionRepository.connect(server, credentials)`
 *   is the only API that opens a session.
 * - **The status bar's Retry owns *re-establishing an existing* one.** It delegates to
 *   `ConnectionRepository.retry()`, which re-runs the last connect command and is a no-op when there is
 *   none. The strip lives inside the shell, and the entry policy above means the shell is only reachable
 *   after a successful connect — so wherever Retry is visible there is always a command to re-run. It is
 *   never the first-connect affordance, and it is never the dead control it was before this story (when
 *   the app launched straight into the shell with no session and Retry had nothing to retry).
 *
 * @param connectFlow the connect destination's content, hoisted so the shell's navigation tests can
 *   drive the graph without a Hilt graph; production uses the default, the real `ConnectFlow`.
 * @param onSignOut deliberate session teardown, invoked before navigating back to [ConnectRoute].
 *   Hoisted (default no-op) for the same reason; `AppRoot` supplies the repository-backed one.
 */
@Composable
fun AppNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    connectionStatusBar: @Composable () -> Unit = { ConnectionStatusBar() },
    connectFlow: @Composable (onConnected: () -> Unit) -> Unit = { onConnected ->
        ConnectFlow(onConnected = onConnected)
    },
    onSignOut: () -> Unit = {},
) {
    NavHost(
        navController = navController,
        startDestination = ConnectRoute,
        modifier = modifier,
    ) {
        composable<ConnectRoute> {
            connectFlow {
                // Sign-in succeeded: hand off to the shell and drop the connect flow from the back
                // stack, so Back from Home exits rather than returning to sign-in over a live session.
                navController.navigate(ShellRoute) {
                    popUpTo(ConnectRoute) { inclusive = true }
                    launchSingleTop = true
                }
            }
        }
        composable<ShellRoute> {
            AppShell(
                onEnterGame = { navController.navigate(GameRoute) },
                onOpenCatalog = { navController.navigate(CatalogRoute) },
                connectionStatusBar = connectionStatusBar,
                onSignOut = {
                    // Teardown first (story 0046: a deliberate exit sends Logout, it is not a drop),
                    // then return to the connect flow with the signed-out shell popped.
                    onSignOut()
                    navController.navigate(ConnectRoute) {
                        popUpTo(ShellRoute) { inclusive = true }
                        launchSingleTop = true
                    }
                },
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
