package magefree.designsystem.component.phase

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
 * The phase bar: where in the turn we are, whose turn it is, and where the game will stop for you.
 *
 * It answers a question the player asks constantly and a second one they otherwise have to remember.
 * The first is positional — a row of steps with the current one marked reads at a glance in a way a
 * line of text never does. The second is that **a stop is a standing instruction**: "stop at my second
 * main phase" is set once and then silently governs every turn afterwards, so the only honest place
 * to show it is on the step it applies to.
 *
 * The stops are the server's, not an invention here. Upstream keeps a `SkipPrioritySteps` per side —
 * one for your turn and one for the opponent's, which is what makes a stop per-phase *and* per-player
 * — covering exactly seven steps: upkeep, draw, first main, beginning of combat, end of combat,
 * second main, and end step. A set flag means **stop**, and first and second main are set by default.
 *
 * This renders that model. Wiring it to a game is separate work.
 */

/** Whose turn the bar is describing. */
enum class PhaseBarTurn {
    /** The viewing player's turn: they may act, so the bar says so in colour. */
    Yours,

    /** An opponent's turn. */
    Opponents,
}

/**
 * One step in the bar.
 *
 * @param id a stable key, so a step keeps its identity as the turn advances.
 * @param label the two or three characters shown in the bar.
 * @param name the full name, for anywhere with room for it.
 * @param stoppable whether the server accepts a stop here. Only seven steps do; the rest are shown so
 *   the turn reads as a whole, but tapping them would be a control the server discards.
 * @param stopSet whether a stop is currently set for this step, on this side of the turn.
 */
data class PhaseBarStep(
    val id: String,
    val label: String,
    val name: String,
    val stoppable: Boolean = false,
    val stopSet: Boolean = false,
)

/**
 * @param steps the turn's steps, in order.
 * @param currentStepId the step the game is in, or null before the first turn begins.
 * @param turn whose turn it is.
 */
data class PhaseBarState(
    val steps: List<PhaseBarStep>,
    val currentStepId: String? = null,
    val turn: PhaseBarTurn = PhaseBarTurn.Yours,
)

/**
 * The steps a turn is shown as.
 *
 * Untap and cleanup are omitted: no player receives priority in either, so a marker there would be a
 * position the game passes through without ever stopping. First-strike damage is omitted for a
 * related reason — it exists only in some turns, and a bar whose length changed with the board would
 * cost more in instability than it returns in precision.
 *
 * @param stops the ids currently set to stop, so the default reflects a real preference rather than a
 *   guess. Upstream starts with both main phases set.
 */
fun standardTurnSteps(stops: Set<String> = setOf(StepIds.PRECOMBAT_MAIN, StepIds.POSTCOMBAT_MAIN)): List<PhaseBarStep> =
    listOf(
        step(StepIds.UPKEEP, "UP", "Upkeep", stoppable = true, stops = stops),
        step(StepIds.DRAW, "DR", "Draw", stoppable = true, stops = stops),
        step(StepIds.PRECOMBAT_MAIN, "M1", "Precombat main", stoppable = true, stops = stops),
        step(StepIds.BEGIN_COMBAT, "BC", "Beginning of combat", stoppable = true, stops = stops),
        step(StepIds.DECLARE_ATTACKERS, "AT", "Declare attackers", stoppable = false, stops = stops),
        step(StepIds.DECLARE_BLOCKERS, "BL", "Declare blockers", stoppable = false, stops = stops),
        step(StepIds.COMBAT_DAMAGE, "DM", "Combat damage", stoppable = false, stops = stops),
        step(StepIds.END_COMBAT, "EC", "End of combat", stoppable = true, stops = stops),
        step(StepIds.POSTCOMBAT_MAIN, "M2", "Postcombat main", stoppable = true, stops = stops),
        step(StepIds.END_TURN, "END", "End step", stoppable = true, stops = stops),
    )

private fun step(
    id: String,
    label: String,
    name: String,
    stoppable: Boolean,
    stops: Set<String>,
) = PhaseBarStep(id = id, label = label, name = name, stoppable = stoppable, stopSet = stoppable && id in stops)

