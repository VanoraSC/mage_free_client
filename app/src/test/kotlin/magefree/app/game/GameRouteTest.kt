package magefree.app.game

import magefree.app.navigation.ShellRoute
import magefree.app.navigation.TopLevelDestination
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Hermetic JVM unit test for the [GameRoute] wiring — part of the `./gradlew check` gate, no Android
 * framework or device required. It pins the invariant that the game is a distinct route hosted
 * **outside** the top-level tab set, so the shell chrome is excluded on it: the board is three columns
 * sized by the window they are given, and a navigation rail down the left takes that width off the
 * battlefield.
 */
class GameRouteTest {
    @Test
    fun gameRouteIsNotATopLevelTabDestination() {
        val tabRoutes = TopLevelDestination.entries.map { it.route }
        assertFalse(
            "GameRoute must not be one of the tabbed top-level destinations",
            // An instance, because the route carries a game id: a tab is a place, and a game is a
            // place with a game in it.
            tabRoutes.contains(GameRoute(gameId = "g-1")),
        )

        val tabRouteClasses = TopLevelDestination.entries.map { it.routeClass }
        assertFalse(
            "GameRoute's class must not be registered as a tab route",
            tabRouteClasses.contains(GameRoute::class),
        )
    }

    @Test
    fun gameAndShellRoutesAreDistinctRootDestinations() {
        assertNotEquals(GameRoute::class, ShellRoute::class)
    }
}
