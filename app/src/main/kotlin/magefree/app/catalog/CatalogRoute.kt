package magefree.app.catalog

import kotlinx.serialization.Serializable

/**
 * Type-safe Navigation-Compose route for the **developer component catalog** (story 0015).
 *
 * Like [GameRoute][magefree.app.game.GameRoute], it is deliberately **not** a
 * [TopLevelDestination][magefree.app.navigation.TopLevelDestination]: the catalog is a debug-only
 * visual-QA surface hosted **outside** the tabbed
 * [AppShell][magefree.app.navigation.AppShell] by the root
 * [AppNavHost][magefree.app.navigation.AppNavHost], reached from the Settings dev entry. It is not a
 * production feature and is expected to stay behind that clearly-marked debug affordance.
 */
@Serializable
data object CatalogRoute
