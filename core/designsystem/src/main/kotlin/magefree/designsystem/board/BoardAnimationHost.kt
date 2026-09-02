package magefree.designsystem.board

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.layout.approachLayout
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.round
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/*
 * The half of the animation host that puts the running order on screen.
 *
 * It does one thing the board layout cannot do for itself: it keeps an object *the same object* while
 * the layout it belongs to changes underneath it. A card that moves from the hand to the battlefield
 * is, to a naive Compose tree, one composable being removed from one list and a different one being
 * added to another — and the player is shown a disappearance next to an appearance, which is exactly
 * the information the move was carrying, destroyed.
 *
 * Two pieces do it. [movableContentOf] keeps the object's composition alive as it changes parent, so
 * it keeps its state and is never recreated. [LookaheadScope] gives every slot one shared coordinate
 * space, so the object can be told where it used to be and where it now is, in the same terms, and
 * travel between the two.
 *
 * Where the slots *are* is deliberately not this file's business — that is the board layout, and it
 * is Phase 3. The host takes slots as given and moves objects between them.
 */

/**
 * Creates a sequencer and drives its clock from the frame clock for as long as anything is playing.
 *
 * The loop stops the moment the board is still, rather than pumping every frame forever: a board
 * nobody is touching should cost nothing, and a composition that requests a frame in perpetuity never
 * goes idle — which would hang every test that waits for it, and drain a phone that is only showing
 * a position.
 *
 * @param initial the board before anything has happened.
 * @param motionScale how much of its duration each change gets; defaults to whatever the board is
 *   running at, so a reduce-motion preference reaches the sequencer without being passed by hand.
 */
@Composable
fun rememberBoardSequencer(
    initial: BoardSnapshot = BoardSnapshot(),
    motionScale: MotionScale = LocalMotionScale.current,
): BoardSequencer {
    val sequencer = remember(motionScale) { BoardSequencer(initial, motionScale) }
    LaunchedEffect(sequencer) {
        snapshotFlow { sequencer.isPlaying }.collectLatest { playing ->
            if (!playing) return@collectLatest
            var previousFrame = withFrameMillis { it }
            while (sequencer.isPlaying) {
                withFrameMillis { frame ->
                    sequencer.advanceBy(frame - previousFrame)
                    previousFrame = frame
                }
            }
        }
    }
    return sequencer
}

/**
 * Hosts a board in one shared coordinate space, with every object addressed by its stable id.
 *
 * The caller supplies the layout — rows, stacks, whatever the board is — and asks for the objects of
 * a slot wherever they belong, through [BoardHostScope.SlotObjects]. The host supplies identity and
 * movement: an object that appears in a different slot in [snapshot] travels there from where it was.
 *
 * @param snapshot the board to draw. In a live board this is the sequencer's [BoardSequencer.presented],
 *   which trails the server while a running order plays — that lag is what there is to see.
 * @param objectContent draws one object. Called once per object, and kept alive across slot changes,
 *   so anything remembered inside it survives the move.
 * @param modifier the [Modifier] for the host's own box.
 * @param content the board layout, which asks for slots' objects where it wants them.
 */
@Composable
fun BoardAnimationHost(
    snapshot: BoardSnapshot,
    objectContent: @Composable (BoardObject) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoardHostScope.() -> Unit,
) {
    val motionScale = LocalMotionScale.current
    LookaheadScope {
        val scope = remember(this) { BoardHostScope(this) }
        scope.snapshot = snapshot
        scope.objectContent = objectContent
        scope.moveDurationMillis = motionScale.scale(BoardDuration.ZONE_MOVE)
        scope.retainOnly(snapshot.objects.map { it.id }.toSet())
        Box(modifier = modifier) { scope.content() }
    }
}

/**
 * What a board layout is handed inside a [BoardAnimationHost]: a way to ask for a slot's objects.
 *
 * It holds the movable content for each object, which is the mechanism identity is preserved by. One
 * entry per id, created the first time the object is seen and kept until the object is gone from the
 * board — an object may only be composed in one place at a time, which is the same rule as "one
 * owning slot per snapshot", enforced by the runtime rather than by convention.
 */
