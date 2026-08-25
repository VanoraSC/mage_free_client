package magefree.cards.art

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

/**
 * The bulk pre-download state machine (progress / cancel / resume) driven by fakes — no Coil, no
 * network. The manager runs on an [UnconfinedTestDispatcher] tied to the test scheduler (the repo's
 * pattern for coroutine components), so its bounded-concurrency workers execute deterministically.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ArtDownloadManagerTest {
    private fun requests(count: Int): List<CardArtRequest> = (1..count).map { CardArtRequest("SET", it.toString()) }

    private fun TestScope.newManager(
        targetSource: PrefetchTargetSource,
        warmer: ArtWarmer,
        maxConcurrency: Int = 4,
    ): ArtDownloadManager =
        ArtDownloadManager(
            targetSource = targetSource,
            warmer = warmer,
            appScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
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

    private class FailingTargets : PrefetchTargetSource {
        override suspend fun requests(
            scope: PrefetchScope,
            size: CardArtSize,
        ): List<CardArtRequest> = throw IllegalStateException("catalog boom")
    }

    /** Records warmed requests; treats pre-seeded [cached] as already present; can fail a chosen set. */
    private class FakeArtWarmer(
        cached: Collection<CardArtRequest> = emptyList(),
        private val failFor: Set<CardArtRequest> = emptySet(),
    ) : ArtWarmer {
        val cached: MutableSet<CardArtRequest> = Collections.synchronizedSet(HashSet(cached))
        val warmCalls: MutableList<CardArtRequest> = Collections.synchronizedList(ArrayList())

        override suspend fun isCached(request: CardArtRequest): Boolean = cached.contains(request)

        override suspend fun warm(request: CardArtRequest): Boolean {
            warmCalls.add(request)
            if (request in failFor) return false
            cached.add(request)
            return true
        }
    }

    @Test
    fun `warms every target at every displayed size and completes`() =
        runTest {
            val warmer = FakeArtWarmer()
            val manager = newManager(FixedTargets(requests(10)), warmer)

            // Default sizes: the run covers every size the UI displays, so the totals are per-size
            // targets x sizes — not one size's worth (story 0043, defect A).
            manager.start(PrefetchScope.All)
            advanceUntilIdle()

            val expected = 10 * PREFETCH_SIZES.size
            val progress = manager.progress.value
            assertEquals(PrefetchStatus.COMPLETED, progress.status)
            assertEquals(expected, progress.total)
            assertEquals(expected, progress.warmed)
            assertEquals(0, progress.skipped)
            assertEquals(0, progress.failed)
            assertEquals(expected, progress.done)
            assertEquals(1f, progress.fraction)
            assertEquals(expected, warmer.warmCalls.size)
            assertEquals(PREFETCH_SIZES, warmer.warmCalls.map { it.size }.toSet())
        }

    @Test
    fun `already-cached targets are skipped so a run resumes`() =
        runTest {
            val all = requests(10)
            val alreadyDone = all.take(6)
            val warmer = FakeArtWarmer(cached = alreadyDone)
            val manager = newManager(FixedTargets(all), warmer)

            // requests() default to SMALL; pre-seeded `cached` must match the size the run warms.
            manager.start(PrefetchScope.All, setOf(CardArtSize.SMALL))
            advanceUntilIdle()

            val progress = manager.progress.value
            assertEquals(PrefetchStatus.COMPLETED, progress.status)
            assertEquals(6, progress.skipped)
            assertEquals(4, progress.warmed)
            assertEquals(10, progress.done)
            // Only the not-yet-cached ones were actually fetched (resume did not refetch).
            assertEquals(4, warmer.warmCalls.size)
        }

    @Test
    fun `failed warms are counted but the run still completes`() =
        runTest {
            val all = requests(5)
            val warmer = FakeArtWarmer(failFor = setOf(all[1], all[3]))
            val manager = newManager(FixedTargets(all), warmer)

            // requests() default to SMALL; `failFor` must match the size the run warms.
            manager.start(PrefetchScope.All, setOf(CardArtSize.SMALL))
            advanceUntilIdle()

            val progress = manager.progress.value
            assertEquals(PrefetchStatus.COMPLETED, progress.status)
            assertEquals(3, progress.warmed)
            assertEquals(2, progress.failed)
            assertEquals(5, progress.done)
        }

    @Test
    fun `cancel moves a running prefetch to CANCELLED`() =
        runTest {
            // A warmer that parks forever, so the run stays RUNNING until cancelled.
            val stalling =
                object : ArtWarmer {
                    override suspend fun isCached(request: CardArtRequest): Boolean = false

                    override suspend fun warm(request: CardArtRequest): Boolean {
                        awaitCancellation()
                    }
                }
            val manager = newManager(FixedTargets(requests(8)), stalling)

            // One size: this is the cancel state machine, not size coverage.
            manager.start(PrefetchScope.All, setOf(CardArtSize.SMALL))
            advanceUntilIdle()
            assertEquals(PrefetchStatus.RUNNING, manager.progress.value.status)
            assertEquals(8, manager.progress.value.total)

            manager.cancel()
            advanceUntilIdle()

            assertEquals(PrefetchStatus.CANCELLED, manager.progress.value.status)
        }

    @Test
    fun `enumeration failure surfaces as FAILED`() =
        runTest {
            val manager = newManager(FailingTargets(), FakeArtWarmer())

            manager.start(PrefetchScope.All)
            advanceUntilIdle()

            val progress = manager.progress.value
            assertEquals(PrefetchStatus.FAILED, progress.status)
            assertTrue(progress.error?.contains("boom") == true)
        }

    @Test
    fun `start is a no-op while a run is active`() =
        runTest {
            // Park so the first run stays active while we call start again.
            val warmed = Collections.synchronizedList(ArrayList<CardArtRequest>())
            val gated =
                object : ArtWarmer {
                    override suspend fun isCached(request: CardArtRequest): Boolean = false

                    override suspend fun warm(request: CardArtRequest): Boolean {
                        warmed.add(request)
                        awaitCancellation()
                    }
                }
            val manager = newManager(FixedTargets(requests(3)), gated, maxConcurrency = 3)

            manager.start(PrefetchScope.All)
            advanceUntilIdle()
            manager.start(PrefetchScope.Set("XLN")) // ignored: already active
            advanceUntilIdle()

            // The second start did not enqueue a fresh run over the same targets.
            assertEquals(PrefetchStatus.RUNNING, manager.progress.value.status)
            assertEquals(3, warmed.size)
        }
}
