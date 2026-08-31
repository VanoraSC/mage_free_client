package magefree.designsystem.board

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import kotlin.math.roundToInt

/*
 * The board's motion tokens.
 *
 * An animation on the board exists because a game action happened — it is how the player finds out
 * what the game did, not decoration over a state change. That makes a duration a piece of meaning:
 * a card crossing zones is a bigger event than a permanent tapping, and it takes longer on screen
 * because it is bigger, not because it looks nicer that way.
 *
 * Durations are therefore named for the event, and every one of them passes through [MotionScale] on
 * its way to an animation. That single multiplication point is what lets a reduce-motion preference
 * **shorten** every animation at once — never remove one, because removing the animation removes the
 * information it was carrying.
 */

/** Durations in milliseconds, named for the event they describe. */
object BoardDuration {
    /** A card moving between zones — the largest event the board shows. */
    const val ZONE_MOVE: Int = 250

    /** A permanent tapping or untapping. */
    const val TAP: Int = 150

    /** A counter appearing, changing or leaving. The smallest change that still gets its turn. */
    const val COUNTER: Int = 120

    /** How long the object currently resolving is held under the spotlight before the board moves on. */
    const val SPOTLIGHT_HOLD: Int = 400

    /** Every duration, for the checks that must hold across all of them and for the catalog. */
    val all: List<Int> = listOf(ZONE_MOVE, TAP, COUNTER, SPOTLIGHT_HOLD)
}

/** Easings, named for the shape of the event rather than for the curve. */
object BoardEasing {
    /** A move that starts and ends on the board: a card travelling from one zone to another. */
    val move: Easing = FastOutSlowInEasing

    /** Something arriving on the board, decelerating into its place. */
    val enter: Easing = LinearOutSlowInEasing

    /** Something leaving the board: it goes quickly, because it is already gone in the game. */
    val exit: Easing = CubicBezierEasing(0.4f, 0.0f, 1.0f, 1.0f)

    /** A state change in place — tapping, a counter ticking. Symmetrical, because nothing travels. */
    val inPlace: Easing = CubicBezierEasing(0.4f, 0.0f, 0.2f, 1.0f)
}

/**
 * How much of its full duration an animation gets, as a multiplier on every [BoardDuration].
 *
 * The factor is clamped to [MINIMUM_FACTOR] at the low end, which is the whole point of the type: a
 * reduce-motion preference makes the board quicker, and there is no value it can take that makes an
 * animation not happen. A player who has asked for less motion still needs to see that the card came
 * from the graveyard.
 */
@Immutable
@JvmInline
value class MotionScale private constructor(
    val factor: Float,
) {
    /**
     * [durationMillis] shortened by this scale, never below [MINIMUM_MILLIS] and never longer than it
     * started.
     */
    fun scale(durationMillis: Int): Int = (durationMillis * factor).roundToInt().coerceIn(MINIMUM_MILLIS, durationMillis)

    companion object {
        /** The shortest an animation is ever allowed to run. */
        const val MINIMUM_MILLIS: Int = 1

        /** The smallest factor [of] will produce, whatever it is handed. */
        const val MINIMUM_FACTOR: Float = 0.25f

        /** Full duration: the default, and what the board runs at unless something asks otherwise. */
        val Full: MotionScale = MotionScale(1f)

        /** What a reduce-motion preference selects: the same animations, noticeably quicker. */
        val Reduced: MotionScale = MotionScale(0.4f)

        /** A scale from an arbitrary factor, clamped into [MINIMUM_FACTOR]..1. */
        fun of(factor: Float): MotionScale = MotionScale(if (factor.isNaN()) 1f else factor.coerceIn(MINIMUM_FACTOR, 1f))
    }
}

/**
 * The scale in force for the board beneath this point in the composition.
 *
 * Static, because it changes only when a preference changes: every board animation reads it, and a
 * non-static local would invalidate all of them on any recomposition that touched it.
 */
val LocalMotionScale = staticCompositionLocalOf { MotionScale.Full }
