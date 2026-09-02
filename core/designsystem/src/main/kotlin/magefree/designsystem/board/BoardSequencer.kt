package magefree.designsystem.board

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/*
 * The half of the animation host that decides *what* the board shows and *in what order*.
 *
 * It holds no pixels and measures nothing, which is the point: "play in order", "drain on a prompt"
 * and "snap on a resync" are decisions about a queue, and a queue can be driven by a clock the test
 * owns and asserted exactly. If this is wrong, no amount of correct animation code can hide it; if
 * this is right, the visual layer is the mechanical part.
 *
 * The rule that shapes everything here: **the board's presentation may trail the server, and that is
 * intended.** A player watching a chain of triggers resolve has no decision to make while it plays,
 * so the sequencer is free to hold the board a moment behind the truth. What it may never do is
 * collapse the chain into its final state — that is the failure case, because the player would see
 * the board end up somewhere with no idea how it got there.
 */

/**
 * Turns a stream of snapshots into an ordered running order, and reports the board that should be on
 * screen right now.
 *
 * Time is supplied, never read: [advanceTo] moves the clock, so a test drives the whole subsystem
 * without waiting for anything and the Compose host drives it from a frame clock. The sequencer is
 * not thread-safe; it is owned by whatever composes the board.
 *
 * @param initial the board as it stands before anything has happened — no changes are derived for it,
 *   because a board that has just appeared has not *done* anything the player needs to be shown.
 * @param motionScale how much of its full duration each change gets. Applied once, here, so that a
 *   reduce-motion preference shortens the whole running order rather than individual animations.
 */
