package magefree.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import kotlinx.serialization.Serializable
import magefree.app.screens.HomeScreen
import magefree.app.screens.ProfilePlaceholderScreen
import magefree.app.screens.SettingsPlaceholderScreen
import magefree.feature.tables.RoomArgs
import magefree.feature.tables.TableRole
import magefree.feature.tables.join.JoinTarget
import magefree.feature.tables.room.TableRoomViewModel
import magefree.feature.cards.CardsRoute as CardsFeatureRoute
import magefree.feature.game.GameBoardRoute as GameBoardFeatureRoute
import magefree.feature.lobby.LobbyRoute as LobbyFeatureRoute
import magefree.feature.tables.HostTableRoute as HostTableFeatureRoute
import magefree.feature.tables.JoinTableRoute as JoinTableFeatureRoute
import magefree.feature.tables.TableRoomRoute as TableRoomFeatureRoute

/**
 * Type-safe route for the lobby browser (story 0029), reached from the home "Play" entry. It is a
 * nested destination inside the shell — not a top-level tab — so the tab chrome and the connection
 * strip stay visible above it and Back returns to Home.
 */
@Serializable
data object LobbyRoute

/**
 * Type-safe route for the read-only card catalog browser (story 0032), reached from the Decks library's
 * "Browse cards" action (story 0035). A nested destination inside the shell (not a top-level tab), so
 * the tab chrome and connection strip stay visible above it and Back returns to Decks.
 */
@Serializable
data object CardsRoute

/**
 * Type-safe route for hosting a table (story 0038): the create-table form. A nested destination within
 * the lobby's back stack (reached from the lobby's Host action), so the tab chrome stays visible and
 * Back returns to the lobby.
 */
@Serializable
data object HostTableNavRoute

/**
 * Type-safe route for joining a lobby table (story 0038): the deck pick + legality + submit flow. Carries
 * the tapped table's identity/format so the offline legality gate can run before the join.
 */
@Serializable
data class JoinTableNavRoute(
    val tableId: String,
    val tableName: String,
    val gameType: String,
    val deckType: String,
    val passworded: Boolean,
)

/**
 * Type-safe route for the table room (story 0038): seats/ready/start/leave over `observeTable`, reached
 * by hosting, joining, or watching. [role] is a [TableRole] name (`Host`/`Player`/`Spectator`) chosen at
 * entry so the action set is deterministic before the first server push.
 */
@Serializable
data class TableRoomNavRoute(
    val tableId: String,
    val tableName: String,
    val gameType: String,
    val role: String,
)

/**
 * Type-safe route for the read-only game board (story 0055, `:feature:game`).
 *
 * It carries the **game** id, which is a different identifier from the table id the room is keyed by:
 * the server mints it when the match starts and delivers it on the table's `MatchStarting` push
 * (`magefree.network.table.MatchStarting.gameId`). That push is the only producer of a game id in the
 * whole app, and until this story nothing consumed it — the room rendered a terminal "Match starting…"
 * screen and the hand-off stopped there.
 *
 * Mounted here, inside the shell graph, rather than beside story 0011's immersive [magefree.app.game.GameRoute]:
 * the board is reached from the room, which lives in this graph, and keeping it here means Back behaves
 * and the connection strip stays visible over a live game. The trade-off is that the shell's tab chrome
 * takes vertical space from a portrait board; moving the board outside the chrome is a deliberate
 * follow-up, not something to improvise inside this story.
 */
@Serializable
data class GameBoardNavRoute(
    val gameId: String,
)

/** Map the feature's [RoomArgs] hand-off onto the type-safe [TableRoomNavRoute]. */
private fun RoomArgs.toNavRoute(): TableRoomNavRoute =
    TableRoomNavRoute(tableId = tableId, tableName = tableName, gameType = gameType, role = role.name)

/**
 * The Navigation-Compose host for the top-level destinations, wired with **type-safe** routes:
 * each `composable<Route>` entry is keyed by a [Serializable][kotlinx.serialization.Serializable]
 * route type, not a string. [HomeRoute] is the start destination.
 *
 * It hosts the four top-level destinations plus the nested destinations reached from them — the lobby
 * browser, the card catalog, and the host/join/room table flows — each keyed by its own route type.
 * Deeper navigation *inside* a feature is that feature's own concern and does not surface here.
 */
