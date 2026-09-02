package magefree.designsystem.board

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sequencer, on a clock the test owns.
 *
 * The assertion the whole subsystem stands on is the first one: **two changes in one snapshot do not
 * happen together.** A sequencer that collapses a chain into its final state passes every other test
 * here — the board still ends up correct — and fails the player completely, because they see the
 * result of three things having happened and cannot tell what any of them were.
 */
class BoardSequencerTest {
    @Test
    fun `two changes in one snapshot play in order, not together`() {
        val sequencer = BoardSequencer(initial = snapshot(bear(HAND), wolf(HAND)))

        sequencer.onSnapshot(snapshot(bear(BATTLEFIELD), wolf(BATTLEFIELD)))

        // The instant the running order starts, exactly one of them has moved.
        sequencer.advanceTo(0)
        assertEquals("the first change starts immediately", BATTLEFIELD, sequencer.presented.byId[BEAR]?.slot)
        assertEquals(
            "the second change must wait its turn — collapsing them is the failure case",
            HAND,
            sequencer.presented.byId[WOLF]?.slot,
        )

        // And it is still the case a frame before the first one has finished.
        sequencer.advanceTo(BoardDuration.ZONE_MOVE - 1L)
        assertEquals(HAND, sequencer.presented.byId[WOLF]?.slot)

        sequencer.advanceTo(BoardDuration.ZONE_MOVE.toLong())
        assertEquals("the second change follows the first", BATTLEFIELD, sequencer.presented.byId[WOLF]?.slot)
    }

    @Test
    fun `the board catches up with the server once the running order has played`() {
        val sequencer = BoardSequencer(initial = snapshot(bear(HAND), wolf(HAND)))

        sequencer.onSnapshot(snapshot(bear(BATTLEFIELD), wolf(BATTLEFIELD)))
        sequencer.advanceToIdle()

        assertEquals("the board ends where the server is", sequencer.latest, sequencer.presented)
        assertTrue("and nothing is left running", sequencer.isIdle)
    }

    @Test
    fun `an object that changes slot moves, rather than dying and being reborn`() {
        val changes = diffSnapshots(snapshot(bear(HAND)), snapshot(bear(BATTLEFIELD)))

        assertEquals(listOf(BoardChange.Move(BEAR, HAND, BATTLEFIELD)), changes)
    }

    @Test
    fun `an id that only appears is an entry, and one that only vanishes is an exit`() {
        val entered = diffSnapshots(snapshot(), snapshot(bear(BATTLEFIELD)))
        val left = diffSnapshots(snapshot(bear(BATTLEFIELD)), snapshot())

        assertEquals(listOf(BoardChange.Enter(bear(BATTLEFIELD))), entered)
        assertEquals(listOf(BoardChange.Exit(BEAR, BATTLEFIELD)), left)
    }

    @Test
    fun `a card that transforms keeps its identity`() {
        // The case that gets modelled as a death and a birth if identity is keyed off appearance:
        // the object is the same object, and the player needs to see it become something else rather
        // than watch one card vanish and an unrelated one arrive in its place.
        val changes =
            diffSnapshots(
                snapshot(bear(BATTLEFIELD)),
                snapshot(bear(BATTLEFIELD).copy(face = "back")),
            )

        assertEquals(listOf(BoardChange.Transform(BEAR, BoardObject.DEFAULT_FACE, "back")), changes)
    }

    @Test
    fun `a token that ceases to exist leaves, and is not confused with the one that replaced it`() {
        val changes =
            diffSnapshots(
                snapshot(BoardObject(TOKEN_A, BATTLEFIELD)),
                snapshot(BoardObject(TOKEN_B, BATTLEFIELD)),
            )

        assertEquals(
            "two different ids are two different objects, whatever they look like",
            listOf(BoardChange.Enter(BoardObject(TOKEN_B, BATTLEFIELD)), BoardChange.Exit(TOKEN_A, BATTLEFIELD)),
            changes,
        )
    }

    @Test
    fun `tapping and counters are events in their own right, on their own durations`() {
        val changes =
            diffSnapshots(
                snapshot(bear(BATTLEFIELD)),
                snapshot(bear(BATTLEFIELD).copy(tapped = true, counters = mapOf("+1/+1" to 2))),
            )

        assertEquals(
            listOf(
                BoardChange.Tap(BEAR, tapped = true),
                BoardChange.CounterChange(BEAR, "+1/+1", from = 0, to = 2),
            ),
            changes,
        )
        assertEquals(BoardDuration.TAP, changes[0].durationMillis)
        assertEquals(BoardDuration.COUNTER, changes[1].durationMillis)
    }

    @Test
    fun `a board that has not changed produces nothing to show`() {
        val sequencer = BoardSequencer(initial = snapshot(bear(BATTLEFIELD)))

        sequencer.onSnapshot(snapshot(bear(BATTLEFIELD)))

        assertTrue("an unchanged snapshot is not an event", sequencer.isIdle)
        assertEquals(emptyList<ScheduledChange>(), sequencer.pending)
    }

