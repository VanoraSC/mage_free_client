package magefree.feature.game.table

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import magefree.designsystem.board.BoardSignal
import magefree.designsystem.board.BoardSurface
import magefree.designsystem.board.BoardTypography
import magefree.designsystem.card.CounterPalette
import magefree.designsystem.card.counterDigitColor

/*
 * A player's vitals, collapsed to counts and colour.
 *
 * §7.15: *"The board is short of space and most of this is zero most of the time, so it earns its room
 * by asking for almost none until there is something to say."* So the strip carries what is always
 * true — life, the zone counts — and a chip for each counter that is not zero, and nothing else.
 *
 * **The colour is not a code.** §7.15 again: *"a way to tell two chips apart at a glance, not a code
 * the player is expected to learn — the number sits next to it, and the expanded view names it."* Life
 * is red because life is red everywhere; poison is green because it is the one other number that ends
 * a game; the rest take a colour from the board's own counter palette, which already hands out a
 * stable colour per kind so a counter never changes colour mid-game.
 */

/**
 * One player's vitals, collapsed.
 *
 * @param vitals the player, from [tableVitals].
 * @param palette the board's live counter palette, so a kind keeps one colour across the whole board.
 * @param onExpand opens the full list, or `null` for a strip that is only being read.
 * @param modifier the [Modifier] for the strip.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VitalsStrip(
    vitals: TableVitals,
    palette: CounterPalette,
    modifier: Modifier = Modifier,
    onExpand: (() -> Unit)? = null,
) {
    Column(
        modifier =
            modifier
                .background(BoardSurface.zone.copy(alpha = STRIP_OPACITY), StripShape)
                .let { base -> onExpand?.let { base.clickable(onClick = it) } ?: base }
                .padding(horizontal = ChipPadding, vertical = ChipPadding)
                .testTag(VitalsTestTags.strip(vitals.playerId)),
        verticalArrangement = Arrangement.spacedBy(RowGap),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // No name. Which seat a column belongs to is said by where it is — the opponent's at the top of
        // the rail, the viewer's at the bottom — and a label repeating that costs room the numbers
        // need. Priority is shown elsewhere.

        // Life gets the design system's own `vitals` token — *"the largest thing on the board that is
        // not a card"* — which is what it was defined for. It is on its own line at the top because it
        // is the number a player checks most and the only one they check from across the table.
        Chip(
            label = "${vitals.life}",
            fill = LifeColor,
            tag = VitalsTestTags.life(vitals.playerId),
            style = BoardTypography.vitals,
        )

        // The zone counts on one line under it, in the order a player reads them: what I am holding,
        // what has died, what I have left to draw, what is gone. Wrapped rather than clipped, because
        // the rail is one card wide and four numbers do not always fit across it.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(ChipGap, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(RowGap),
        ) {
            ZoneCount(label = "H", count = vitals.handCount, tag = VitalsTestTags.hand(vitals.playerId))
            ZoneCount(label = "G", count = vitals.graveyardCount, tag = VitalsTestTags.graveyard(vitals.playerId))
            // The library is a chip rather than a label: an empty one is a loss on the next draw, and
            // that is the only zone count that is itself a game state rather than a number.
            Chip(
                label = "L${vitals.libraryCount}",
                fill = if (vitals.isDecking) BoardSignal.threat else BoardSurface.zone,
                tag = VitalsTestTags.library(vitals.playerId),
                outlined = !vitals.isDecking,
            )
            ZoneCount(label = "X", count = vitals.exileCount, tag = VitalsTestTags.exile(vitals.playerId))
        }

        if (vitals.floatingMana > 0) {
            Chip(
                label = "${vitals.floatingMana}",
                fill = BoardSignal.pendingCost,
                tag = VitalsTestTags.mana(vitals.playerId),
            )
        }

        // **One counter per row, and the rows scroll.** A player with poison, energy and experience
        // has three numbers that each mean something different, and a row of bare circles makes them
        // a puzzle. Down the column each gets its own line; past what the rail can show, the column
        // scrolls rather than growing, because the rail's height belongs to the board.
        if (vitals.counters.isNotEmpty() || vitals.isMonarch || vitals.hasInitiative) {
            Column(
                modifier = Modifier.heightIn(max = CountersHeight).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(RowGap),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                vitals.counters.forEach { counter ->
                    Chip(
                        label = "${counter.count}",
                        fill = if (counter.isPoison) PoisonColor else palette.colorFor(counter.name),
                        tag = VitalsTestTags.counter(vitals.playerId, counter.name),
                        // The one thing the board says about a counter beyond how many: ten poison is
                        // a loss, so a player near it needs to know without doing arithmetic.
                        alarming = counter.isNearLethal,
                    )
                }

                if (vitals.isMonarch) Marker("Monarch", VitalsTestTags.monarch(vitals.playerId))
                if (vitals.hasInitiative) Marker("Initiative", VitalsTestTags.initiative(vitals.playerId))
            }
        }

        if (vitals.showsWins) {
            Text(
                text = "${vitals.wins}/${vitals.winsNeeded}",
                style = BoardTypography.cardStats,
                color = BoardSurface.onSurfaceMuted,
                maxLines = 1,
                modifier = Modifier.testTag(VitalsTestTags.wins(vitals.playerId)),
            )
        }
    }
}

/** A zone's count, which is only worth its room once there is something in it. */
@Composable
private fun ZoneCount(
    label: String,
    count: Int,
    tag: String,
) {
    if (count <= 0) return
    Text(
        text = "$label$count",
        style = BoardTypography.cardStats,
        color = BoardSurface.onSurfaceMuted,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier.testTag(tag),
    )
}

