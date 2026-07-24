package magefree.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Trivial JVM unit test — no Android framework — proving the local unit-test toolchain runs as part
 * of the hermetic `./gradlew check` gate. It pins the placeholder text the Compose smoke test also
 * asserts, so the two stay in sync.
 */
class AppRootPlaceholderTest {
    @Test
    fun placeholderTextIsTheAppName() {
        assertEquals("Mage Free Client", APP_ROOT_PLACEHOLDER)
    }

    @Test
    fun placeholderTextIsNotBlank() {
        assertTrue(APP_ROOT_PLACEHOLDER.isNotBlank())
    }
}
