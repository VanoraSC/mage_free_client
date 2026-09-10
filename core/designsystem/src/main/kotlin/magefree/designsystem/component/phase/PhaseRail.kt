package magefree.designsystem.component.phase

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import magefree.designsystem.board.BoardSignal
import magefree.designsystem.board.BoardSurface
import magefree.designsystem.board.BoardTypography

/*
 * The turn, as a vertical rail with a column per side.
 *
 * ```
 *        ┌────┬─────┬────┐
 *        │    │ UP  │    │     ← left column lit: it is an opponent's turn
 *        │    │ DR  │    │
 *        │    │ M1  │  ● │     ← a mark in the right column: stop on *your* M1
 *        │▓▓▓▓│ BC  │    │
 *        │    │ AT  │    │
 *        │    │ ... │    │
 *        │  ● │ END │  ● │     ← the end step, marked on both by default
 *        └────┴─────┴────┘
 *          them        you
 * ```
 *
 * **One glance answers two questions.** The horizontal bar answered *which phase* by position and
 * *whose turn* by the colour of the current chip — two facts sharing one channel, so a player checking
 * the second had to notice a colour they were not looking for. Here the two are separate axes: down
 * the rail is the phase, and across it is the side.
 *
 * **Each column is a side, which is what makes a per-side stop sayable.** 0115 merged the two sides
 * because one row could not say which a mark belonged to; two columns can, and each is pressed on its
 * own. Upstream has kept a `SkipPrioritySteps` per side all along, so this is the client catching up
 * to the server rather than inventing a distinction.
 *
 * **The label goes between the columns, not inside them.** Drawn inside, it would be dark-on-light in
 * whichever cell happens to be lit and light-on-dark in the other, which is the same word rendered two
 * ways on one row. In the gutter it is one word, and the two columns are free to be pure illumination.
 */

/** One side's cell on a step's row. */
data class PhaseRailCell(
    /** What the player has asked for here. */
    val stop: PhaseStop = PhaseStop.None,
    /**
     * Whether pressing this cell changes anything.
     *
     * False for a step upstream accepts no stop for, and for one whose stop is a *rule* — see the
     * caller's `locked`. A rule is drawn as a stop and refuses to be pressed away, because that is
     * exactly what a rule is.
     */
    val stoppable: Boolean = false,
)

/** One step of the turn: a row of the rail, with a cell per side. */
data class PhaseRailStep(
    val id: String,
    val label: String,
    val name: String,
    val opponents: PhaseRailCell = PhaseRailCell(),
    val yours: PhaseRailCell = PhaseRailCell(),
) {
    /** The cell belonging to [turn]. */
    fun cell(turn: PhaseBarTurn): PhaseRailCell = if (turn == PhaseBarTurn.Yours) yours else opponents
}

/**
 * The rail for one snapshot.
 *
 * @property steps the turn, in order, top to bottom.
 * @property currentStepId where the game is, or null for a step the rail does not draw — untap and
 *   cleanup, which leave the marker where it was rather than moving it somewhere not shown.
 * @property turn whose turn it is, which decides **which column** is lit.
 */
data class PhaseRailState(
    val steps: List<PhaseRailStep>,
    val currentStepId: String? = null,
    val turn: PhaseBarTurn = PhaseBarTurn.Yours,
)

/**
 * The steps a turn is shown as, with a cell per side.
 *
 * The same ten as the horizontal bar drew, for the same reasons: untap and cleanup give nobody
 * priority, and first-strike damage exists only in some turns, so a rail that drew them would either
 * mark a position the game never stops in or change length with the board.
 *
 * @param yours what the player has asked for on their own turn.
 * @param opponents what they have asked for on everybody else's.
 * @param lockedYours steps whose stop is a **rule** on the player's own turn — drawn as always
 *   stopping, and not pressable. Per side, because the rules are: your own main phases are a rule on
 *   your turn and an ordinary choice on theirs.
 * @param lockedBoth steps whose stop is a rule on **both** sides — the combat steps, which upstream
 *   gives priority in whatever the flags say.
 */
fun standardRailSteps(
    yours: Map<String, PhaseStop> = emptyMap(),
    opponents: Map<String, PhaseStop> = emptyMap(),
    lockedYours: Set<String> = emptySet(),
    lockedBoth: Set<String> = emptySet(),
): List<PhaseRailStep> =
    RAIL_STEPS.map { (id, labels) ->
        val (label, name) = labels
        val stoppable = id in STOPPABLE_STEPS
        PhaseRailStep(
            id = id,
            label = label,
            name = name,
            opponents = cell(id, stoppable, opponents, lockedBoth),
            yours = cell(id, stoppable, yours, lockedBoth + lockedYours),
        )
    }