class BoardSequencer(
    initial: BoardSnapshot = BoardSnapshot(),
    private val motionScale: MotionScale = MotionScale.Full,
) {
    /**
     * The board as it should be drawn at the current clock — the last state plus every change that
     * has started.
     *
     * This trails [latest] whenever a running order is playing, and that lag *is* the animation.
     */
    var presented: BoardSnapshot by mutableStateOf(initial)
        private set

    /** The most recent snapshot received: the server's truth, which the board is catching up to. */
    var latest: BoardSnapshot = initial
        private set

    private val queue = ArrayDeque<ScheduledChange>()
    private val running = mutableListOf<ScheduledChange>()
    private var nowMillis = 0L
    private var busyUntilMillis = 0L

    /** The changes that have not started yet, in the order they will. */
    val pending: List<ScheduledChange> get() = queue.toList()

    /** The changes that have started and not yet settled — what the host is animating. */
    val active: List<ScheduledChange> get() = running.toList()

    /** Whether the board has caught up: nothing waiting, nothing still moving. */
    val isIdle: Boolean get() = queue.isEmpty() && running.isEmpty()

    /** How far behind the server the board currently is, in milliseconds of running order left. */
    val backlogMillis: Long get() = (busyUntilMillis - nowMillis).coerceAtLeast(0L)

    /**
     * Takes a new snapshot from the server and appends what changed to the running order.
     *
     * Appends rather than replaces: a snapshot arriving while an earlier one is still being shown does
     * not cut it short, because the earlier one described something the player has not seen yet.
     *
     * Anything that is not an event — a card's own detail changing, its [BoardObject.payload] — is
     * applied to [presented] immediately. There is nothing to show for it, so making it wait behind a
     * queue would only make the board wrong for longer.
     */
    fun onSnapshot(next: BoardSnapshot) {
        val changes = diffSnapshots(latest, next)
        latest = next
        adoptQuietDetail(next)
        changes.forEach { change ->
            val start = maxOf(nowMillis, busyUntilMillis)
            val duration = motionScale.scale(change.durationMillis)
            queue.addLast(ScheduledChange(change, start, duration))
            busyUntilMillis = start + duration
        }
        if (backlogMillis > MAX_BACKLOG_MILLIS) {
            compressTo(MAX_BACKLOG_MILLIS)
        }
    }

    /**
     * The server has asked the player to decide something.
     *
     * Being asked to act is the sync point: nobody should act on a stale board, so whatever is left
     * of the running order finishes **quickly** rather than being jumped or waited out. Every change
     * still gets its turn on screen — a drained sequence is fast, not silent.
     */
    fun onPrompt() {
        compressTo(PROMPT_DRAIN_MILLIS)
    }

    /**
     * The connection came back and the server has sent current state.
     *
     * A resync is not a sequence. There is no backlog to replay, and replaying one would narrate
     * events the player already missed while they were disconnected, so the board simply *is* the new
     * state — it snaps.
     */
    fun onResync(snapshot: BoardSnapshot) {
        queue.clear()
        running.clear()
        latest = snapshot
        presented = snapshot
        busyUntilMillis = nowMillis
    }

    /**
     * Moves the clock to [millis], starting every change whose turn has come and retiring those that
     * have settled.
     *
     * Time only ever moves forward; a caller that hands back an earlier reading is describing a clock
     * the board cannot follow, so it is refused rather than silently reordered.
     */
    fun advanceTo(millis: Long) {
        require(millis >= nowMillis) { "the clock moved backwards: $millis is before $nowMillis" }
        nowMillis = millis
        while (queue.isNotEmpty() && queue.first().startMillis <= nowMillis) {
            val started = queue.removeFirst()
            presented = presented.applying(started.change)
            running += started
        }
        running.removeAll { it.endMillis <= nowMillis }
    }

    /** Advances the clock far enough that the whole running order has played and settled. */
    fun advanceToIdle() {
        if (busyUntilMillis > nowMillis) advanceTo(busyUntilMillis)
    }

    /**
     * Copies across the parts of [next] that are not events.
     *
     * Only for objects already on screen, and only fields no [BoardChange] describes: a slot, a tap,
     * a counter and a face all have their turn in the running order, and overwriting them here would
     * be the collapse this class exists to prevent.
     */
    private fun adoptQuietDetail(next: BoardSnapshot) {
        val updated =
            presented.objects.map { shown ->
                val truth = next.byId[shown.id] ?: return@map shown
                if (truth.payload == shown.payload) shown else shown.copy(payload = truth.payload)
            }
        presented = BoardSnapshot(updated)
    }

    /**
     * Squeezes what has not started yet into [totalMillis], keeping every change and its order.
     *
     * One mechanism serves both bounds. A prompt asks for a short one because the player is about to
     * act; a backlog that has grown past what trailing can justify asks for a longer one. Neither
     * drops a change: a change that is never shown is a game action the player was never told about,
     * and that is the same defect as collapsing the sequence, arrived at from the other side.
     *
     * Each change is given the slice of [totalMillis] its own duration is worth, measured against a
     * running total rather than scaled one at a time — rounding each in isolation lets the errors
     * accumulate, and a budget that is overshot by a few milliseconds per change is not a budget.
     * The floor is [MotionScale.MINIMUM_MILLIS], so a running order with more changes in it than
     * there are milliseconds to spend will overrun rather than lose one.
     */
    private fun compressTo(totalMillis: Long) {
        val original = queue.sumOf { it.durationMillis.toLong() }
        if (queue.isEmpty() || backlogMillis <= totalMillis || original <= 0L) return
        var consumed = 0L
        var cursor = nowMillis
        val compressed =
            queue.map { scheduled ->
                consumed += scheduled.durationMillis
                val settlesAt = nowMillis + totalMillis * consumed / original
                val duration = (settlesAt - cursor).coerceAtLeast(MotionScale.MINIMUM_MILLIS.toLong()).toInt()
                ScheduledChange(scheduled.change, cursor, duration).also { cursor += duration }
            }
        queue.clear()
        queue.addAll(compressed)
        busyUntilMillis = cursor
    }

    companion object {
        /**
         * How long the rest of the running order is given once the player has been asked to act.
         *
         * Short enough that answering does not mean waiting, long enough that the changes are still
         * seen going past rather than teleporting to their destination.
         */
        const val PROMPT_DRAIN_MILLIS: Long = 250L

        /**
         * The most the board is allowed to trail the server while nobody is being asked anything.
         *
         * Trailing is intended, but it is intended as *a moment*. Past this the board has stopped
         * being a slightly delayed picture of the game and started being a different game.
         */
        const val MAX_BACKLOG_MILLIS: Long = 2_000L
    }
}
