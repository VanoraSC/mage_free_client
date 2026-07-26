package magefree.bridge

import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.runTestApplication
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.minutes

/**
 * A drop-in replacement for Ktor's `testApplication` that gives the test body a generous, explicit
 * completion budget.
 *
 * `testApplication { }` runs its body inside `kotlinx-coroutines-test` `runTest`, whose default
 * timeout is **60s**. On a cold-start test JVM over the slow `/mnt/c` container bind mount, the first
 * `testApplication`-based test to run pays the class-load + JIT warmup and can exceed 60s, flaking the
 * hermetic gate with `UncompletedCoroutinesError: the test body did not run to completion`. Passing an
 * explicit [runTest] `timeout` avoids that (the value is honored directly — unlike the
 * `kotlinx.coroutines.test.default_timeout` system property, which does not propagate to the Gradle
 * test worker here). Genuine hangs are still bounded, and integration tests keep their own inner
 * `withTimeout` guards.
 *
 * Use this instead of `testApplication` for every WebSocket/HTTP test in this module.
 */
fun testApplicationTimed(block: suspend ApplicationTestBuilder.() -> Unit) =
    runTest(timeout = 2.minutes) {
        runTestApplication(block = block)
    }
