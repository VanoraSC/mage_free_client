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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
 * - **Bounded concurrency** ([maxConcurrency]) with a small [politeDelayMs] after each real fetch, to
 *   stay polite to the image source.
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

        try {
            coroutineScope {
                val semaphore = Semaphore(maxConcurrency.coerceAtLeast(1))
                targets.forEach { request ->
                    launch {
                        semaphore.withPermit {
                            ensureActive()
                            if (warmer.isCached(request)) {
                                skipped.incrementAndGet()
                            } else {
                                val ok = warmer.warm(request)
                                if (ok) warmed.incrementAndGet() else failed.incrementAndGet()
                                if (politeDelayMs > 0) delay(politeDelayMs)
                            }
                            publish(request, warmed, skipped, failed, PrefetchStatus.RUNNING)
                        }
                    }
                }
            }
        } catch (cancellation: CancellationException) {
            publishCancelled(warmed, skipped, failed)
            throw cancellation
        }

        publish(current = null, warmed, skipped, failed, PrefetchStatus.COMPLETED)
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
