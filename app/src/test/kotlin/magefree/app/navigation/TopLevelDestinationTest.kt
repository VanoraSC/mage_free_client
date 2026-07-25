package magefree.app.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hermetic JVM unit test for the [TopLevelDestination] model — part of the `./gradlew check` gate,
 * no Android framework or device required. It pins the shell's invariants: exactly the four
 * Arena-style destinations, Home first, unique routes, and an accessible label + content description
 * on every item.
 */
class TopLevelDestinationTest {
    private val destinations = TopLevelDestination.entries

    @Test
    fun hasExactlyFourDestinationsWithHomeFirst() {
        assertEquals(4, destinations.size)
        assertEquals(TopLevelDestination.HOME, destinations.first())
    }

    @Test
    fun routesAreUnique() {
        val routes = destinations.map { it.route }
        assertEquals(routes.size, routes.toSet().size)

        val routeClasses = destinations.map { it.routeClass }
        assertEquals(routeClasses.size, routeClasses.toSet().size)
    }

    @Test
    fun routeClassMatchesRouteInstance() {
        destinations.forEach { destination ->
            assertEquals(destination.route::class, destination.routeClass)
        }
    }

    @Test
    fun everyDestinationHasALabelAndContentDescription() {
        destinations.forEach { destination ->
            assertTrue(
                "label must be non-blank for $destination",
                destination.label.isNotBlank(),
            )
            assertTrue(
                "contentDescription must be non-blank for $destination",
                destination.contentDescription.isNotBlank(),
            )
        }
    }

    @Test
    fun coversTheFourTopLevelRoutes() {
        val expected = setOf(HomeRoute, DecksRoute, ProfileRoute, SettingsRoute)
        assertEquals(expected, destinations.map { it.route }.toSet())
    }
}
