package magefree.designsystem.board

import androidx.compose.runtime.Immutable

/*
 * What the animation host animates: objects that have an identity, and a slot each of them is in.
 *
 * The board's own state model lives elsewhere and is much larger than this. What the host needs is
 * only the part that answers "is this the same object as last time, and has it moved" — an id, the
 * slot it occupies, and the handful of in-place facts that are themselves events (a permanent taps,
 * a counter ticks, a card transforms). Everything else about a card is the caller's business and is
 * carried through untouched in [BoardObject.payload].
 *
 * Deliberately free of Compose and of Android: this is the half of the subsystem that decides *what*
 * happens and *in what order*, and it is asserted on a virtual clock with no pixels involved.
 */

/**
 * The stable identity of a renderable object, as it already crosses the wire.
 *
 * Identity is something to **use**, not to invent: every card and permanent upstream carries an id,
 * and it is that id which makes a card leaving the hand and arriving on the battlefield one object
 * that moved rather than two objects, one of which vanished.
 */
@JvmInline
value class BoardObjectId(
    val value: String,
)

/**
 * The identity of a layout slot — a place on the board an object can own.
 *
 * The host does not know where a slot *is*; that is the board layout's business, and it is measured
 * at runtime. A slot id is only a name the layout and a snapshot can agree on.
 */
@JvmInline
value class BoardSlotId(
    val value: String,
)

/**
 * One object in one snapshot: who it is, where it is, and the in-place facts that are events.
 *
 * @property id the stable identity. Two snapshots that share an id are describing the same object.
 * @property slot the single slot this object owns in this snapshot. One, never several: an object in
 *   two places is a rendering the host cannot animate, because there is no "from" and no "to".
 * @property tapped whether the permanent is tapped. An event in its own right, on its own duration.
 * @property counters the counters on the object, by kind. A change of value is an event; the host
 *   cares about the change, and the caller cares about how it is drawn.
 * @property face which face of the object is showing. A card that transforms keeps its id and changes
 *   its face — the case that is got wrong first if identity is keyed off appearance instead.
 * @property payload whatever the caller needs to draw this object. Opaque here, and compared by
 *   equality only so a caller that rebuilds an equal payload does not read as a change.
 */
@Immutable
data class BoardObject(
    val id: BoardObjectId,
    val slot: BoardSlotId,
    val tapped: Boolean = false,
    val counters: Map<String, Int> = emptyMap(),
    val face: String = DEFAULT_FACE,
    val payload: Any? = null,
) {
    companion object {
        /** The face an object shows unless it says otherwise — the front of a card that has one face. */
        const val DEFAULT_FACE: String = "front"
    }
}

/**
 * The whole board at one instant, as the host sees it.
 *
 * A snapshot replaces its predecessor rather than merging with it, which is the same rule the game
 * state itself follows: an object absent from a snapshot is gone, not stale-but-remembered.
 *
 * @property objects every renderable object, each owning exactly one slot.
 */
@Immutable
data class BoardSnapshot(
    val objects: List<BoardObject> = emptyList(),
) {
    /** The objects by id, which is how every comparison in this subsystem reads them. */
    val byId: Map<BoardObjectId, BoardObject> = objects.associateBy { it.id }

    /** The objects owning [slot], in snapshot order — what one slot renders. */
    fun inSlot(slot: BoardSlotId): List<BoardObject> = objects.filter { it.slot == slot }

    /** This snapshot with [object]'s place taken by [replacement], or with it removed when null. */
    internal fun with(
        id: BoardObjectId,
        replacement: BoardObject?,
    ): BoardSnapshot {
        val index = objects.indexOfFirst { it.id == id }
        return when {
            index < 0 && replacement != null -> BoardSnapshot(objects + replacement)
            index < 0 -> this
            replacement == null -> BoardSnapshot(objects.filterIndexed { at, _ -> at != index })
            else -> BoardSnapshot(objects.toMutableList().also { it[index] = replacement })
        }
    }
}
