package magefree.app.game

import kotlinx.serialization.Serializable

/**
 * Type-safe Navigation-Compose route for the **immersive in-game mode**.
 *
 * It is deliberately **not** a
 * [TopLevelDestination][magefree.app.navigation.TopLevelDestination]: the game is a focused,
 * full-screen surface hosted **outside** the tabbed
 * [AppShell][magefree.app.navigation.AppShell] chrome by the root
 * [AppNavHost][magefree.app.navigation.AppNavHost]. Navigating here therefore replaces the shell
 * entirely — no bottom bar / rail and no connection strip — leaving the placeholder game surface
 * edge-to-edge and immersive.
 */
@Serializable
data object GameRoute
