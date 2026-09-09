package magefree.feature.game.table

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import magefree.designsystem.board.BoardSignal
import magefree.network.game.CombatGroup
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/*
 * What is pointing at what.
 *
 * **Only for objects the board is actually drawing.** A spell can target a card in a graveyard or a
 * player, and an arrow to where that card *would* be is worse than no arrow at all — it points at
 * something else. The detail view names every target in full, which is the answer for the ones that
 * cannot be pointed at.
 *
 * **Drawn between edges, not between centres.** A line from the middle of one card to the middle of
 * another disappears under both of them and reads as two cards with a smear between them. Each end is
 * pulled back to where the line crosses that card's own box, so the arrow starts and finishes in the
 * open.
 *
 * **One colour, the targeting one.** It is the same signal a targeted card's own border carries, so
 * the arrow and the highlight it lands on are visibly the same statement.
 */

/**
 * The arrows from every stack object to every target the board is drawing.
 *
 * @param stack the objects, from [tableStack].
 * @param anchors where each object is drawn, from the board's own measurements.
 * @param modifier the [Modifier] for the overlay, which must fill the same box the anchors are
 *   measured against.
 */
@Composable
internal fun TargetArrows(
    stack: List<TableStackObject>,
    anchors: BoardAnchors,
    modifier: Modifier = Modifier,
    combat: List<CombatGroup> = emptyList(),
) {
    val arrows =
        stack.flatMap { entry ->
            val from = anchors.boxOf(entry.id) ?: return@flatMap emptyList()
            entry.targetIds.mapNotNull { targetId ->
                anchors.boxOf(targetId)?.let { Arrow(from = from, to = it, color = ArrowColor) }
            }
        } + combatArrows(combat, anchors)
    if (arrows.isEmpty()) return

    Canvas(modifier = modifier) {
        arrows.forEach { arrow -> drawArrow(from = arrow.from, to = arrow.to, color = arrow.color) }
    }
}

/** One arrow: two measured boxes and what it means. */
private data class Arrow(
    val from: Rect,
    val to: Rect,
    val color: Color,
)

/**
 * The arrows combat itself draws: attackers at what they are attacking, blockers at what they block.
 *
 * **Combat is the one thing on this board that is a set of *relationships*.** Who is attacking is a
 * border; who they are attacking, and which blocker is on which attacker, is a pairing, and a pairing
 * has no honest representation on a card. `GameState.combat` is upstream's own `CombatGroup` list, so
 * every line here is a pairing the server has already made.
 *
 * **Attacking is red and blocking is blue**, which is the board's existing vocabulary — the same two
 * colours the cards' own borders carry for the same two facts, so a line and the card it leaves agree.
 *
 * A defender the board cannot draw has no arrow. In practice it always can: a planeswalker is a
 * permanent, and a player is their life total, which is anchored for exactly this.
 */
private fun combatArrows(
    combat: List<CombatGroup>,
    anchors: BoardAnchors,
): List<Arrow> =
    combat.flatMap { group ->
        val defender = group.defenderId?.let(anchors::boxOf)
        group.attackerIds.flatMap { attackerId ->
            val attacker = anchors.boxOf(attackerId) ?: return@flatMap emptyList()
            val attacking = defender?.let { Arrow(from = attacker, to = it, color = AttackColor) }
            // Blocker → attacker, because the blocker is the one that chose. It is also the direction
            // a player reads it in: *that* creature is stopping *this* one.
            val blocking =
                group.blockerIds.mapNotNull { blockerId ->
                    anchors.boxOf(blockerId)?.let { Arrow(from = it, to = attacker, color = BlockColor) }
                }
            listOfNotNull(attacking) + blocking
        }
    }

/**
 * One arrow, from the edge of [from] to the edge of [to].
 *
 * The head is drawn as two strokes rather than a filled path: at this weight a filled triangle reads
 * as a blob, and two lines at the same width as the shaft read as one continuous mark.
 */
private fun DrawScope.drawArrow(
    from: Rect,
    to: Rect,
    color: Color,
) {
    val start = edgePoint(from, towards = to.center)
    val end = edgePoint(to, towards = from.center)
    if (hypot(end.x - start.x, end.y - start.y) < MIN_ARROW_PX) return

    val stroke = Stroke(width = ArrowWidth.toPx())
    drawLine(color = color, start = start, end = end, strokeWidth = stroke.width)

    val angle = atan2(end.y - start.y, end.x - start.x)
    val head = ArrowHead.toPx()
    listOf(angle + HEAD_SPREAD, angle - HEAD_SPREAD).forEach { barb ->
        drawLine(
            color = color,
            start = end,
            end = Offset(end.x - head * cos(barb), end.y - head * sin(barb)),
            strokeWidth = stroke.width,
        )
    }
}

/**
 * Where the line to [towards] crosses [box]'s own edge.
 *
 * Worked out by scaling the direction until it meets whichever side it reaches first, which is one
 * division rather than four intersection tests, and is exact for a rectangle.
 */
private fun edgePoint(
    box: Rect,
    towards: Offset,
): Offset {
    val dx = towards.x - box.center.x
    val dy = towards.y - box.center.y
    if (dx == 0f && dy == 0f) return box.center
    val scale =
        minOf(
            if (dx == 0f) Float.MAX_VALUE else (box.width / 2f) / kotlin.math.abs(dx),
            if (dy == 0f) Float.MAX_VALUE else (box.height / 2f) / kotlin.math.abs(dy),
        )
    return Offset(box.center.x + dx * scale, box.center.y + dy * scale)
}

/** The same colour a targeted card's own border carries, so the arrow and the highlight agree. */
private val ArrowColor: Color = BoardSignal.targeting

/** The same red an attacking creature's own border carries. */
private val AttackColor: Color = BoardSignal.attacking

/** The same blue a blocking creature's own border carries. */
private val BlockColor: Color = BoardSignal.blocking

private val ArrowWidth: Dp = 3.dp

private val ArrowHead: Dp = 14.dp

/** How far each barb of the head opens from the shaft, in radians — about thirty degrees. */
private const val HEAD_SPREAD = 0.52f

/**
 * Below this, an arrow is not drawn at all.
 *
 * Two boxes that overlap produce a line shorter than its own arrowhead, which draws as a scribble.
 * The case is real: a stack object can be laid out next to something it targets.
 */
private const val MIN_ARROW_PX = 24f