/** The step ids, matching the app schema's own step names. */
object StepIds {
    const val UPKEEP: String = "upkeep"
    const val DRAW: String = "draw"
    const val PRECOMBAT_MAIN: String = "precombatMain"
    const val BEGIN_COMBAT: String = "beginCombat"
    const val DECLARE_ATTACKERS: String = "declareAttackers"
    const val DECLARE_BLOCKERS: String = "declareBlockers"
    const val COMBAT_DAMAGE: String = "combatDamage"
    const val END_COMBAT: String = "endCombat"
    const val POSTCOMBAT_MAIN: String = "postcombatMain"
    const val END_TURN: String = "endTurn"
}

/**
 * The phase bar.
 *
 * @param state the turn, its steps, and where in them the game is.
 * @param onToggleStop invoked when a stoppable step is tapped. Steps the server accepts no stop for
 *   raise nothing, because a control the server discards is worse than no control.
 */
@Composable
fun PhaseBar(
    state: PhaseBarState,
    onToggleStop: (PhaseBarStep) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(BarShape)
                .background(BoardSurface.zone)
                .padding(horizontal = BarPadding, vertical = 2.dp)
                .testTag(PhaseBarTestTags.BAR),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        state.steps.forEach { step ->
            StepChip(
                step = step,
                isCurrent = step.id == state.currentStepId,
                turn = state.turn,
                onToggleStop = onToggleStop,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * One step.
 *
 * **Whose turn it is decides whether the current step is coloured at all.** Saturated colour on the
 * board means the player can act, so the same rule holds here: on your turn the marker is the
 * playable colour, and on an opponent's it is grey. That is the distinction carried by exactly the
 * channel the rest of the board already uses, rather than by a second colour that would have to be
 * learned separately.
 */
@Composable
private fun StepChip(
    step: PhaseBarStep,
    isCurrent: Boolean,
    turn: PhaseBarTurn,
    onToggleStop: (PhaseBarStep) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentColor = if (turn == PhaseBarTurn.Yours) BoardSignal.playable else BoardSurface.onSurfaceMuted
    val background = if (isCurrent) currentColor else Color.Transparent
    val label =
        when {
            isCurrent && turn == PhaseBarTurn.Yours -> BoardSurface.ground
            isCurrent -> BoardSurface.ground
            step.stoppable -> BoardSurface.onSurface
            else -> BoardSurface.onSurfaceMuted
        }

    Column(
        modifier =
            modifier
                .clip(ChipShape)
                .background(background)
                .then(if (step.stoppable) Modifier.clickable { onToggleStop(step) } else Modifier)
                .padding(vertical = 2.dp)
                .testTag(if (isCurrent) PhaseBarTestTags.CURRENT else PhaseBarTestTags.stepTag(step.id)),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = step.label,
            style = BoardTypography.counter,
            color = label,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
        // A stop is a standing instruction that governs turns the player is not looking at, so it is
        // marked on the step it applies to rather than living in a settings screen.
        Box(
            modifier =
                Modifier
                    .size(StopDotSize)
                    .background(
                        color = if (step.stopSet) BoardSignal.pendingCost else Color.Transparent,
                        shape = CircleShape,
                    ).testTag(if (step.stopSet) PhaseBarTestTags.stopTag(step.id) else PhaseBarTestTags.NO_STOP),
        )
    }
}

/** Test tags for the bar and its parts. */
object PhaseBarTestTags {
    const val BAR: String = "phase-bar"
    const val CURRENT: String = "phase-bar-current"
    const val NO_STOP: String = "phase-bar-no-stop"

    /** The chip for one step. */
    fun stepTag(id: String): String = "phase-bar-step-$id"

    /** The stop marker on one step, present only when a stop is set there. */
    fun stopTag(id: String): String = "phase-bar-stop-$id"
}

private val BarShape = RoundedCornerShape(6.dp)
private val ChipShape = RoundedCornerShape(4.dp)
private val BarPadding = 4.dp
private val StopDotSize = 4.dp

/** The bar's own height, so a board laying it out knows what it costs before measuring. */
val PhaseBarHeight = 26.dp
