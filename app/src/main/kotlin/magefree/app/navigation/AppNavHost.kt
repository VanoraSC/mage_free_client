package magefree.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.serialization.Serializable
import magefree.app.catalog.BattlefieldPreviewRoute
import magefree.app.catalog.BattlefieldPreviewScreen
import magefree.app.catalog.CatalogRoute
import magefree.app.catalog.ComponentCatalogScreen
import magefree.app.catalog.rememberBattlefieldArtResolver
import magefree.app.catalog.rememberCatalogArtResolver
import magefree.app.connection.ui.ConnectionStatusBar
import magefree.app.game.GameRoute
import magefree.app.game.ImmersiveGameScreen
import magefree.feature.connect.ConnectFlow
import magefree.feature.cards.CardsRoute as CardsFeatureRoute
import magefree.feature.decks.DecksRoute as DecksLibraryRoute

/**
 * Type-safe route for the tabbed browsing shell — the [AppShell] with its bottom-bar / rail chrome
 * and the four top-level destinations. It is entered from [ConnectRoute] once a session is live.
 */
@Serializable
data object ShellRoute

/**
 * Type-safe route for the connect + sign-in flow (`:feature:connect`).
 *
 * It is the **start destination** of the root [AppNavHost] and renders *outside* the shell chrome, the
 * same way [GameRoute] does: no tab bar and no connection strip over a sign-in form.
 */
@Serializable
data object ConnectRoute

/**
 * The **root** Navigation-Compose host, sitting *around* the shell so the connect flow and the
 * immersive [GameRoute] can render **outside** the tab chrome (the chosen approach — a
 * top-level `NavHost` above the shell).
 *
 * - [ConnectRoute] renders the connect + sign-in flow, chrome-free. **The start destination.**
 * - [ShellRoute] renders the full [AppShell], which owns its **own** inner nav controller for the
 *   Home/Decks/Profile/Settings tabs. The connection strip lives inside the shell.
 * - [GameRoute] renders the full-screen [ImmersiveGameScreen] with no shell chrome at all — no
 *   bottom bar / rail, no connection strip — so the game surface is edge-to-edge and immersive.
 * - [CatalogRoute] renders the debug-only [ComponentCatalogScreen], also outside the
 *   shell chrome; it is reached from the Settings dev entry.
 *
 * Entering the game / opening the catalog are hoisted actions ([AppShell]'s `onEnterGame` /
 * `onOpenCatalog`) so the shell needs no knowledge of the root graph; exiting simply pops back.
 *
 * ## Entry policy
 *
 * **A launch begins on the connect flow, and the shell is only ever entered with a live session.**
 *
 * A cold process provably has no session: `ConnectionRepository` holds the connect intent in memory
 * only (a `MutableStateFlow<Command?>` seeded `null`, cleared by `signOut()`), and nothing anywhere
 * persists credentials or a session token across process death. So "start on the connect flow when
 * there is no session" is, at cold start, unconditional — which is why this graph states it as a fixed
 * [startDestination][NavHost] rather than a runtime branch that could only ever go one way. Making it
 * a branch would be a gate that reads state nothing produces, which is exactly the defect class this
 * fixes.
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
 *   never the first-connect affordance, and it is never the dead control it was without it (when
 *   the app launched straight into the shell with no session and Retry had nothing to retry).
 *
 * ## Deckbuilding without a session
 *
 * Deck storage, format legality and the card catalog are all on the device, and nothing in
 * `:feature:decks` or `:feature:cards` reads a session — only art fetching touches the network. So
 * [DecksRoute] and [CardsRoute] are mounted **here** as well as in the shell, chrome-free like
 * [GameRoute], and the server-list screen offers a way in. Back returns to the server list, because
 * the connect destination stays on the stack.
 *
 * This is a second mount point, not a relaxation of the entry policy above: the shell still requires a
 * live session. Letting the shell in without one would make the lobby, tables and the connection strip
 * reachable in a state where none of them can work.
 *
 * @param connectFlow the connect destination's content, hoisted so the shell's navigation tests can
 *   drive the graph without a DI container; production uses the default, the real `ConnectFlow`. It
 *   raises `onConnected` on a successful sign-in and `onOpenDecks` from the server list's offline
 *   entry.
 * @param decksScreen the offline deck library's content, and @param cardsScreen the card browser's.
 *   Hoisted for the same reason [connectFlow] is: both resolve their ViewModels through the DI
 *   container, and the navigation guards drive this graph without one. Production uses the defaults.
 * @param onSignOut deliberate session teardown, invoked before navigating back to [ConnectRoute].
 *   Hoisted (default no-op) for the same reason; `AppRoot` supplies the repository-backed one.
 */
