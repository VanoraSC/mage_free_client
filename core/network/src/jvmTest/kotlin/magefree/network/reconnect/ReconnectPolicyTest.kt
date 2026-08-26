package magefree.network.reconnect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure unit coverage for the  back-off shape: geometric growth, the max-delay cap, the
 * bounded jitter band, and the constructor invariants. No coroutines — [ReconnectPolicy.delayForAttempt]
 * is deterministic given an injected random source.
 */
class ReconnectPolicyTest {
    private val policy =
        ReconnectPolicy(
            initialDelayMillis = 1_000,
            multiplier = 2.0,
            maxDelayMillis = 30_000,
            jitterRatio = 0.2,
        )

    @Test
    fun growsGeometricallyThenCapsAtMaxDelay() {
        // random = 0.5 → jitter term is exactly 0, exposing the geometric base.
        val noJitter = { 0.5 }
        assertEquals(1_000, policy.delayForAttempt(1, noJitter))
        assertEquals(2_000, policy.delayForAttempt(2, noJitter))
        assertEquals(4_000, policy.delayForAttempt(3, noJitter))
        assertEquals(8_000, policy.delayForAttempt(4, noJitter))
        assertEquals(16_000, policy.delayForAttempt(5, noJitter))
        // 32_000 would exceed the cap → clamped to maxDelayMillis, and stays there.
        assertEquals(30_000, policy.delayForAttempt(6, noJitter))
        assertEquals(30_000, policy.delayForAttempt(10, noJitter))
    }

    @Test
    fun jitterStaysWithinTheConfiguredBandAndNeverExceedsTheCap() {
        // attempt 3 base = 4_000, ±20% → [3_200, 4_800].
        assertEquals(3_200, policy.delayForAttempt(3, random = { 0.0 }))
        assertEquals(4_800, policy.delayForAttempt(3, random = { 1.0 }))

        // At the cap, positive jitter is clamped so the delay never exceeds maxDelayMillis.
        assertEquals(30_000, policy.delayForAttempt(20, random = { 1.0 }))
        assertTrue(policy.delayForAttempt(20, random = { 0.0 }) <= 30_000)
    }

    @Test
    fun everyDelayIsNonNegativeAndBoundedAcrossManyAttempts() {
        for (attempt in 1..50) {
            for (r in listOf(0.0, 0.25, 0.5, 0.75, 1.0)) {
                val delay = policy.delayForAttempt(attempt, random = { r })
                assertTrue("delay must be >= 0", delay >= 0)
                assertTrue("delay must be <= maxDelayMillis", delay <= 30_000)
            }
        }
    }

    @Test
    fun rejectsInvalidConfiguration() {
        assertThrows(IllegalArgumentException::class.java) { ReconnectPolicy(initialDelayMillis = 0) }
        assertThrows(IllegalArgumentException::class.java) { ReconnectPolicy(multiplier = 0.5) }
        assertThrows(IllegalArgumentException::class.java) {
            ReconnectPolicy(initialDelayMillis = 5_000, maxDelayMillis = 1_000)
        }
        assertThrows(IllegalArgumentException::class.java) { ReconnectPolicy(jitterRatio = 1.5) }
        assertThrows(IllegalArgumentException::class.java) { ReconnectPolicy(maxAttempts = 0) }
    }
}
