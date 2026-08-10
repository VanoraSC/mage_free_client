package magefree.cards.art

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger

/**
 * User-initiated **bulk pre-download** that warms the disk cache for full offline art (story 0031).
 * It is opt-in — nothing runs until [start] is called — and never automatic.
 *
 * Design:
 * - Enumerates targets for the chosen [PrefetchScope] from [PrefetchTargetSource], then warms each via
 *   the [ArtWarmer] (the [CardImageLoader]), honoring the active cache policy (disk writes happen only
 *   under [CardArtCachePolicy.PERSISTENT]).
 * - **Resumable:** every target is checked with [ArtWarmer.isCached] first and skipped if present, so
 *   re-running after a cancel picks up where it left off without re-fetching.
 * - **Cancellable:** [cancel] cancels the run; already-warmed art stays cached.
 * - **Bounded concurrency** ([maxConcurrency]) — a fixed worker pool over the target list — with a
 *   small [politeDelayMs] after each real fetch, to stay polite to the image source.
 * - **Fail-soft:** a target that throws is counted in [PrefetchProgress.failed] and skipped, never
 *   fatal. Nothing escapes into [appScope] (a `SupervisorJob` with no `CoroutineExceptionHandler`,
 *   where an escaping throw would crash the process), and every run ends in a terminal status —
 *   COMPLETED (possibly with failures), CANCELLED, or FAILED — so the UI never shows a frozen bar.
 * - Exposes progress as a [StateFlow] ([progress]).
 */