@Composable
fun AppNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    connectionStatusBar: @Composable () -> Unit = { ConnectionStatusBar() },
    connectFlow: @Composable (
        onConnected: () -> Unit,
        onOpenDecks: () -> Unit,
        onOpenCatalog: () -> Unit,
    ) -> Unit = { onConnected, onOpenDecks, onOpenCatalog ->
        ConnectFlow(onConnected = onConnected, onOpenDecks = onOpenDecks, onOpenCatalog = onOpenCatalog)
    },
    decksScreen: @Composable (onBrowseCards: () -> Unit) -> Unit = { onBrowseCards ->
        DecksLibraryRoute(onBrowseCards = onBrowseCards)
    },
    cardsScreen: @Composable (onBack: () -> Unit) -> Unit = { onBack -> CardsFeatureRoute(onBack = onBack) },
    catalogScreen: @Composable (onExit: () -> Unit, onOpenBattlefield: () -> Unit) -> Unit = { onExit, onOpenBattlefield ->
        // Real card art is resolved here, so the screen itself stays previewable and the graph tests
        // can substitute a stand-in rather than needing a DI container.
        ComponentCatalogScreen(
            onExit = onExit,
            artFor = rememberCatalogArtResolver(),
            onOpenBattlefield = onOpenBattlefield,
        )
    },
    onSignOut: () -> Unit = {},
) {
    NavHost(
        navController = navController,
        startDestination = ConnectRoute,
        modifier = modifier,
    ) {
        composable<ConnectRoute> {
            connectFlow(
                {
                    // Sign-in succeeded: hand off to the shell and drop the connect flow from the back
                    // stack, so Back from Home exits rather than returning to sign-in over a live session.
                    navController.navigate(ShellRoute) {
                        popUpTo(ConnectRoute) { inclusive = true }
                        launchSingleTop = true
                    }
                },
                {
                    // Offline decks: navigate without popping, so Back returns to the server list.
                    navController.navigate(DecksRoute) { launchSingleTop = true }
                },
                {
                    // The component catalog, reached the same way and for the same reason: it needs
                    // no session either.
                    navController.navigate(CatalogRoute) { launchSingleTop = true }
                },
            )
        }
        composable<DecksRoute> {
            decksScreen { navController.navigate(CardsRoute) }
        }
        composable<CardsRoute> {
            cardsScreen { navController.popBackStack() }
        }
        composable<ShellRoute> {
            AppShell(
                onEnterGame = { navController.navigate(GameRoute) },
                onOpenCatalog = { navController.navigate(CatalogRoute) },
                connectionStatusBar = connectionStatusBar,
                onSignOut = {
                    // Teardown first (a deliberate exit sends Logout, it is not a drop),
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
        composable<BattlefieldPreviewRoute> {
            // The battlefield needs the whole window and its own orientation, so it is a route of its
            // own rather than a box inside the catalog. See BattlefieldPreviewScreen.
            BattlefieldPreviewScreen(
                onExit = { navController.popBackStack() },
                artFor = rememberBattlefieldArtResolver(),
            )
        }
        composable<CatalogRoute> {
            catalogScreen(
                { navController.popBackStack() },
                { navController.navigate(BattlefieldPreviewRoute) },
            )
        }
    }
}
