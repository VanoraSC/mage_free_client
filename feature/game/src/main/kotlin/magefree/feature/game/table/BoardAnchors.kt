package magefree.feature.game.table

import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned

/*
 * Where each object is, on the board, right now.
 *
 * **Measured, never derived.** An arrow from a spell on the stack to the creature it targets needs two
 * points, and the board knows neither of them as a number: a card's position is the outcome of the
 * status rail's width, the land column's, the centre shift a row applies to itself, and whatever
 * scroll offset a busy row has taken. Re-deriving that in a second place to draw a line would be
 * writing the layout twice and watching the copies drift.
 *
 * So every card that can be an arrow's end reports its own box as it is placed, in the board's own
 * coordinate space, and the arrows are one overlay drawn from what was reported.
 *
 * **An id that is not here has no arrow, and that is an answer rather than a gap.** A spell can target
 * a card in a graveyard, or a player. Pointing at where that card would be if it were on the board is
 * worse than pointing at nothing; the detail view names every target in full, which is what the ones
 * the board cannot show are for.
 */

/**
 * The board's map of object id to where it is drawn.
 *
 * Held for the lifetime of one board and written during layout, so a card that moves — a creature
 * arriving, a row re-flowing, a land tapping — updates its own entry with no bookkeeping anywhere
 * else. Entries for objects that have left are stale until something re-lays out; the arrows only ever
 * read ids the *current* snapshot named, so a stale entry is never drawn from.
 */
@Stable
class BoardAnchors {
    /** The board's own coordinates, which every reported box is measured against. */
    private var root: LayoutCoordinates? by mutableStateOf(null)

    private val boxes = mutableStateMapOf<String, Rect>()

    /** Marks the composable whose coordinate space the anchors are expressed in. */
    fun rootModifier(): Modifier = Modifier.onGloballyPositioned { root = it }

    /**
     * Reports [id]'s box as it is placed.
     *
     * Nothing is written until the root has been placed too — the first pass can position a child
     * before its ancestor — and a box is only rewritten when it has actually moved, so a card that
     * re-lays out at the same place does not invalidate the overlay.
     */
    fun anchorModifier(id: String): Modifier =
        Modifier.onGloballyPositioned { coordinates ->
            val space = root ?: return@onGloballyPositioned
            if (!coordinates.isAttached) return@onGloballyPositioned
            val box = space.localBoundingBoxOf(coordinates, clipBounds = false)
            if (boxes[id] != box) boxes[id] = box
        }

    /** Where [id] is drawn, or `null` for an object the board is not drawing. */
    fun boxOf(id: String): Rect? = boxes[id]

    /**
     * Records [box] for [id] without a layout pass, for a test that is about what the anchors are
     * *used for* rather than about the measuring.
     *
     * The measuring itself is proven by the arrows and the flights on a real board; a test that had to
     * lay out a whole battlefield to assert which card flies where would be testing Compose.
     */
    @VisibleForTesting
    internal fun placeForTest(
        id: String,
        box: Rect,
    ) {
        boxes[id] = box
    }
}

/** One [BoardAnchors] per board, remembered across recompositions. */
@Composable
fun rememberBoardAnchors(): BoardAnchors = remember { BoardAnchors() }