    @Test
    fun `detail that is not an event does not queue behind one`() {
        // A card's own description changing is not something the player is being told about, so it
        // lands at once. Waiting behind the running order would only make the board wrong for longer.
        val sequencer = BoardSequencer(initial = snapshot(bear(BATTLEFIELD), wolf(HAND)))

        sequencer.onSnapshot(snapshot(bear(BATTLEFIELD).copy(payload = "2/2"), wolf(BATTLEFIELD)))

        assertEquals("2/2", sequencer.presented.byId[BEAR]?.payload)
        assertEquals("but the move it arrived with still waits", HAND, sequencer.presented.byId[WOLF]?.slot)
    }

    @Test
    fun `being asked to act drains what is left, without dropping any of it`() {
        val sequencer = BoardSequencer(initial = snapshot())
        sequencer.onSnapshot(snapshot(*tokens(8)))
        val queued = sequencer.pending.map { it.change }

        sequencer.onPrompt()

        assertEquals("every change still gets its turn — a drain is fast, not silent", queued, sequencer.pending.map { it.change })
        assertTrue(
            "the player must not wait ${sequencer.backlogMillis}ms to answer",
            sequencer.backlogMillis <= BoardSequencer.PROMPT_DRAIN_MILLIS,
        )

        sequencer.advanceToIdle()
        assertEquals("and the board they answer on is current", sequencer.latest, sequencer.presented)
    }

    @Test
    fun `a drained running order still plays in order`() {
        val sequencer = BoardSequencer(initial = snapshot())
        sequencer.onSnapshot(snapshot(*tokens(4)))

        sequencer.onPrompt()

        val starts = sequencer.pending.map { it.startMillis }
        assertEquals("no two changes may start at the same instant", starts.distinct(), starts)
        assertEquals("and they keep their order", starts.sorted(), starts)
    }

    @Test
    fun `a resync snaps, and replays nothing`() {
        val sequencer = BoardSequencer(initial = snapshot(bear(HAND)))
        sequencer.onSnapshot(snapshot(bear(BATTLEFIELD), wolf(BATTLEFIELD)))

        sequencer.onResync(snapshot(bear(GRAVEYARD)))

        assertTrue("there is no backlog to replay after a reconnect", sequencer.isIdle)
        assertEquals(snapshot(bear(GRAVEYARD)), sequencer.presented)
        assertNull("what happened while disconnected is not narrated", sequencer.presented.byId[WOLF])
    }

    @Test
    fun `the backlog does not grow without bound`() {
        val sequencer = BoardSequencer(initial = snapshot())

        // Far more than any real chain of triggers, arriving faster than the board can show it.
        repeat(60) { index ->
            sequencer.onSnapshot(snapshot(*tokens(index + 1)))
            assertTrue(
                "the board trailed by ${sequencer.backlogMillis}ms — that is a different game, not a delayed picture of this one",
                sequencer.backlogMillis <= BoardSequencer.MAX_BACKLOG_MILLIS,
            )
        }

        assertEquals("and nothing was thrown away to achieve it", 60, sequencer.pending.size)
    }

    @Test
    fun `reduce motion shortens every duration and removes none`() {
        val reduced = BoardSequencer(initial = snapshot(bear(HAND)), motionScale = MotionScale.Reduced)
        val full = BoardSequencer(initial = snapshot(bear(HAND)))

        reduced.onSnapshot(snapshot(bear(BATTLEFIELD)))
        full.onSnapshot(snapshot(bear(BATTLEFIELD)))

        val reducedMove = reduced.pending.single()
        assertTrue("reduce-motion must be quicker", reducedMove.durationMillis < full.pending.single().durationMillis)
        assertTrue("but never instant — removing the animation removes the information", reducedMove.durationMillis > 0)

        reduced.advanceTo(0)
        assertNotNull("and the move still happens", reduced.active.singleOrNull())
    }

    @Test
    fun `a change is on screen for as long as it was given`() {
        val sequencer = BoardSequencer(initial = snapshot(bear(HAND)))
        sequencer.onSnapshot(snapshot(bear(BATTLEFIELD)))

        sequencer.advanceTo(BoardDuration.ZONE_MOVE - 1L)
        assertEquals("still moving", 1, sequencer.active.size)

        sequencer.advanceTo(BoardDuration.ZONE_MOVE.toLong())
        assertTrue("settled", sequencer.isIdle)
    }

    private companion object {
        val HAND = BoardSlotId("hand")
        val BATTLEFIELD = BoardSlotId("battlefield")
        val GRAVEYARD = BoardSlotId("graveyard")

        val BEAR = BoardObjectId("bear")
        val WOLF = BoardObjectId("wolf")
        val TOKEN_A = BoardObjectId("token-a")
        val TOKEN_B = BoardObjectId("token-b")

        fun bear(slot: BoardSlotId) = BoardObject(BEAR, slot)

        fun wolf(slot: BoardSlotId) = BoardObject(WOLF, slot)

        fun snapshot(vararg objects: BoardObject) = BoardSnapshot(objects.toList())

        /** [count] tokens on the battlefield — a stand-in for a chain that arrives faster than it plays. */
        fun tokens(count: Int) = Array(count) { BoardObject(BoardObjectId("token-$it"), BATTLEFIELD) }
    }
}