private fun cell(
    id: String,
    stoppable: Boolean,
    stops: Map<String, PhaseStop>,
    locked: Set<String>,
) = PhaseRailCell(
    // A step upstream accepts no stop for is not made stoppable by a locked one: `locked` is how a
    // stop that is a *rule* is drawn, and a rule is exactly a stop the player may not press away.
    stop = if (id in locked) PhaseStop.Always else stops[id].takeIf { stoppable } ?: PhaseStop.None,
    stoppable = stoppable && id !in locked,
)

/** The ten drawn steps, in turn order, with the rail's abbreviation and the full name. */
private val RAIL_STEPS: List<Pair<String, Pair<String, String>>> =
    listOf(
        StepIds.UPKEEP to ("UP" to "Upkeep"),
        StepIds.DRAW to ("DR" to "Draw"),
        StepIds.PRECOMBAT_MAIN to ("M1" to "Precombat main"),
        StepIds.BEGIN_COMBAT to ("BC" to "Beginning of combat"),
        StepIds.DECLARE_ATTACKERS to ("AT" to "Declare attackers"),
        StepIds.DECLARE_BLOCKERS to ("BL" to "Declare blockers"),
        StepIds.COMBAT_DAMAGE to ("DM" to "Combat damage"),
        StepIds.END_COMBAT to ("EC" to "End of combat"),
        StepIds.POSTCOMBAT_MAIN to ("M2" to "Postcombat main"),
        StepIds.END_TURN to ("END" to "End step"),
    )

/** The steps upstream's `SkipPrioritySteps` has a flag for. Everything else stops by rule or not at all. */
private val STOPPABLE_STEPS: Set<String> =
    setOf(
        StepIds.UPKEEP,
        StepIds.DRAW,
        StepIds.PRECOMBAT_MAIN,
        StepIds.BEGIN_COMBAT,
        StepIds.END_COMBAT,
        StepIds.POSTCOMBAT_MAIN,
        StepIds.END_TURN,
    )

/**
 * The vertical phase rail.
 *
 * @param state the turn, its steps, and where in them the game is.
 * @param onToggleStop invoked with the step and the side whose cell was pressed. A cell that is not
 *   [PhaseRailCell.stoppable] raises nothing, because a control the server discards is worse than no
 *   control at all.
 * @param modifier the [Modifier] for the rail. It fills whatever height it is given, dividing it
 *   evenly between the steps — the rail lives in a column with the graveyards and the counts, and how
 *   much is left over depends on them.
 */
@Composable
fun PhaseRail(
    state: PhaseRailState,
    modifier: Modifier = Modifier,
    onToggleStop: ((PhaseRailStep, PhaseBarTurn) -> Unit)? = null,
) {
    Column(
        modifier =
            modifier
                .clip(RailShape)
                .background(BoardSurface.zone)
                .padding(RailPadding)
                .testTag(PhaseRailTestTags.RAIL),
        verticalArrangement = Arrangement.spacedBy(StepGap),
    ) {
        state.steps.forEach { step ->
            StepRow(
                step = step,
                isCurrent = step.id == state.currentStepId,
                turn = state.turn,
                onToggleStop = onToggleStop,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        }
    }
}

/**
 * One step: two cells and the name between them.
 *
 * The label is drawn **over** the two cells rather than inside either, so it reads the same however
 * the illumination falls. It takes no pointer input, so a press on it reaches the cell underneath —
 * the same arrangement the board's arrow canvas uses.
 */
@Composable
private fun StepRow(
    step: PhaseRailStep,
    isCurrent: Boolean,
    turn: PhaseBarTurn,
    onToggleStop: ((PhaseRailStep, PhaseBarTurn) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.testTag(PhaseRailTestTags.step(step.id))) {
        Row(modifier = Modifier.fillMaxSize()) {
            StopCell(
                step = step,
                side = PhaseBarTurn.Opponents,
                isLit = isCurrent && turn == PhaseBarTurn.Opponents,
                onToggleStop = onToggleStop,
                alignment = Alignment.CenterStart,
                modifier = Modifier.fillMaxHeight().weight(1f),
            )
            Box(modifier = Modifier.width(LabelWidth))
            StopCell(
                step = step,
                side = PhaseBarTurn.Yours,
                isLit = isCurrent && turn == PhaseBarTurn.Yours,
                onToggleStop = onToggleStop,
                alignment = Alignment.CenterEnd,
                modifier = Modifier.fillMaxHeight().weight(1f),
            )
        }

        Text(
            text = step.label,
            style = BoardTypography.counter,
            color = if (isCurrent) BoardSurface.onSurface else BoardSurface.onSurfaceMuted,
            maxLines = 1,
            textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.Center).width(LabelWidth),
        )
    }
}