@Composable
fun MageNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
    onEnterGame: () -> Unit = {},
    onOpenCatalog: () -> Unit = {},
    onSignOut: () -> Unit = {},
    decksScreen: @Composable () -> Unit = {},
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
                // The Play entry opens the lobby browser, from which a table can be hosted, joined or
                // watched. Kept within the Home tab's back stack so Back returns here.
                onPlayClick = { navController.navigate(LobbyRoute) },
                onDecksClick = { navigateToTab(DecksRoute) },
                onProfileClick = { navigateToTab(ProfileRoute) },
                onSettingsClick = { navigateToTab(SettingsRoute) },
            )
        }
        composable<LobbyRoute> {
            // Host/Join/Watch each open a nested tables destination within the lobby's back stack
            // (chrome stays, Back returns to the lobby).
            LobbyFeatureRoute(
                onBack = { navController.popBackStack() },
                onHost = { navController.navigate(HostTableNavRoute) },
                onJoin = { table ->
                    navController.navigate(
                        JoinTableNavRoute(
                            tableId = table.id,
                            tableName = table.name,
                            gameType = table.gameType,
                            deckType = table.deckType,
                            passworded = table.isPassworded,
                        ),
                    )
                },
                onWatch = { table ->
                    navController.navigate(
                        TableRoomNavRoute(
                            tableId = table.id,
                            tableName = table.name,
                            gameType = table.gameType,
                            role = TableRole.Spectator.name,
                        ),
                    )
                },
            )
        }
        composable<HostTableNavRoute> {
            HostTableFeatureRoute(
                onBack = { navController.popBackStack() },
                // On create, replace the form with the room so Back from the room returns to the lobby.
                onOpenRoom = { args ->
                    navController.navigate(args.toNavRoute()) { popUpTo(LobbyRoute) { inclusive = false } }
                },
                onBuildDeck = { navigateToTab(DecksRoute) },
            )
        }
        composable<JoinTableNavRoute> { entry ->
            val route = entry.toRoute<JoinTableNavRoute>()
            JoinTableFeatureRoute(
                target =
                    JoinTarget(
                        tableId = route.tableId,
                        tableName = route.tableName,
                        gameType = route.gameType,
                        deckType = route.deckType,
                        passworded = route.passworded,
                    ),
                onBack = { navController.popBackStack() },
                onOpenRoom = { args ->
                    navController.navigate(args.toNavRoute()) { popUpTo(LobbyRoute) { inclusive = false } }
                },
                onBuildDeck = { navigateToTab(DecksRoute) },
            )
        }
        composable<TableRoomNavRoute> { entry ->
            val route = entry.toRoute<TableRoomNavRoute>()
            // The room's own ViewModel, resolved here and handed to the feature route so both read the
            // *same* instance (`hiltViewModel()` is scoped to this back-stack entry either way). That is
            // what lets the graph watch for the match-start hand-off without a second subscription to
            // `observeTable`, and without `:feature:tables` having to grow a navigation callback.
            val roomViewModel: TableRoomViewModel = hiltViewModel()
            val roomState by roomViewModel.uiState.collectAsStateWithLifecycle()

            // Story 0055: the Epic-7 hand-off, finally connected. `MatchStarting` is the server's own
            // "your game exists and its id is this" push (story 0037's `TableState.matchStarting`); the
            // board is opened with that id and the room is popped, so Back from a live game returns to
            // the lobby rather than to a room whose match has already begun.
            //
            // Story 0069: `matchStarting` is a *one-shot* push — it fires only for whichever client is
            // sitting in the room at the instant the game starts. A client that opens (or re-opens) the
            // room afterwards — back from the lobby, or after a relaunch — never receives it again, and
            // was stranded with no way back into its own active game. `TableState.resumableGameId` also
            // considers `activeGameId`, which `observeTable`'s unconditional open-time table read
            // populates on *every* read, so this now fires for a late arrival exactly as it does for the
            // live push.
            val startedGameId = roomState.table.resumableGameId
            LaunchedEffect(startedGameId) {
                startedGameId?.let { gameId ->
                    navController.navigate(GameBoardNavRoute(gameId = gameId)) {
                        popUpTo(LobbyRoute) { inclusive = false }
                        launchSingleTop = true
                    }
                }
            }

            TableRoomFeatureRoute(
                args =
                    RoomArgs(
                        tableId = route.tableId,
                        tableName = route.tableName,
                        gameType = route.gameType,
                        role = runCatching { TableRole.valueOf(route.role) }.getOrDefault(TableRole.Spectator),
                    ),
                onExit = { navController.popBackStack() },
                onBuildDeck = { navigateToTab(DecksRoute) },
                viewModel = roomViewModel,
            )
        }
        composable<GameBoardNavRoute> { entry ->
            val route = entry.toRoute<GameBoardNavRoute>()
            GameBoardFeatureRoute(
                gameId = route.gameId,
                onExit = { navController.popBackStack() },
            )
        }
        composable<DecksRoute> {
            // Story 0035: the Decks tab hosts the deck library + builder (`:feature:decks`), provided by
            // the caller so the shell can stay Hilt-free in tests. Card browse remains reachable from the
            // library's "Browse cards" action, which navigates to [CardsRoute] within the Decks back stack.
            decksScreen()
        }
        composable<CardsRoute> {
            CardsFeatureRoute(onBack = { navController.popBackStack() })
        }
        composable<ProfileRoute> { ProfilePlaceholderScreen() }
        composable<SettingsRoute> {
            SettingsPlaceholderScreen(
                // Stub entry into the immersive game route. The table room does not open the board
                // yet: the real entry is the room's match-start signal, which belongs to the in-game
                // epic (Epic 11 — see `magefree.network.table.MatchStarting`).
                onEnterGame = onEnterGame,
                // Debug-only entry into the design-system component catalog (story 0015).
                onOpenCatalog = onOpenCatalog,
                // Story 0047: the shell's one deliberate exit from the session. Hoisted all the way to
                // the root graph, which tears the session down (0046's `signOut()` sends `Logout`) and
                // returns to the connect flow, so the player can sign in again.
                onSignOut = onSignOut,
            )
        }
    }
}
