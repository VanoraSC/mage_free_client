package magefree.designsystem.card

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import magefree.designsystem.board.BoardSignal
import magefree.designsystem.board.BoardSurface
import magefree.designsystem.board.BoardTypography

/*
 * The Board tier: a card as it appears on a battlefield or in a stack pile.
 *
 * This is the smallest of the three card tiers and the one every permanent is drawn with, so it shows
 * only what has to be readable without inspecting: the name, power and toughness, the counters on the
 * card, whether it is tapped, and what the game is currently signalling about it. Cost, type line and
 * rules text belong to the larger tiers.
 *
 * It computes nothing about the game. Every value here is state the server already decided and sent,
 * and the component renders what it is handed — the same rule the rest of the client runs on, and why
 * this type carries no derived fields.
 */

/** One counter on a card's face. The kind is an open set upstream, so it stays a string. */
data class BoardCounter(
    val name: String,
    val count: Int,
)

/**
 * What the game is signalling about a card right now.
 *
 * **Declaration order is precedence**, highest first, and [primarySignal] reads it. A card can carry
 * several at once — a creature that is attacking and has just been targeted by removal carries two —
 * so the order decides which claims the card's border. It runs from the most immediate to the least:
 * being targeted is about to happen, combat is already assigned, and being playable is an affordance
 * rather than an event.
 */
enum class BoardCardSignal {
    /** Something on the stack is targeting this. */
    Targeted,

    /** This is dangerous to the player: lethal on board, an unanswered threat. */
    Threat,

    /** This creature is attacking. */
    Attacking,

    /** This creature is blocking. */
    Blocking,

    /** A cost being assembled will consume this. */
    PendingCost,

    /** This can be played, cast or activated right now. */
    Playable,
    ;

    /** The information colour this signal is drawn in. */
    val color: Color
        get() =
            when (this) {
                Targeted -> BoardSignal.targeting
                Threat -> BoardSignal.threat
                Attacking -> BoardSignal.attacking
                Blocking -> BoardSignal.blocking
                PendingCost -> BoardSignal.pendingCost
                Playable -> BoardSignal.playable
            }
}

/**
 * The signal that claims the card's border: the highest-precedence one present, or null for none.
 *
 * Pure logic, so the rule is testable without rendering anything and a later change to the precedence
 * is a change to [BoardCardSignal]'s declaration order rather than to layout code.
 */
fun primarySignal(signals: Set<BoardCardSignal>): BoardCardSignal? = BoardCardSignal.entries.firstOrNull { it in signals }

/**
 * A permanent's board-tier state. Everything here is server-supplied.
 *
 * @param card the display fields shared by all three tiers.
 * @param power the current power, already resolved by the server; null for a non-creature.
 * @param toughness the current toughness; null for a non-creature.
 * @param counters the counters on the card, in the order the server sent them.
 * @param tapped whether the permanent is tapped, which rotates it a quarter turn.
 * @param signals everything the game is signalling about this card at once.
 */
data class BoardCardState(
    val card: CardDisplay,
    val power: String? = null,
    val toughness: String? = null,
    val counters: List<BoardCounter> = emptyList(),
    val tapped: Boolean = false,
    val signals: Set<BoardCardSignal> = emptySet(),
)

/**
 * A card at Board size.
 *
 * **Tapping rotates the card rather than badging it** — the universal Magic idiom, and cheaper to read
 * than a symbol. Rotation is not only paint: a tapped permanent occupies a landscape footprint where an
 * untapped one occupies a portrait one, and this component owns that. It derives its whole footprint
 * from [width] and swaps its own dimensions when tapped, so a caller never has to know that a tapped
 * card is a different shape from an untapped one.
 *
 * @param state everything the game says about this card.
 * @param width the card's width when untapped; the footprint derives from it.
 * @param modifier the [Modifier] for the card.
 * @param art the card-art slot; the built-in placeholder is used when none is supplied. At this tier
 *   the slot is asked for art cropped to the card's art box rather than a whole card face.
 * @param onTap invoked when the card is tapped; null makes the card non-interactive.
 */
