package magefree.designsystem.board

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one property the reduce-motion path turns on: it **shortens**.
 *
 * An animation on the board is how the player learns what the game did, so a preference that removed
 * one would remove the information with it. Every assertion here is a way of saying that no factor,
 * however extreme, and no duration, however small, can produce an animation that does not happen.
 */
class MotionScaleTest {
    @Test
    fun `the full scale leaves every duration exactly as specified`() {
        BoardDuration.all.forEach { duration ->
            assertEquals(duration, MotionScale.Full.scale(duration))
        }
    }

    @Test
    fun `a reduced scale shortens every duration`() {
        BoardDuration.all.forEach { duration ->
            assertTrue(
                "the reduced scale left $duration ms unchanged",
                MotionScale.Reduced.scale(duration) < duration,
            )
        }
    }

    @Test
    fun `no factor produces an animation that does not happen`() {
        val factors = listOf(-100f, -1f, 0f, 0.001f, 0.1f, 0.25f, 0.5f, 1f, 2f, Float.NaN)
        factors.forEach { factor ->
            BoardDuration.all.forEach { duration ->
                val scaled = MotionScale.of(factor).scale(duration)
                assertTrue(
                    "factor $factor reduced $duration ms to $scaled — an animation the player " +
                        "cannot see is an animation that was removed",
                    scaled > 0,
                )
            }
        }
    }

    @Test
    fun `no factor stretches a duration past what it was specified as`() {
        listOf(1f, 2f, 1000f).forEach { factor ->
            BoardDuration.all.forEach { duration ->
                assertEquals(
                    "factor $factor must not lengthen $duration ms — the durations are the meaning",
                    duration,
                    MotionScale.of(factor).scale(duration),
                )
            }
        }
    }

    @Test
    fun `even the shortest conceivable duration survives the smallest scale`() {
        assertTrue(MotionScale.of(0f).scale(1) > 0)
    }

    @Test
    fun `the durations stay ordered under every scale, because their order is the meaning`() {
        // A zone move is a bigger event than a tap, which is bigger than a counter ticking. Scaling
        // must not flatten that into three animations of the same length.
        listOf(MotionScale.Full, MotionScale.Reduced, MotionScale.of(0f)).forEach { scale ->
            assertTrue(
                "$scale collapsed the ordering of the durations",
                scale.scale(BoardDuration.ZONE_MOVE) > scale.scale(BoardDuration.TAP) &&
                    scale.scale(BoardDuration.TAP) > scale.scale(BoardDuration.COUNTER),
            )
        }
    }

    @Test
    fun `an out-of-range factor is clamped rather than trusted`() {
        assertEquals(MotionScale.MINIMUM_FACTOR, MotionScale.of(-5f).factor)
        assertEquals(MotionScale.MINIMUM_FACTOR, MotionScale.of(0f).factor)
        assertEquals(1f, MotionScale.of(9f).factor)
        assertEquals(1f, MotionScale.of(Float.NaN).factor)
    }
}
