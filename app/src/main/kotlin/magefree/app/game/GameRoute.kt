package magefree.app.game

import kotlinx.serialization.Serializable

/**
 * Type-safe Navigation-Compose route for a **game in progress**.
 *
 * It is deliberately **not** a
 * [TopLevelDestination][magefree.app.navigation.TopLevelDestination]: the game is a focused,
 * full-screen surface hosted **outside** the tabbed
 * [AppShell][magefree.app.navigation.AppShell] chrome by the root
 * [AppNavHost][magefree.app.navigation.AppNavHost]. Navigating here therefore replaces the shell
 * entirely — no bottom bar / rail and no connection strip — leaving the board edge-to-edge,
 * landscape and immersive.
 *
 * **The chrome is not a preference, it is the story.** The board is three columns of cards sized by
 * the space they are given, and a navigation rail down its left in landscape takes that space from
 * the battlefield. `BattlefieldPreviewScreen` needed the whole window for the same reason and became
 * a root destination first; 0112 brought the real board here to join it.
 *
 * @property gameId the game to observe — in production the id the table's `MatchStarting` push
 *   carried, which is the only place a game id comes from.
 */
@Serializable
data class GameRoute(
    val gameId: String,
)