class ArtDownloadManager(
    private val targetSource: PrefetchTargetSource,
    private val warmer: ArtWarmer,
    private val appScope: CoroutineScope,
    private val maxConcurrency: Int = DEFAULT_CONCURRENCY,
    private val politeDelayMs: Long = DEFAULT_POLITE_DELAY_MS,
) {
    private val _progress = MutableStateFlow(PrefetchProgress())
    val progress: StateFlow<PrefetchProgress> = _progress.asStateFlow()

    @Volatile
    private var job: Job? = null

    /**
     * Start warming the cache for [scope] at every size in [sizes]. A no-op if a run is already active
     * (call [cancel] first). Returns immediately; observe [progress] for updates.
     *
     * The default, [PREFETCH_SIZES], is every size the UI displays — warming a subset would leave the
     * surfaces that display the other sizes blank offline (story 0043, defect A). Callers should not
     * narrow it; the parameter exists so tests can pin a single size.
     */
    fun start(
        scope: PrefetchScope,
        sizes: Set<CardArtSize> = PREFETCH_SIZES,
    ) {
        if (job?.isActive == true) return
        job = appScope.launch { run(scope, sizes) }
    }

    /** Cancel the active run, if any. Warmed art is retained; a later [start] resumes via skip. */
    fun cancel() {
        job?.cancel()
    }

    private suspend fun run(
        scope: PrefetchScope,
        sizes: Set<CardArtSize>,
    ) {
        _progress.value = PrefetchProgress(status = PrefetchStatus.RUNNING)

        val targets =
            try {
                // One enumeration per requested size, unioned: the target *set* is per-size because a
                // request's size is part of its cache identity. `total` therefore counts real targets.
                sizes.flatMap { targetSource.requests(scope, it) }.distinct()
            } catch (cancellation: CancellationException) {
                publishCancelled()
                throw cancellation
            } catch (error: Exception) {
                _progress.update { it.copy(status = PrefetchStatus.FAILED, error = error.message ?: error.toString()) }
                return
            }

        _progress.update { it.copy(total = targets.size) }
        if (targets.isEmpty()) {
            _progress.update { it.copy(status = PrefetchStatus.COMPLETED) }
            return
        }

        val warmed = AtomicInteger()
        val skipped = AtomicInteger()
        val failed = AtomicInteger()

        // A fixed worker pool pulling from a shared cursor, rather than one coroutine per target
        // gated by a semaphore: PrefetchScope.All would otherwise launch ~32k coroutines (x the
        // displayed sizes) that all park before acquiring a permit. Same bounded concurrency, a
        // handful of coroutines.
        val cursor = AtomicInteger()
        try {
            coroutineScope {
                repeat(maxConcurrency.coerceAtLeast(1).coerceAtMost(targets.size)) {
                    launch {
                        while (true) {
                            val index = cursor.getAndIncrement()
                            if (index >= targets.size) break
                            ensureActive()
                            warmOne(targets[index], warmed, skipped, failed)
                        }
                    }
                }
            }
        } catch (cancellation: CancellationException) {
            publishCancelled(warmed, skipped, failed)
            throw cancellation
        } catch (error: Exception) {
            // Belt and braces. Per-target failures are already contained in warmOne, so reaching
            // here means something structural — but this runs in an appScope whose SupervisorJob has
            // no CoroutineExceptionHandler, so an escaping throw is an uncaught exception and a
            // process crash, with progress frozen at RUNNING (story 0043, defect C). Land on a
            // terminal status instead.
            publish(current = null, warmed, skipped, failed, PrefetchStatus.FAILED)
            _progress.update { it.copy(error = error.message ?: error.toString()) }
            return
        }

        // Terminal: COMPLETED. `failed > 0` makes it a partial run — every target was attempted, some
        // could not be warmed, and a re-run resumes over the rest (already-warmed targets are skipped).
        publish(current = null, warmed, skipped, failed, PrefetchStatus.COMPLETED)
    }

    /**
     * Warm one target, absorbing its failure into [failed]. Over tens of thousands of network targets a
     * per-item failure is normal, not exceptional: a corrupt disk-cache entry makes
     * [ArtWarmer.isCached] throw `IOException`, and without this the throw cancels every sibling and
     * takes the whole run — and the process — with it.
     *
     * [CancellationException] is deliberately rethrown ahead of the general catch: in Kotlin it *is* an
     * `Exception`, so absorbing it would silently turn user cancellation into "failed targets" and let
     * a cancelled run report COMPLETED.
     */
    private suspend fun warmOne(
        request: CardArtRequest,
        warmed: AtomicInteger,
        skipped: AtomicInteger,
        failed: AtomicInteger,
    ) {
        try {
            if (warmer.isCached(request)) {
                skipped.incrementAndGet()
            } else {
                val ok = warmer.warm(request)
                if (ok) warmed.incrementAndGet() else failed.incrementAndGet()
                if (politeDelayMs > 0) delay(politeDelayMs)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            failed.incrementAndGet()
        }
        publish(request, warmed, skipped, failed, PrefetchStatus.RUNNING)
    }

    private fun publish(
        request: CardArtRequest?,
        warmed: AtomicInteger,
        skipped: AtomicInteger,
        failed: AtomicInteger,
        status: PrefetchStatus,
    ) = publish(request?.let { "${it.setCode} #${it.collectorNumber}" }, warmed, skipped, failed, status)

    private fun publish(
        current: String?,
        warmed: AtomicInteger,
        skipped: AtomicInteger,
        failed: AtomicInteger,
        status: PrefetchStatus,
    ) {
        _progress.update {
            it.copy(
                status = status,
                warmed = warmed.get(),
                skipped = skipped.get(),
                failed = failed.get(),
                current = if (status == PrefetchStatus.RUNNING) current else null,
            )
        }
    }

    /** Emit the terminal CANCELLED snapshot even though the coroutine is being cancelled. */
    private suspend fun publishCancelled(
        warmed: AtomicInteger = AtomicInteger(),
        skipped: AtomicInteger = AtomicInteger(),
        failed: AtomicInteger = AtomicInteger(),
    ) {
        withContext(NonCancellable) {
            publish(current = null, warmed, skipped, failed, PrefetchStatus.CANCELLED)
        }
    }

    private companion object {
        const val DEFAULT_CONCURRENCY = 4
        const val DEFAULT_POLITE_DELAY_MS = 100L
    }
}
