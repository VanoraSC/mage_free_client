package magefree.cards.art

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.Collections

/**
 * Failure **containment** for the bulk pre-download.
 *
 * The production app scope is a `SupervisorJob` with **no** `CoroutineExceptionHandler`, so anything
 * that escapes [ArtDownloadManager.run] reaches the default handler and crashes the process. These
 * tests give the scope a *recording* handler instead of a crashing one: an empty recording is the
 * assertion that nothing escaped, and the progress snapshot is the assertion that the run still
 * reached a terminal state instead of freezing at `RUNNING`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ArtDownloadManagerFailureTest {
    private fun requests(count: Int): List<CardArtRequest> = (1..count).map { CardArtRequest("SET", it.toString()) }

    /** Everything a run's app scope swallowed instead of crashing the process. */
    private class UncaughtRecorder {
        val thrown: MutableList<Throwable> = Collections.synchronizedList(ArrayList())

        val handler: CoroutineExceptionHandler = CoroutineExceptionHandler { _, error -> thrown += error }
    }

    private fun TestScope.newManager(
        targets: List<CardArtRequest>,
        warmer: ArtWarmer,
        recorder: UncaughtRecorder,
        maxConcurrency: Int = 4,
    ): ArtDownloadManager =
        ArtDownloadManager(
            targetSource = FixedTargets(targets),
            warmer = warmer,
            appScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler) + recorder.handler),
            maxConcurrency = maxConcurrency,
            politeDelayMs = 0,
        )

    private class FixedTargets(
        private val targets: List<CardArtRequest>,
    ) : PrefetchTargetSource {
        override suspend fun requests(
            scope: PrefetchScope,
            size: CardArtSize,
        ): List<CardArtRequest> = targets.map { it.copy(size = size) }
    }

    /**
     * The real defect shape: `CardImageLoader.isCached` calls `DiskCache.openSnapshot`, which throws
     * `IOException` on a corrupt/unreadable entry.
     */
    private class ProbeThrowsFor(
        private val collectorNumber: String,
    ) : ArtWarmer {
        val warmed: MutableList<CardArtRequest> = Collections.synchronizedList(ArrayList())

        override suspend fun isCached(request: CardArtRequest): Boolean {
            if (request.collectorNumber == collectorNumber) throw IOException("corrupt disk cache entry")
            return false
        }

        override suspend fun warm(request: CardArtRequest): Boolean {
            warmed += request
            return true
        }
    }

    @Test
    fun `a throwing cache probe is counted and skipped, not fatal`() =
        runTest {
            val recorder = UncaughtRecorder()
            val warmer = ProbeThrowsFor(collectorNumber = "3")
            val manager = newManager(requests(5), warmer, recorder)

            manager.start(PrefetchScope.All, setOf(CardArtSize.SMALL))
            advanceUntilIdle()

            val progress = manager.progress.value
            assertTrue(
                "nothing may escape the app scope, got ${recorder.thrown} (status=${progress.status})",
                recorder.thrown.isEmpty(),
            )
            assertNotEquals("progress must never be left stuck at RUNNING", PrefetchStatus.RUNNING, progress.status)
            assertTrue("the run must reach a terminal state", progress.isTerminal)
            assertEquals(PrefetchStatus.COMPLETED, progress.status)
            assertEquals("the one throwing target is counted as failed", 1, progress.failed)
            assertEquals("every other target still warmed", 4, progress.warmed)
            assertEquals(5, progress.total)
            assertEquals(5, progress.done)
            assertEquals(4, warmer.warmed.size)
        }

    @Test
    fun `a throwing warm is counted and skipped, not fatal`() =
        runTest {
            val recorder = UncaughtRecorder()
            val warmer =
                object : ArtWarmer {
                    override suspend fun isCached(request: CardArtRequest): Boolean = false

                    override suspend fun warm(request: CardArtRequest): Boolean {
                        if (request.collectorNumber == "2") throw IOException("decode blew up")
                        return true
                    }
                }
            val manager = newManager(requests(4), warmer, recorder)

            manager.start(PrefetchScope.All, setOf(CardArtSize.SMALL))
            advanceUntilIdle()

            val progress = manager.progress.value
            assertTrue(
                "nothing may escape the app scope, got ${recorder.thrown} (status=${progress.status})",
                recorder.thrown.isEmpty(),
            )
            assertEquals(PrefetchStatus.COMPLETED, progress.status)
            assertEquals(1, progress.failed)
            assertEquals(3, progress.warmed)
            assertEquals(4, progress.done)
        }

    /**
     * The containment above must not be built out of a blanket `catch (Exception)`: in Kotlin,
     * `CancellationException` **is** an `Exception`, so a blanket catch would quietly convert user
     * cancellation into "failed targets" and let the run report COMPLETED.
     */
    @Test
    fun `cancellation still cancels and is never counted as a failure`() =
        runTest {
            val recorder = UncaughtRecorder()
            val stalling =
                object : ArtWarmer {
                    override suspend fun isCached(request: CardArtRequest): Boolean = false

                    override suspend fun warm(request: CardArtRequest): Boolean = awaitCancellation()
                }
            val manager = newManager(requests(8), stalling, recorder)

            manager.start(PrefetchScope.All, setOf(CardArtSize.SMALL))
            advanceUntilIdle()
            assertEquals(PrefetchStatus.RUNNING, manager.progress.value.status)

            manager.cancel()
            advanceUntilIdle()

            val progress = manager.progress.value
            assertEquals(PrefetchStatus.CANCELLED, progress.status)
            assertTrue(progress.isTerminal)
            assertEquals("cancellation is not a per-target failure", 0, progress.failed)
            assertEquals(0, progress.warmed)
            assertTrue("cancellation must not reach the app scope's handler", recorder.thrown.isEmpty())
        }
}
