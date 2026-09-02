package magefree.designsystem.board

import androidx.compose.runtime.Immutable

/*
 * The events the board shows, and when each of them gets the screen.
 *
 * A change here is a game action the player has to be told about, so every one of them carries the
 * duration it is shown for. The durations come from 0095 and are named for the event rather than for
 * how they look, which is what makes "a card crossing zones is a bigger event than a permanent
 * tapping" a fact about the type instead of a fact about a magic number.
 */

/**
 * One thing that happened between two snapshots.
 *
 * Every case names the object it happened to, because the host addresses objects by id and never by
 * position: a move is one object changing slot, not a disappearance next to an appearance.
 */
@Immutable
sealed interface BoardChange {
    /** The object this happened to. */
    val id: BoardObjectId

    /** How long the board spends showing it, at full motion. */
    val durationMillis: Int

    /**
     * An object arriving on the board — drawn, cast, created as a token.
     *
     * It carries the whole object rather than an id, because until this change plays the board has
     * never heard of it: there is nothing already on screen for an id to address.
     *
     * @property entering the object as the snapshot that introduced it describes it.
     */
    data class Enter(
        val entering: BoardObject,
    ) : BoardChange {
        override val id: BoardObjectId get() = entering.id

        /** Where it arrives. */
        val slot: BoardSlotId get() = entering.slot

        override val durationMillis: Int get() = BoardDuration.ZONE_MOVE
    }

    /**
     * An object changing which slot it owns — the event the whole subsystem exists for.
     *
     * It is one change rather than an [Exit] and an [Enter] because the object did not stop existing;
     * a host handed an exit and an entry would destroy and recreate it, and the player would lose the
     * one thing the animation was carrying, which is *where it came from*.
     *
     * @property from the slot it left.
     * @property to the slot it now owns.
     */
    data class Move(
        override val id: BoardObjectId,
        val from: BoardSlotId,
        val to: BoardSlotId,
    ) : BoardChange {
        override val durationMillis: Int get() = BoardDuration.ZONE_MOVE
    }

    /**
     * An object leaving the board entirely — a token ceasing to exist, a card leaving a visible zone.
     *
     * @property from the slot it was in when it left.
     */
    data class Exit(
        override val id: BoardObjectId,
        val from: BoardSlotId,
    ) : BoardChange {
        override val durationMillis: Int get() = BoardDuration.ZONE_MOVE
    }

    /**
     * A permanent tapping or untapping.
     *
     * @property tapped what it became.
     */
    data class Tap(
        override val id: BoardObjectId,
        val tapped: Boolean,
    ) : BoardChange {
        override val durationMillis: Int get() = BoardDuration.TAP
    }

    /**
     * A counter appearing, changing or leaving.
     *
     * @property counter the kind of counter, as the board names it.
     * @property from how many there were; zero when the kind was not on the object at all.
     * @property to how many there are now; zero when the last one has gone.
     */
    data class CounterChange(
        override val id: BoardObjectId,
        val counter: String,
        val from: Int,
        val to: Int,
    ) : BoardChange {
        override val durationMillis: Int get() = BoardDuration.COUNTER
    }

    /**
     * An object showing a different face — a card transforming, a face-down permanent turning up.
     *
     * The identity does not change, which is the point: transforming is the case that gets modelled
     * as a death and a birth if identity is keyed off what the object looks like. It is given the
     * zone-move duration because the object becoming a different object is an event of that weight,
     * not because anything travels.
     *
     * @property from the face that was showing.
     * @property to the face showing now.
     */
    data class Transform(
        override val id: BoardObjectId,
        val from: String,
        val to: String,
    ) : BoardChange {
        override val durationMillis: Int get() = BoardDuration.ZONE_MOVE
    }
}

/**
 * A [BoardChange] with its place in the running order.
 *
 * The board's presented state is exactly "the last state, plus every scheduled change whose
 * [startMillis] has arrived" — so a change is applied when it *starts*, and [durationMillis] is how
 * long the movement it causes takes to settle, not a delay before anything happens.
 *
 * @property change what happened.
 * @property startMillis when the board starts showing it, on the sequencer's clock.
 * @property durationMillis how long it takes, after [MotionScale] has been applied.
 */
@Immutable
data class ScheduledChange(
    val change: BoardChange,
    val startMillis: Long,
    val durationMillis: Int,
) {
    /** When the board is finished showing it, and the next change may begin. */
    val endMillis: Long get() = startMillis + durationMillis
}

/**
 * The changes between two snapshots, in the order the board will show them.
 *
 * Order within a snapshot pair is deliberate rather than incidental: things arriving are shown before
 * things leaving, so a token that replaces another reads as a replacement, and in-place changes
 * follow the movements that caused them. Two snapshots that describe the same board produce nothing.
 */
fun diffSnapshots(
    previous: BoardSnapshot,
    next: BoardSnapshot,
): List<BoardChange> {
    val entries = mutableListOf<BoardChange>()
    val movements = mutableListOf<BoardChange>()
    val inPlace = mutableListOf<BoardChange>()
    val exits = mutableListOf<BoardChange>()

    next.objects.forEach { after ->
        val before = previous.byId[after.id]
        if (before == null) {
            entries += BoardChange.Enter(after)
            return@forEach
        }
        if (before.slot != after.slot) {
            movements += BoardChange.Move(after.id, before.slot, after.slot)
        }
        if (before.face != after.face) {
            inPlace += BoardChange.Transform(after.id, before.face, after.face)
        }
        if (before.tapped != after.tapped) {
            inPlace += BoardChange.Tap(after.id, after.tapped)
        }
        (before.counters.keys + after.counters.keys).sorted().forEach { counter ->
            val from = before.counters[counter] ?: 0
            val to = after.counters[counter] ?: 0
            if (from != to) {
                inPlace += BoardChange.CounterChange(after.id, counter, from, to)
            }
        }
    }

    previous.objects.forEach { before ->
        if (next.byId[before.id] == null) {
            exits += BoardChange.Exit(before.id, before.slot)
        }
    }

    return entries + movements + inPlace + exits
}

/** This snapshot with [change] applied — how the presented board advances as the running order plays. */
internal fun BoardSnapshot.applying(change: BoardChange): BoardSnapshot {
    val current = byId[change.id]
    return when (change) {
        is BoardChange.Enter -> with(change.id, change.entering)
        is BoardChange.Exit -> with(change.id, null)
        is BoardChange.Move -> with(change.id, current?.copy(slot = change.to))
        is BoardChange.Tap -> with(change.id, current?.copy(tapped = change.tapped))
        is BoardChange.Transform -> with(change.id, current?.copy(face = change.to))
        is BoardChange.CounterChange ->
            with(
                change.id,
                current?.copy(
                    counters =
                        current.counters.toMutableMap().also { counters ->
                            if (change.to == 0) counters.remove(change.counter) else counters[change.counter] = change.to
                        },
                ),
            )
    }
}
