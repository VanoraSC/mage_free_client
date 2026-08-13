package magefree.feature.game.board

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import magefree.designsystem.theme.Elevation
import magefree.designsystem.theme.Spacing
import magefree.feature.cards.CardArtRenderer

/*
 * The board's regions (story 0055), each with a **defined empty state** — the board is never guaranteed
 * to arrive populated (requirements §1.2): the first snapshot after joining legitimately has an empty
 * hand, and the stack, combat and revealed zones are routinely empty.
 */

/** Height of a seat's vitals bar. */
internal val VitalsBarHeight: Dp = 44.dp

/** Height of the status rail — **fixed**, see [StatusRail]. */
internal val StatusRailHeight: Dp = 132.dp

/** Height of the stack strip inside the rail — also fixed, for the same reason. */
internal val StackStripHeight: Dp = 56.dp

/** Height of the collapsed hand's peek edge. */
internal val HandPeekHeight: Dp = 64.dp

/**
 * A seat's compact vitals bar (requirements §4.3): life prominent, zone counts small, in a strip whose
 * position never moves however full the battlefield gets.
 *
 * Empty state: a seat that has not arrived yet renders [NO_SEAT_LABEL] rather than a bar of zeroes,
 * because a real player on 0 life is a different thing from a player we have not been told about.
 */
@Composable
internal fun SeatVitalsBar(
    seat: SeatUi?,
    fallbackLabel: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth().height(VitalsBarHeight),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        if (seat == null) {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = Spacing.medium),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "$fallbackLabel: $NO_SEAT_LABEL",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            return@Surface
        }
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = Spacing.medium),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            Text(
                text = "${seat.life}",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Text(
                text = seat.name,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (seat.isActive) {
                Text(
                    text = ACTIVE_SEAT_MARK,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                )
            }
            if (seat.hasLeft) {
                Text(
                    text = LEFT_SEAT_MARK,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 1,
                )
            }
            Text(
                text = seat.zoneCountsLabel(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
    }
}

/**
 * The zone counts a seat shows, small: library / hand / graveyard / exile, floating mana when there is
 * any, and the match score for a real best-of-N.
 *
 * `exileCount` is safe to read directly here: the bridge maps it from `PlayerView.exile.size`, a set of
 * **cards**. The zone-list caveat applies to `GameState.exile`, which [BoardUi.exiledCardCount] counts
 * by cards instead.
 */
internal fun SeatUi.zoneCountsLabel(): String =
    buildList {
        add("$LIBRARY_LABEL $libraryCount")
        add("$HAND_LABEL $handCount")
        add("$GRAVEYARD_LABEL $graveyardCount")
        add("$EXILE_LABEL $exileCount")
        if (floatingMana > 0) add("$MANA_LABEL $floatingMana")
        matchScore?.let { add("$WINS_LABEL $it") }
    }.joinToString(" · ")

/**
 * One player's battlefield band.
 *
 * Empty state: [EMPTY_BATTLEFIELD] — the normal state of both battlefields on turn one, and of the
 * opponent's for as long as they play nothing.
 */
@Composable
internal fun BattlefieldBand(
    seat: SeatUi?,
    artRenderer: CardArtRenderer,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        val permanents = seat?.battlefield.orEmpty()
        if (permanents.isEmpty()) {
            Text(
                text = EMPTY_BATTLEFIELD,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = Spacing.small, vertical = Spacing.extraSmall),
                horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                permanents.forEach { permanent ->
                    key(permanent.objectId) {
                        PermanentCard(permanent = permanent, artRenderer = artRenderer)
                    }
                }
            }
        }
    }
}

/**
 * **The portrait re-shape of requirements §4.1's side panel.**
 *
 * §4.1 put the stack and phase indicator in a vertical panel down one edge, and said why: on a *short
 * landscape* screen that was the only way to keep both battlefields full-height. Portrait (§16.1)
 * inverts the scarcity — width is now the plentiful axis and height the scarce one — so an edge panel
 * would tax **both** battlefields' width permanently, for a region that is empty most of the game.
 *
 * So the stack, the turn/phase readout and the explicit priority banner share one **horizontal band
 * between the two battlefields**. That is where a physical table puts the stack, which §4.1 wanted and
 * could not afford; it costs a slice of the scarce axis exactly once instead of taxing the plentiful
 * one twice; and it puts the priority statement on the centre line, where the eye already is.
 *
 * **Fixed height, always.** [StatusRailHeight] does not depend on what is in the rail, and the stack
 * strip inside it has its own fixed [StackStripHeight]. The stack is empty in the common case and fills
 * abruptly (verified live), and §4.1's surviving requirement is that filling it must **not reflow the
 * battlefields**. Reserving the space unconditionally is what guarantees that; the empty strip carries
 * [EMPTY_STACK] rather than collapsing.
 */
@Composable
internal fun StatusRail(
    board: BoardUi,
    artRenderer: CardArtRenderer,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth().height(StatusRailHeight),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = Elevation.level2,
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = Spacing.medium, vertical = Spacing.extraSmall)) {
            TurnLine(turn = board.turn, clock = board.clock)
            PriorityBanner(priority = board.priority)
            StackStrip(stack = board.stack, artRenderer = artRenderer)
        }
    }
}

