package magefree.app.catalog

import kotlinx.serialization.Serializable

/**
 * Type-safe Navigation-Compose route for the **developer component catalog**.
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

/**
 * Route for the **full-window battlefield preview**, reached from the catalog's battlefield section.
 *
 * A route of its own rather than a box inside [CatalogRoute] because the board needs the whole window
 * and its own orientation: §7.4's rules are all about fitting a real one, and none of them can be
 * assessed in a letterbox inside a scrolling portrait column. Debug-only, like the catalog itself.
 */
@Serializable
data object BattlefieldPreviewRoute
