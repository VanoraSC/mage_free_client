package magefree.feature.game.table

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import magefree.designsystem.board.BoardSignal
import magefree.designsystem.board.BoardSurface
import magefree.designsystem.board.BoardTypography

/*
 * A player's life total, on the centre line of their own edge of the table.
 *
 * **It left the status rail to become a thing on the board.** In the rail it was a number in a corner
 * with four zone counts under it — read when you go looking for it. Here it sits where the player is
 * already looking, mirrored top and bottom exactly as the battlefields are, so whose life it is needs
 * no label.
 *
 * **And it is how a player is targeted.** Every other candidate for a target question is a card on
 * the board that says so with a green border; a *player* had no such place, and was answered instead
 * from a list of names in the prompt panel — a second vocabulary for the same act. Now a player is
 * pointed at like anything else: the life total takes the same green when the question can be
 * answered with it, the same tint when it has been chosen, and the arrows from the stack point here.
 *
 * **The board never decides who is targetable.** [LifeTotalState.isPickable] is the outstanding
 * prompt's own candidate list, and upstream targets a player by their id like any other object, so
 * the id this is anchored and pressed by *is* the player id the server sent.
 */

/**
 * One player's life total.
 *
 * @property playerId the server's own id, which is what a target names and what an arrow anchors to.
 * @property life the server's number.
 * @property isPickable whether the outstanding question can be answered with this player.
 * @property isSelected whether they have already been chosen as part of that answer.
 */
data class LifeTotalState(
    val playerId: String,
    val life: Int,
    val isPickable: Boolean = false,
    val isSelected: Boolean = false,
)

/**
 * The life totals for one snapshot, with what [picks] says about each player.
 *
 * Two lists rather than one, because they are drawn at opposite edges and nothing else about them
 * differs: [LifeTotals.opponents] against the top, [LifeTotals.viewer] against the bottom.
 */
data class LifeTotals(
    val opponents: List<LifeTotalState>,
    val viewer: LifeTotalState?,
)

/** Every seat's life total, from the same vitals the rail draws. */
fun lifeTotals(
    vitals: List<TableVitals>,
    picks: PromptPicks = PromptPicks(),
): LifeTotals {
    val states =
        vitals.map { seat ->
            LifeTotalState(
                playerId = seat.playerId,
                life = seat.life,
                isPickable = seat.playerId in picks.pickable,
                isSelected = seat.playerId in picks.picked,
            )
        }
    return LifeTotals(
        opponents = states.filterIndexed { index, _ -> !vitals[index].isViewer },
        viewer = states.firstOrNull { state -> vitals.first { it.playerId == state.playerId }.isViewer },
    )
}

/**
 * One life total, drawn.
 *
 * **The same two channels a card uses**, for the same reason: green says *the question can be
 * answered with this*, and the fill says *it has been*. A player who has learned what a green border
 * on a creature means already knows what a green ring here means.
 *
 * @param onPick answers the question with this player, or `null` when they are not a candidate. It is
 *   the only thing a press does — the seat's piles are opened from the status rail, which is still
 *   where everything else about a player lives.
 */
@Composable
fun LifeTotal(
    state: LifeTotalState,
    modifier: Modifier = Modifier,
    onPick: (() -> Unit)? = null,
) {
    val ring = if (state.isPickable) BoardSignal.playable else Color.Transparent
    Box(
        modifier =
            modifier
                .defaultMinSize(minWidth = LifeSize, minHeight = LifeSize)
                .background(if (state.isSelected) SelectedFill else LifeFill, CircleShape)
                .border(width = RingWidth, color = ring, shape = CircleShape)
                .let { base -> onPick?.let { pick -> base.clickable(onClick = pick) } ?: base }
                .padding(horizontal = LifePadding)
                .testTag(LifeTotalTestTags.total(state.playerId)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "${state.life}",
            style = BoardTypography.vitals,
            color = BoardSurface.onSurface,
        )
    }
}

/** Test tags. Which seat's total this is, is said by its id rather than by where it ended up. */
object LifeTotalTestTags {
    fun total(playerId: String): String = "life-total-$playerId"
}

/**
 * Life's own red, unchanged from the rail it left — life is red everywhere, and moving the number did
 * not change what it means.
 */
private val LifeFill = Color(0xFFB91C1C)

/** A chosen player, in the same green a chosen card is tinted with. */
private val SelectedFill = BoardSignal.playable.copy(alpha = SELECTED_FILL_ALPHA)

private const val SELECTED_FILL_ALPHA = 0.55f

private val LifeSize = 44.dp
private val LifePadding = 6.dp

/** Thick enough to read across the table, since it is the only thing marking a player as a target. */
private val RingWidth = 3.dp