/** Turn number, phase/step and whose turn it is — plus the clock, but only on a table that has one. */
@Composable
private fun TurnLine(
    turn: TurnUi,
    clock: ClockUi?,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val whose =
            when {
                turn.isViewerTurn -> YOUR_TURN_LABEL
                turn.activePlayerName != null -> "${turn.activePlayerName}$OPPONENT_TURN_SUFFIX"
                else -> UNKNOWN_TURN_OWNER
            }
        Text(
            text = "${turn.turnLabel} · ${turn.phaseLabel} · $whose",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        // Absent entirely on an untimed table, where the server reports 0 (never "0:00 left").
        clock?.let {
            Text(
                text = it.label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/**
 * Priority, stated in words (requirements §4.2). Driven by `viewerHasPriority` alone, so it is right in
 * the state a card highlight cannot express: holding priority with nothing playable.
 */
@Composable
private fun PriorityBanner(priority: PriorityUi) {
    Column(modifier = Modifier.fillMaxWidth().heightIn(min = PRIORITY_BANNER_MIN_HEIGHT)) {
        Text(
            text = priority.headline,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color =
                when (priority) {
                    is PriorityUi.Yours, PriorityUi.Asked -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            maxLines = 1,
        )
        priority.detail?.let { detail ->
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The stack, top first.
 *
 * Empty state: [EMPTY_STACK], in a strip of exactly the same height as a full one — see [StatusRail].
 *
 * The caption says [STACK_AS_PUSHED] whenever the stack has anything in it, because it is **not**
 * authoritative between pushes: the server does not push the opponent a snapshot when a cast is
 * cancelled (requirements §17.4, verified by card id), so an object here can be one its caster has
 * already rewound.
 */
@Composable
private fun StackStrip(
    stack: StackUi,
    artRenderer: CardArtRenderer,
) {
    Column(modifier = Modifier.fillMaxWidth().height(StackStripHeight)) {
        Text(
            text = if (stack.isEmpty) "$STACK_LABEL · $EMPTY_STACK" else "$STACK_LABEL (${stack.count}) · $STACK_AS_PUSHED",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(Spacing.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            stack.entries.forEach { entry ->
                key(entry.objectId) {
                    StackCard(entry = entry, artRenderer = artRenderer, modifier = Modifier.fillMaxHeight())
                }
            }
        }
    }
}

/**
 * The hand's **peek** edge (requirements §3.2): a slim strip of card backs and a count, which expands
 * over the board on tap.
 *
 * Empty state: [EMPTY_HAND]. This is not an edge case — the very first snapshot after joining
 * legitimately has an empty hand, and the dealt cards arrive in a later push, so the peek form has to
 * read correctly with nothing behind it.
 */
@Composable
internal fun HandPeek(
    hand: HandUi,
    expanded: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth().height(HandPeekHeight),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = Spacing.medium),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            Text(
                text = if (hand.isEmpty) EMPTY_HAND else "$HAND_PEEK_PREFIX ${hand.count}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                maxLines = 1,
            )
            Row(
                modifier = Modifier.weight(1f).fillMaxHeight().padding(vertical = Spacing.extraSmall),
                horizontalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
                verticalAlignment = Alignment.Bottom,
            ) {
                repeat(hand.count.coerceAtMost(MAX_PEEK_EDGES)) {
                    HandPeekEdge(modifier = Modifier.fillMaxHeight())
                }
            }
            Text(
                text = if (expanded) HAND_COLLAPSE_LABEL else HAND_EXPAND_LABEL,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                maxLines = 1,
            )
        }
    }
}

/**
 * The expanded hand, drawn **over** the board rather than pushing it around — which is what lets the
 * battlefields keep their height in portrait, where height is the scarce axis (§16.1).
 */
@Composable
internal fun ExpandedHand(
    hand: HandUi,
    artRenderer: CardArtRenderer,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = Elevation.level4,
    ) {
        if (hand.isEmpty) {
            Box(
                modifier = Modifier.fillMaxWidth().height(EXPANDED_HAND_EMPTY_HEIGHT),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = EMPTY_HAND_EXPANDED,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(Spacing.small),
                horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                verticalAlignment = Alignment.Bottom,
            ) {
                hand.cards.forEach { handCard ->
                    key(handCard.objectId) {
                        HandCard(handCard = handCard, artRenderer = artRenderer)
                    }
                }
            }
        }
    }
}

/**
 * The notice strip: the server's outstanding question (rendered for comprehension, **never**
 * answerable — that is story 0056), its latest narration, any sticky error, the exile summary, and the
 * end-of-game line.
 *
 * Empty state: [NO_NOTICES] — the ordinary state of a quiet board.
 *
 * Everything here has been through [stripServerMarkup]: the server's log is HTML and raw markup must
 * never reach the screen.
 */
@Composable
internal fun NoticeStrip(
    board: BoardUi,
    modifier: Modifier = Modifier,
) {
    val lines =
        buildList {
            board.resultNotice?.let { add(it) }
            board.promptNotice?.let { add("$PROMPT_PREFIX $it") }
            board.errorNotice?.let { add(it) }
            board.narration?.let { add(it) }
            add(if (board.hasExiledCards) "$EXILE_LABEL ${board.exiledCardCount}" else EXILE_EMPTY)
        }
    Surface(
        modifier = modifier.fillMaxWidth().height(NOTICE_STRIP_HEIGHT),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = Spacing.medium, vertical = Spacing.extraSmall),
            verticalArrangement = Arrangement.Center,
        ) {
            if (lines.isEmpty()) {
                Text(
                    text = NO_NOTICES,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            } else {
                lines.take(MAX_NOTICE_LINES).forEach { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private val PRIORITY_BANNER_MIN_HEIGHT: Dp = 40.dp
private val EXPANDED_HAND_EMPTY_HEIGHT: Dp = 96.dp

/** Height of the notice strip — fixed, so a prompt arriving cannot reflow the board either. */
internal val NOTICE_STRIP_HEIGHT: Dp = 56.dp

/** How many card edges the peek strip draws before it stops adding more. */
private const val MAX_PEEK_EDGES = 7

/** How many notice lines fit the fixed strip. */
private const val MAX_NOTICE_LINES = 3