/** One number on a coloured ground, which is the whole collapsed vocabulary. */
@Composable
private fun Chip(
    label: String,
    fill: Color,
    tag: String,
    outlined: Boolean = false,
    alarming: Boolean = false,
    style: TextStyle = BoardTypography.cardStats,
) {
    Box(
        modifier =
            Modifier
                .background(if (outlined) Color.Transparent else fill, CircleShape)
                .let { base ->
                    when {
                        alarming -> base.border(AlarmBorder, BoardSignal.threat, CircleShape)
                        outlined -> base.border(ChipBorder, BoardSurface.onSurfaceMuted, CircleShape)
                        else -> base
                    }
                }.padding(horizontal = ChipPadding, vertical = ChipPadding / 2)
                .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = style,
            color = if (outlined) BoardSurface.onSurfaceMuted else counterDigitColor(fill),
        )
    }
}

/** A designation the player holds — a word, because there is no number to show. */
@Composable
private fun Marker(
    label: String,
    tag: String,
) {
    Text(
        text = label,
        style = BoardTypography.cardStats,
        color = BoardSignal.targeting,
        // One line or nothing. In a column a card wide, a wrapping word sets one letter per line,
        // which is how "Monarch" came out as a vertical stack of seven letters.
        maxLines = 1,
        softWrap = false,
        modifier = Modifier.testTag(tag),
    )
}

/** Test tags for the strip's parts, which are told apart by position rather than by text. */
object VitalsTestTags {
    fun strip(playerId: String): String = "vitals-$playerId"

    fun life(playerId: String): String = "vitals-life-$playerId"

    fun library(playerId: String): String = "vitals-library-$playerId"

    fun hand(playerId: String): String = "vitals-hand-$playerId"

    fun graveyard(playerId: String): String = "vitals-graveyard-$playerId"

    fun exile(playerId: String): String = "vitals-exile-$playerId"

    fun mana(playerId: String): String = "vitals-mana-$playerId"

    fun wins(playerId: String): String = "vitals-wins-$playerId"

    fun monarch(playerId: String): String = "vitals-monarch-$playerId"

    fun initiative(playerId: String): String = "vitals-initiative-$playerId"

    fun counter(
        playerId: String,
        name: String,
    ): String = "vitals-counter-$playerId-$name"
}

/** Life is red everywhere in Magic, and this is not the place to be original about it. */
private val LifeColor = Color(0xFFE05252)

/** Poison is green, and it is the only other number that ends a game on its own. */
private val PoisonColor = Color(0xFF6FBF73)

private val StripShape = RoundedCornerShape(4.dp)
private const val STRIP_OPACITY = 0.85f
private val StripPadding = 10.dp
private val ChipGap = 5.dp

/** Between the lines of the column. */
private val RowGap = 3.dp

/** As tall as the counters may get before they scroll — the rail's height belongs to the board. */
private val CountersHeight = 66.dp
private val ChipPadding = 7.dp
private val ChipBorder = 1.dp
private val AlarmBorder = 2.dp