@Composable
fun BoardCard(
    state: BoardCardState,
    width: Dp,
    modifier: Modifier = Modifier,
    art: CardArtSlot? = null,
    onTap: (() -> Unit)? = null,
) {
    val height = width / CARD_ASPECT_RATIO
    val primary = primarySignal(state.signals)

    // The footprint is the rotated one when tapped, while the card inside keeps its portrait
    // dimensions and turns within that box. Sizing the box the other way round is what makes a tapped
    // permanent take the space it visually occupies instead of overlapping its neighbours.
    Box(
        modifier =
            modifier.size(
                width = if (state.tapped) height else width,
                height = if (state.tapped) width else height,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .size(width = width, height = height)
                    .graphicsLayer { rotationZ = if (state.tapped) TAPPED_ROTATION_DEGREES else 0f }
                    .clip(BoardCardShape)
                    .background(BoardSurface.card)
                    .border(
                        width = if (primary != null) SignalBorderWidth else RestingBorderWidth,
                        color = primary?.color ?: BoardSurface.zoneRaised,
                        shape = BoardCardShape,
                    ).let { base -> if (onTap != null) base.cardInspectable(onTap = onTap) else base }
                    .testTag(BoardCardTestTags.CARD),
        ) {
            CardArtRegion(card = state.card, art = art, modifier = Modifier.fillMaxSize())

            // The name sits on its own band rather than directly on art: art is arbitrary, and a name
            // that is only sometimes readable is worse than one that always is.
            Text(
                text = state.card.name,
                style = BoardTypography.cardName,
                color = BoardSurface.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier =
                    Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .background(BoardSurface.zone.copy(alpha = BAND_OPACITY))
                        .padding(horizontal = BoardCardPadding),
            )

            if (state.counters.isNotEmpty()) {
                CounterStrip(
                    counters = state.counters,
                    modifier = Modifier.align(Alignment.BottomStart).padding(BoardCardPadding),
                )
            }

            boardStatsLabel(state.power, state.toughness)?.let { stats ->
                Text(
                    text = stats,
                    style = BoardTypography.cardStats,
                    color = BoardSurface.onSurface,
                    modifier =
                        Modifier
                            .align(Alignment.BottomEnd)
                            .padding(BoardCardPadding)
                            .background(BoardSurface.zone.copy(alpha = BAND_OPACITY), BoardCardShape)
                            .padding(horizontal = BoardCardPadding)
                            .testTag(BoardCardTestTags.STATS),
                )
            }

            // Only the highest-precedence signal claims the border, so every other one would be
            // invisible without these. One pip per signal, in precedence order, so nothing is lost.
            if (state.signals.size > 1) {
                SignalPips(
                    signals = state.signals,
                    modifier = Modifier.align(Alignment.TopStart).padding(BoardCardPadding),
                )
            }
        }
    }
}

/** The counters on the face, capped so a heavily-countered permanent still shows a card. */
@Composable
private fun CounterStrip(
    counters: List<BoardCounter>,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.testTag(BoardCardTestTags.COUNTERS),
        horizontalArrangement = Arrangement.spacedBy(PipSpacing),
    ) {
        counters.take(MAX_VISIBLE_COUNTERS).forEach { counter ->
            Text(
                text = counterLabel(counter),
                style = BoardTypography.counter,
                color = BoardSurface.onSurface,
                maxLines = 1,
                modifier =
                    Modifier
                        .background(BoardSurface.cardRaised, BoardCardShape)
                        .padding(horizontal = BoardCardPadding),
            )
        }
        val hidden = counters.size - MAX_VISIBLE_COUNTERS
        if (hidden > 0) {
            Text(
                text = "+$hidden",
                style = BoardTypography.counter,
                color = BoardSurface.onSurfaceMuted,
                modifier =
                    Modifier
                        .background(BoardSurface.cardRaised, BoardCardShape)
                        .padding(horizontal = BoardCardPadding),
            )
        }
    }
}

/** One pip per active signal, in precedence order. */
@Composable
private fun SignalPips(
    signals: Set<BoardCardSignal>,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.testTag(BoardCardTestTags.SIGNAL_PIPS),
        horizontalArrangement = Arrangement.spacedBy(PipSpacing),
    ) {
        BoardCardSignal.entries.filter { it in signals }.forEach { signal ->
            Box(modifier = Modifier.size(SignalPipSize).background(signal.color, CircleShape))
        }
    }
}

/**
 * The label for a counter. The kind is an open set — poison, energy, experience, loyalty and hundreds
 * more — so a name this build has never heard of still renders as itself rather than being dropped.
 */
internal fun counterLabel(counter: BoardCounter): String = "${counter.name} ${counter.count}"

/**
 * The power/toughness label, or null when the card has neither. A card carrying only one of the two is
 * a server state this client does not get to interpret, so the missing half renders as a dash rather
 * than suppressing the whole label.
 */
internal fun boardStatsLabel(
    power: String?,
    toughness: String?,
): String? =
    if (power == null && toughness == null) {
        null
    } else {
        "${power ?: "-"}/${toughness ?: "-"}"
    }

/** Test tags for the parts of the card that carry no text of their own. */
object BoardCardTestTags {
    const val CARD: String = "board-card"
    const val STATS: String = "board-card-stats"
    const val COUNTERS: String = "board-card-counters"
    const val SIGNAL_PIPS: String = "board-card-signal-pips"
}

/** A tapped permanent is turned a quarter turn, as on a physical table. */
private const val TAPPED_ROTATION_DEGREES = 90f

/** How opaque the name band and the stats backing are over arbitrary art. */
private const val BAND_OPACITY = 0.82f

/** Counters shown individually before the rest collapse into a count. */
private const val MAX_VISIBLE_COUNTERS = 3

private val BoardCardShape = RoundedCornerShape(3.dp)
private val BoardCardPadding = 2.dp
private val PipSpacing = 1.dp
private val SignalBorderWidth = 2.dp
private val RestingBorderWidth = 1.dp
private val SignalPipSize = 5.dp
