package magefree.designsystem.card

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import magefree.designsystem.board.perceptualLightness

/*
 * Colour for counters.
 *
 * Counter kinds are an open set upstream — hundreds of them, and a new set can add one — so a fixed
 * table cannot cover them. What a player actually needs is narrower than global consistency: on any
 * one board there are rarely several kinds on a single card, but there are often many cards each
 * carrying a different kind. The colour therefore has one job, which is to say *this is a different
 * kind from that one*. The number carries the precision, and the inspect view carries the name.
 *
 * So three kinds that mean something get fixed colours, and everything else is allocated from a queue
 * on first sight and then held for the rest of the game.
 */

/** The colours reserved for counter kinds where the colour itself carries meaning. */
private val FixedCounterColors: Map<String, Color> =
    mapOf(
        "+1/+1" to Color(0xFF4ADE80),
        "-1/-1" to Color(0xFFEF4444),
        "loyalty" to Color(0xFFF5D67B),
    )

/**
 * The queue every other kind draws from, in order.
 *
 * Chosen to be tellable apart at circle size and to avoid the three fixed colours above, so a queue
 * colour is never mistaken for "this is a +1/+1". They are free to resemble the board's signal
 * colours: a counter is a filled ring on the card face and a signal is the card's border, which are
 * different enough channels that colour does not have to carry the distinction as well.
 */
private val CounterColorQueue: List<Color> =
    listOf(
        Color(0xFF38BDF8),
        Color(0xFFC084FC),
        Color(0xFFFB923C),
        Color(0xFF2DD4BF),
        Color(0xFFF472B6),
        Color(0xFFA3E635),
        Color(0xFF818CF8),
        Color(0xFFFBBF24),
        Color(0xFF94A3B8),
        Color(0xFF7DD3FC),
    )

/**
 * Hands out a colour per counter kind, and keeps handing out the same one.
 *
 * Allocation is by **first sight**: the first unfamiliar kind takes the head of the queue, the next
 * takes the one after it, and each is remembered. That makes the colours stable for as long as this
 * palette lives, which is the life of the board — a counter kind never changes colour mid-game, which
 * is the only stability property a player can actually notice.
 *
 * When the queue is exhausted it wraps. Two kinds sharing a colour is a real, accepted outcome: it
 * takes eleven distinct kinds in one game to reach it, and the number and the inspect view still tell
 * them apart.
 */
@Stable
class CounterPalette {
    private val assigned = LinkedHashMap<String, Color>()

    /** The colour for [counterName], allocating one from the queue if this kind is new. */
    fun colorFor(counterName: String): Color {
        FixedCounterColors[counterName]?.let { return it }
        return assigned.getOrPut(counterName) {
            CounterColorQueue[assigned.size % CounterColorQueue.size]
        }
    }

    /** How many kinds have been allocated from the queue. Exposed so the allocation order is testable. */
    val allocatedCount: Int
        get() = assigned.size
}

/** A [CounterPalette] that lives as long as the board it is remembered in. */
@Composable
fun rememberCounterPalette(): CounterPalette = remember { CounterPalette() }

/**
 * Black or white for a digit sitting on [fill], whichever the eye reads more easily.
 *
 * The counter's ring makes the circle stand out from whatever is behind it, but nothing protects the
 * number from its own fill — a queue colour could otherwise allocate a pale fill under white text.
 * Choosing by lightness means every colour in the queue is safe by construction rather than by having
 * been checked.
 */
fun counterDigitColor(fill: Color): Color = if (fill.perceptualLightness() > DIGIT_FLIP_LIGHTNESS) Color.Black else Color.White

/** The CIE L* above which a fill is light enough to need a dark digit. */
private const val DIGIT_FLIP_LIGHTNESS = 60.0