/**
 * One side's cell: the illumination, and the mark.
 *
 * **The lit colour is the board's own answer to "can you act".** Green everywhere on this board means
 * the player may do something, so their own turn lights green and an opponent's lights grey — the
 * distinction carried by a channel the player has already learned, rather than by a second one.
 */
@Composable
private fun StopCell(
    step: PhaseRailStep,
    side: PhaseBarTurn,
    isLit: Boolean,
    onToggleStop: ((PhaseRailStep, PhaseBarTurn) -> Unit)?,
    alignment: Alignment,
    modifier: Modifier = Modifier,
) {
    val cell = step.cell(side)
    val lit = if (side == PhaseBarTurn.Yours) BoardSignal.playable else BoardSurface.onSurfaceMuted

    Box(
        modifier =
            modifier
                .clip(CellShape)
                .background(if (isLit) lit else Color.Transparent)
                .then(
                    if (cell.stoppable && onToggleStop != null) {
                        Modifier.clickable { onToggleStop(step, side) }
                    } else {
                        Modifier
                    },
                ).padding(horizontal = DotInset)
                .testTag(PhaseRailTestTags.cell(step.id, side)),
        contentAlignment = alignment,
    ) {
        // A stop is a standing instruction governing turns the player is not looking at, so it is
        // marked on the step it applies to rather than living in a settings screen.
        //
        // **Two kinds of stop, two colours.** One that fires once and clears itself is a different
        // promise from one that fires every turn, and a player setting them a step apart has to tell
        // at a glance which they set. Colour rather than shape: the mark is a few dp across, and a
        // shape that small is a smudge.
        Box(
            modifier =
                Modifier
                    .size(StopDotSize)
                    .background(
                        color =
                            when (cell.stop) {
                                PhaseStop.None -> Color.Transparent
                                PhaseStop.Once -> StopOnce
                                PhaseStop.Always -> StopAlways
                            },
                        shape = CircleShape,
                    ).testTag(
                        when (cell.stop) {
                            PhaseStop.None -> PhaseRailTestTags.NO_STOP
                            PhaseStop.Once -> PhaseRailTestTags.onceStop(step.id, side)
                            PhaseStop.Always -> PhaseRailTestTags.stop(step.id, side)
                        },
                    ),
        )
    }
}

/** Test tags for the rail and its parts, which are told apart by position rather than by text. */
object PhaseRailTestTags {
    const val RAIL: String = "phase-rail"
    const val NO_STOP: String = "phase-rail-no-stop"

    /** One step's whole row. */
    fun step(stepId: String): String = "phase-rail-step-$stepId"

    /** One side's cell on a step's row — the thing a press lands on. */
    fun cell(
        stepId: String,
        side: PhaseBarTurn,
    ): String = "phase-rail-cell-${side.slug()}-$stepId"

    /** A stop that fires every time. */
    fun stop(
        stepId: String,
        side: PhaseBarTurn,
    ): String = "phase-rail-stop-${side.slug()}-$stepId"

    /** A stop that fires once and clears itself. */
    fun onceStop(
        stepId: String,
        side: PhaseBarTurn,
    ): String = "phase-rail-once-stop-${side.slug()}-$stepId"

    private fun PhaseBarTurn.slug(): String = if (this == PhaseBarTurn.Yours) "yours" else "theirs"
}

private val RailShape = RoundedCornerShape(6.dp)
private val CellShape = RoundedCornerShape(3.dp)
private val RailPadding = 3.dp
private val StepGap = 2.dp
private val StopDotSize = 5.dp

/** How far a mark sits in from its column's outer edge, so the two do not crowd the label. */
private val DotInset = 2.dp

/**
 * The gutter the step's name is drawn in.
 *
 * Wide enough for the longest abbreviation the rail draws — `END` — at the board's counter size, and
 * no wider: every dp here is a dp the two columns do not have, and the columns are what is pressed.
 */
private val LabelWidth = 24.dp

/** A stop that fires once and clears itself. The same blue the horizontal bar used. */
private val StopOnce: Color = Color(0xFF60A5FA)

/** A stop that fires every time. The same red. */
private val StopAlways: Color = Color(0xFFF87171)
