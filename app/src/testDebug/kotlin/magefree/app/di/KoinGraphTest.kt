package magefree.app.di

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.core.annotation.KoinInternalApi
import org.koin.core.context.GlobalContext
import org.koin.core.context.stopKoin
import org.koin.dsl.koinApplication
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * **The replacement for Hilt's compile-time safety.**
 *
 * Hilt failed the *build* when a binding was missing. Koin fails at *runtime*, on whichever screen
 * first asks for it — so a mechanical conversion that misses one binding compiles cleanly, ships,
 * and crashes on a screen nobody may open for weeks. This test closes that gap, and the conversion
 * is not verified without it.
 *
 * **It runs against the container the real application started.** Robolectric instantiates
 * [magefree.app.MageApp], whose `onCreate` calls `startKoin { modules(appModules) }` — so
 * [GlobalContext] here holds the graph the app actually boots with, not one the test assembled. A
 * test that built its own list would verify a graph the app does not run.
 *
 * **Every definition is instantiated, not merely type-checked.** Koin's `verify()` inspects
 * constructor parameters by reflection, which sees nothing inside a `single { Foo(bar = get()) }`
 * lambda — and lambda-bodied definitions are most of this graph, because several bindings do real
 * work when constructed: the Room database is built, the card catalog opens the bundled SQLite
 * asset, both DataStores are created. A binding that resolves in principle and throws in practice is
 * precisely what this is exposed to, so the check constructs each one.
 *
 * Enumerating definitions needs `@KoinInternalApi`. That is a deliberate, test-only trade: the
 * alternative is a hand-maintained list of types to resolve, which reintroduces exactly the
 * "somebody must remember to add it" failure the test exists to eliminate. If a Koin upgrade breaks
 * it, the fix belongs here rather than in a weaker check.
 */
@RunWith(RobolectricTestRunner::class)
class KoinGraphTest {
    /**
     * Koin's global container is process-wide and Robolectric does **not** reset it between test
     * methods, while it does build a fresh `Application` for each one. Without this teardown the
     * second method's `MageApp.onCreate` calls `startKoin` against a container the first method left
     * running, and fails with `KoinApplicationAlreadyStartedException` — a test-harness artefact, not
     * a defect in the app, since `onCreate` genuinely runs once per process in production.
     */
    @After
    fun tearDown() {
        stopKoin()
    }

    @OptIn(KoinInternalApi::class)
    @Test
    fun `every binding the running app declares can be instantiated`() {
        val koin = GlobalContext.get()

        val failures =
            appModules
                .flatMap { module -> module.mappings.values }
                .map { it.beanDefinition }
                .mapNotNull { definition ->
                    runCatching { koin.get<Any>(definition.primaryType, definition.qualifier, null) }
                        .exceptionOrNull()
                        ?.let { error ->
                            val name = definition.primaryType.simpleName
                            val qualifier = definition.qualifier?.let { q -> " ($q)" }.orEmpty()
                            "$name$qualifier -> $error"
                        }
                }

        assertTrue(
            failures.isEmpty(),
            "Bindings declared in appModules that could not be instantiated:\n${failures.joinToString("\n")}",
        )
    }

    @Test
    fun `the graph check fails when a module is missing`() {
        // Standard 1: prove the guard discriminates. A check that passed against a broken graph would
        // manufacture exactly the confidence it exists to provide — and with no compile-time error to
        // fall back on, that would leave the whole conversion unverified.
        //
        // Order matters. Resolving the context is what makes Robolectric instantiate MageApp, whose
        // `onCreate` starts a container — so the context must be fetched *before* stopping it, or the
        // stop happens first and the app immediately starts another one. Stopping is safe because
        // Robolectric builds a fresh Application per test method, so the sibling test gets its own.
        val context = ApplicationProvider.getApplicationContext<Context>()
        stopKoin()

        val incomplete = appModules - connectionModule
        val koin =
            koinApplication {
                androidContext(context)
                modules(incomplete)
            }.koin

        assertFailsWith<Exception> { koin.get<magefree.app.core.DispatcherProvider>() }
    }
}
