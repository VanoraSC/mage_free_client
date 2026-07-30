package magefree.network.reconnect

import kotlin.math.pow
import kotlin.random.Random

/**
 * Bounded exponential back-off with jitter for an unexpectedly-dropped socket (story 0024).
 *
 * Replaces story 0016's fixed `(3 attempts, 1s)` policy: the delay before reconnect attempt _n_ grows
 * geometrically ([initialDelayMillis] × [multiplier]^(n-1)), is capped at [maxDelayMillis], and is
 * spread by ±[jitterRatio] so a fleet of clients recovering from the same outage does not stampede the
 * bridge. [maxAttempts] is optional (`null` = keep trying): the reconnect loop instead stops on a
 * terminal outcome (`AuthFailed`, `VersionUnsupported`, explicit disconnect), and a connectivity/
 * foreground return cuts a waiting back-off short (see `ReconnectingSession`).
 */
data class ReconnectPolicy(
    val initialDelayMillis: Long = 1_000L,
    val multiplier: Double = 2.0,
    val maxDelayMillis: Long = 30_000L,
    val jitterRatio: Double = 0.2,
    val maxAttempts: Int? = null,
) {
    init {
        require(initialDelayMillis > 0) { "initialDelayMillis must be > 0" }
        require(multiplier >= 1.0) { "multiplier must be >= 1.0" }
        require(maxDelayMillis >= initialDelayMillis) { "maxDelayMillis must be >= initialDelayMillis" }
        require(jitterRatio in 0.0..1.0) { "jitterRatio must be in 0.0..1.0" }
        require(maxAttempts == null || maxAttempts > 0) { "maxAttempts must be null or > 0" }
    }

    /**
     * The back-off delay (ms) before reconnect [attempt] (1-based). The geometric base is capped at
     * [maxDelayMillis], then spread by ±[jitterRatio] using [random] (a `0.0..1.0` source, injectable
     * so tests are deterministic); the result never exceeds [maxDelayMillis] and is never negative.
     */
    fun delayForAttempt(
        attempt: Int,
        random: () -> Double = { Random.nextDouble() },
    ): Long {
        require(attempt >= 1) { "attempt must be >= 1" }
        val base = (initialDelayMillis.toDouble() * multiplier.pow(attempt - 1)).coerceAtMost(maxDelayMillis.toDouble())
        val jitter = base * jitterRatio * (random() * 2.0 - 1.0)
        return (base + jitter).toLong().coerceIn(0L, maxDelayMillis)
    }
}
