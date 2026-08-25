package magefree.cards.concurrent

/**
 * An integer counter safe to increment from several coroutines at once — the art warm-up's tallies
 * and its work-distribution cursor (story 0084).
 *
 * The `actual` is a typealias to `java.util.concurrent.atomic.AtomicInteger` on every current
 * target, so this is the same class the pre-port code named directly; what changes is that
 * `commonMain` no longer references `java.*`. See `magefree.network.concurrent` for why an
 * `expect`/`actual` rather than `kotlin.concurrent.atomics` (still experimental) or a third-party
 * concurrency library.
 */
internal expect class AtomicCounter() {
    fun get(): Int

    fun incrementAndGet(): Int

    fun getAndIncrement(): Int
}