class BoardHostScope internal constructor(
    private val lookahead: LookaheadScope,
) {
    internal var snapshot: BoardSnapshot by mutableStateOf(BoardSnapshot())
    internal var objectContent: @Composable (BoardObject) -> Unit by mutableStateOf({})
    internal var moveDurationMillis: Int by mutableStateOf(BoardDuration.ZONE_MOVE)

    private val holders = mutableMapOf<BoardObjectId, @Composable () -> Unit>()

    /**
     * Draws every object that owns [slot], in snapshot order, wherever the layout puts this call.
     *
     * The layout decides the arrangement; the host decides only that these are the objects and that
     * each of them is the one that was there before.
     */
    @Composable
    fun SlotObjects(slot: BoardSlotId) {
        snapshot.inSlot(slot).forEach { shown -> holderFor(shown.id)() }
    }

    /** Forgets objects that have left the board, so the map does not accumulate a whole game. */
    internal fun retainOnly(ids: Set<BoardObjectId>) {
        holders.keys.retainAll(ids)
    }

    private fun holderFor(id: BoardObjectId): @Composable () -> Unit =
        holders.getOrPut(id) {
            movableContentOf {
                val shown = snapshot.byId[id]
                if (shown != null) {
                    Box(
                        modifier = Modifier.animatePlacementIn(lookahead, moveDurationMillis, BoardEasing.move),
                    ) {
                        objectContent(shown)
                    }
                }
            }
        }
}

/**
 * Places this content where the layout says it goes, but *travels* there from where it last was.
 *
 * The lookahead pass measures the layout as it will be; the approach pass is then free to place the
 * content somewhere else in the meantime and animate the difference away. That is what makes a slot
 * change a movement rather than a jump, and it is why the whole board shares one [LookaheadScope]:
 * "where it was" and "where it goes" have to be expressible in the same coordinates.
 *
 * An object being placed for the first time has no previous position, so it simply appears where it
 * belongs — arriving from the top-left corner of the board would be an animation of something that
 * did not happen.
 */
@Composable
private fun Modifier.animatePlacementIn(
    lookahead: LookaheadScope,
    durationMillis: Int,
    easing: Easing,
): Modifier {
    val coroutineScope = rememberCoroutineScope()
    val spec = remember(durationMillis, easing) { tween<IntOffset>(durationMillis, easing = easing) }
    var placement by remember { mutableStateOf<Animatable<IntOffset, *>?>(null) }
    var target by remember { mutableStateOf<IntOffset?>(null) }
    var travelling by remember { mutableStateOf(false) }

    return approachLayout(
        isMeasurementApproachInProgress = { false },
        isPlacementApproachInProgress = { coordinates ->
            val destination = with(lookahead) { lookaheadScopeCoordinates.localLookaheadPositionOf(coordinates) }.round()
            if (destination != target) {
                target = destination
                val running = placement
                if (running == null) {
                    placement = Animatable(destination, IntOffset.VectorConverter)
                } else {
                    // Marked as travelling *here*, not once the animation reports itself running. The
                    // approach pass is asked this question in the same frame the move is discovered,
                    // before any coroutine has had a turn; answering "not moving" would end the
                    // approach on the spot and place the object at its destination, which is the jump
                    // this modifier exists to prevent.
                    travelling = true
                    coroutineScope.launch {
                        try {
                            running.animateTo(destination, spec)
                        } finally {
                            // Only the animation still heading for the current target may declare the
                            // travel over: a move interrupted by a further move is cancelled here, and
                            // its cleanup must not stop the one that replaced it.
                            if (target == destination) travelling = false
                        }
                    }
                }
            }
            travelling
        },
    ) { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        layout(placeable.width, placeable.height) {
            val here = coordinates
            val animated = placement?.value
            if (here == null || animated == null) {
                placeable.place(IntOffset.Zero)
            } else {
                val current = with(lookahead) { lookaheadScopeCoordinates.localPositionOf(here, Offset.Zero) }.round()
                placeable.place(animated - current)
            }
        }
    }
}
