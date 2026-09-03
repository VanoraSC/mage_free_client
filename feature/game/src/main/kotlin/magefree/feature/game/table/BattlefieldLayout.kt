package magefree.feature.game.table

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import magefree.designsystem.board.BoardSurface
import magefree.designsystem.card.BoardCard
import magefree.designsystem.card.CARD_ASPECT_RATIO
import magefree.designsystem.card.CounterPalette
import magefree.designsystem.card.boardCardWidthFitting
import magefree.designsystem.card.rememberCounterPalette
import kotlin.math.ceil

/*
 * The battlefield, arranged as §7.4 describes it.
 *
 * ```
 *    ┌──────────────┬───────────────────────────────────┐
 *    │              │  [ other permanents ]             │  back
 *    │  [ lands ]   │  [ creatures ]                    │  front
 *    ├─ opponent ───┼───────────────────────────────────┤
 *    │  [ lands ]   │  [ creatures ]                    │  front
 *    │              │  [ other permanents ]             │  back
 *    └─ you ────────┴───────────────────────────────────┘
 * ```
 *
 * **Front means nearest the middle.** Creatures are what a player looks at — they attack, block and
 * change state constantly — so each side puts its creatures against the centre line, where the two
 * sides meet and where combat happens. Non-creature permanents sit behind them, further out.
 *
 * **Lands get a corner, not a row.** They are the most numerous permanents and the least individually
 * interesting, and §7.4 wants the space they take minimised. A zone in the outer corner does that in a
 * way a shared row cannot: it is bounded, so lands can never push the creatures off the board however
 * many of them there are, and it wraps within itself, so they grow *down* into their own quadrant
 * instead of *across* everyone else's. The two zones mirror across the centre line — the opponent's in
 * the top corner, the viewer's in the bottom.
 *
 * **Mirrored, not rotated.** Each side's rows run in the opposite vertical order so the creatures
 * face each other. The cards themselves are drawn the right way up: a player reads an opponent's board
 * constantly, and turning the text over to complete the metaphor would trade legibility for a picture
 * of a table.
 *
 * Rules that are easy to lose, and are therefore stated as code rather than intent:
 *
 * - **No empty region holds height.** A side with no lands has no land zone, not an empty one. Today's
 *   board reserves a fixed `StatusRailHeight` and `StackStripHeight` whether or not anything is in
 *   them, and an empty-but-present region is invisible in a screenshot of a full board.
 * - **No chrome.** No borders, banners, headers or rules between the regions. A region is identified
 *   by its position and its shade of grey.
 * - **A card has a size, and a quiet board does not make it bigger.** Every constraint can only take
 *   the size down from a preferred one.
 * - **Nothing sits against the edge of the screen.** A card in the corner is a card that is awkward to
 *   touch, and the board's own margin is cheaper than finding that out per device.
 */

/**
 * Both battlefields, arranged.
 *
 * @param model the two sides, from [battlefieldModel].
 * @param modifier the [Modifier] for the board.
 * @param artFor resolves a permanent's art from the printing the server named. Without it every card
 *   falls back to its placeholder and the arrangement is still exactly what it will be — but the
 *   arrangement is much harder to judge that way, because a board of grey rectangles hides whether a
 *   card is actually readable at the size it was given.
 * @param onInspect called with a permanent's id when its card is tapped, or `null` for a board that is
 *   only being looked at.
 */
@Composable
fun BattlefieldLayout(
    model: BattlefieldModel,
    modifier: Modifier = Modifier,
    artFor: TableArtResolver? = null,
    onInspect: ((String) -> Unit)? = null,
) {
    val palette = rememberCounterPalette()

    BoxWithConstraints(
        modifier =
            modifier
                .fillMaxSize()
                .background(BoardSurface.ground)
                .testTag(BattlefieldTestTags.BOARD),
    ) {
        val sides = model.opponents + listOfNotNull(model.viewer)
        val sideHeight = (maxHeight - BoardMargin * 2) / sides.size.coerceAtLeast(1)
        val landZoneWidth = (maxWidth - BoardMargin * 2) * LAND_ZONE_SHARE
        val mainWidth = maxWidth - BoardMargin * 2 - landZoneWidth - ZoneGap

        // One size for every land and one for everything else, shared across both sides: a creature on
        // the far side is the same size as one on this side, because the game does not say one of them
        // is nearer.
        val landWidth = landCardWidth(sides, landZoneWidth, sideHeight)
        val cardWidth = mainCardWidth(sides, mainWidth, sideHeight)

        Column(modifier = Modifier.fillMaxSize().padding(BoardMargin)) {
            model.opponents.forEach { side ->
                SideLayout(
                    side = side,
                    order = OpponentOrder,
                    landWidth = landWidth,
                    cardWidth = cardWidth,
                    landZoneWidth = landZoneWidth,
                    palette = palette,
                    artFor = artFor,
                    onInspect = onInspect,
                    modifier = Modifier.weight(1f),
                )
            }
            model.viewer?.let { side ->
                SideLayout(
                    side = side,
                    order = ViewerOrder,
                    landWidth = landWidth,
                    cardWidth = cardWidth,
                    landZoneWidth = landZoneWidth,
                    palette = palette,
                    artFor = artFor,
                    onInspect = onInspect,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** One player's half: the land corner, and everything else beside it. */
@Composable
private fun SideLayout(
    side: BattlefieldSide,
    order: List<BattlefieldRow>,
    landWidth: Dp,
    cardWidth: Dp,
    landZoneWidth: Dp,
    palette: CounterPalette,
    artFor: TableArtResolver?,
    onInspect: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    // The viewer's front row comes first, so packing to the top puts it against the middle; the
    // opponent's comes last, so theirs packs to the bottom. One rule, mirrored — and the land zone
    // packs the opposite way, into the outer corner, for the same reason.
    val isViewer = order === ViewerOrder
    val towardCentre = if (isViewer) Alignment.Top else Alignment.Bottom
    val towardCorner = if (isViewer) Alignment.BottomStart else Alignment.TopStart

    Row(
        modifier = modifier.fillMaxWidth().testTag(BattlefieldTestTags.side(side.playerId)),
        horizontalArrangement = Arrangement.spacedBy(ZoneGap),
    ) {
        val lands = side.pilesIn(PermanentRole.Land)
        // The rule, in the one place it can be broken: a side with no lands emits no zone, so the
        // corner costs nothing rather than holding an empty box.
        if (lands.isNotEmpty()) {
            LandZone(
                lands = lands,
                tag = BattlefieldTestTags.row(side.playerId, BattlefieldTestTags.LAND_ZONE),
                width = landWidth,
                alignment = towardCorner,
                palette = palette,
                artFor = artFor,
                onInspect = onInspect,
                modifier = Modifier.width(landZoneWidth).fillMaxHeight(),
            )
        }

        Column(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(RowGap, towardCentre),
        ) {
            order.forEach { row ->
                val content = side.inRole(row.role)
                if (content.isNotEmpty()) {
                    PermanentRow(
                        permanents = content,
                        tag = BattlefieldTestTags.row(side.playerId, row.name),
                        width = cardWidth,
                        palette = palette,
                        artFor = artFor,
                        onInspect = onInspect,
                    )
                }
            }
        }
    }
}

/**
 * The lands, wrapped into their corner.
 *
 * A wrapping grid rather than a row, because the zone is bounded: lands that overflowed sideways would
 * either push into the creatures or scroll out of sight, and a land you cannot see is a land you
 * cannot tap for mana. Growing downward into the side's own half is the one direction that costs
 * nobody else anything.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LandZone(
    lands: List<TablePile>,
    tag: String,
    width: Dp,
    alignment: Alignment,
    palette: CounterPalette,
    artFor: TableArtResolver?,
    onInspect: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier, contentAlignment = alignment) {
        FlowRow(
            modifier = Modifier.testTag(tag),
            horizontalArrangement = Arrangement.spacedBy(ZoneGap),
            verticalArrangement = Arrangement.spacedBy(CardGap),
        ) {
            lands.forEach { pile ->
                PileFan(
                    pile = pile,
                    width = width,
                    palette = palette,
                    artFor = artFor,
                    onInspect = onInspect,
                )
            }
        }
    }
}

/** One row of permanents, scrolling sideways when it cannot fit at the floor width. */
@Composable
private fun PermanentRow(
    permanents: List<TablePermanent>,
    tag: String,
    width: Dp,
    palette: CounterPalette,
    artFor: TableArtResolver?,
    onInspect: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()).testTag(tag),
            horizontalArrangement = Arrangement.spacedBy(CardGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            permanents.forEach { permanent ->
                PermanentCard(
                    permanent = permanent,
                    width = width,
                    palette = palette,
                    artFor = artFor,
                    onInspect = onInspect,
                )
            }
        }
    }
}

/** One permanent, drawn at [width]. */
@Composable
private fun PermanentCard(
    permanent: TablePermanent,
    width: Dp,
    palette: CounterPalette,
    artFor: TableArtResolver?,
    onInspect: ((String) -> Unit)?,
) {
    BoardCard(
        state = permanent.state,
        width = width,
        art = artFor?.invoke(permanent.art, permanent.state.card),
        onTap = onInspect?.let { inspect -> { inspect(permanent.id) } },
        counterPalette = palette,
    )
}

/**
 * How wide a land is drawn.
 *
 * The zone is a fixed box, so this is a packing question: for each number of columns the zone could
 * hold, the width is bounded both by the columns fitting across it and by the resulting rows fitting
 * down it. The best column count is whichever of those gives the largest card, and the answer is
 * capped at [PreferredCardWidth] — a board with two lands on it draws two ordinary lands, not two
 * lands the height of the battlefield.
 *
 * Trying every column count sounds wasteful and is not: there are never more columns than lands, the
 * arithmetic is four operations, and it runs once per snapshot.
 */
private fun landCardWidth(
    sides: List<BattlefieldSide>,
    zoneWidth: Dp,
    sideHeight: Dp,
): Dp {
    val piles = sides.map { it.pilesIn(PermanentRole.Land) }
    val most = piles.maxOfOrNull { it.size } ?: 0
    if (most == 0) return PreferredCardWidth

    // A stack is wider than a card by the faces behind the front one, so the packing is over stacks
    // rather than over lands — which is exactly why piling buys space: ten Plains is one stack here,
    // not ten. The widest stack stands in for all of them, so nothing overflows the zone.
    val widestPile = piles.flatten().maxOf { minOf(it.count, PILE_FAN_LIMIT) }

    var best = MinCardWidth
    for (columns in 1..most) {
        val rows = ceil(most.toFloat() / columns).toInt()
        val perColumn = (zoneWidth - ZoneGap * (columns - 1)) / columns
        val byWidth = perColumn - PileFanStep * (widestPile - 1)
        val byHeight = (sideHeight - CardGap * (rows - 1)) / rows * CARD_ASPECT_RATIO
        val fits = minOf(byWidth, byHeight)
        if (fits > best) best = fits
    }
    return minOf(best, PreferredCardWidth).coerceAtLeast(MinCardWidth)
}

/**
 * How wide everything that is not a land is drawn.
 *
 * Four constraints, and the smallest wins: the preferred size, the busiest row fitting across the main
 * area, the side's rows fitting down its half, and — the one that is easy to forget — a card carrying
 * attachments being *bigger than the card*, because upright attachments stack above the host so their
 * name bands show and turned ones reach out to the right. That last was a shipped bug in 0100 and it
 * shows only on the one board that has an Aura on it.
 *
 * Floored at [MinCardWidth]: below it a card stops being readable, which defeats the purpose of
 * shrinking it, so the row scrolls instead — the one place the board admits it has run out of space.
 */
private fun mainCardWidth(
    sides: List<BattlefieldSide>,
    mainWidth: Dp,
    sideHeight: Dp,
): Dp {
    var busiest = 0
    var tallest = 0
    sides.forEach { side ->
        var populated = 0
        ViewerOrder.forEach { row ->
            val count = side.inRole(row.role).size
            if (count > 0) populated += 1
            if (count > busiest) busiest = count
        }
        if (populated > tallest) tallest = populated
    }
    if (busiest == 0) return PreferredCardWidth

    val byWidth = (mainWidth - CardGap * (busiest - 1)) / busiest
    val rowHeight = (sideHeight - RowGap * (tallest - 1)) / tallest.coerceAtLeast(1)
    val byHeight = rowHeight * CARD_ASPECT_RATIO
    val plain = minOf(PreferredCardWidth, byWidth, byHeight)

    val byAssembly =
        sides
            .flatMap { it.permanents }
            .filter { it.role != PermanentRole.Land && it.state.attachments.isNotEmpty() }
            .minOfOrNull { boardCardWidthFitting(it.state, maxWidth = plain, maxHeight = rowHeight) }
            ?: plain

    return minOf(plain, byAssembly).coerceAtLeast(MinCardWidth)
}

/** One row of a side's main area, and which bucket feeds it. */
private data class BattlefieldRow(
    val name: String,
    val role: PermanentRole,
)

private val FrontRow = BattlefieldRow(name = "front", role = PermanentRole.Creature)
private val BackRow = BattlefieldRow(name = "back", role = PermanentRole.Other)

/** The viewer reads bottom-up: their creatures sit against the centre line, above the rest. */
private val ViewerOrder = listOf(FrontRow, BackRow)

/** Mirrored: the opponent's other permanents are furthest away and their creatures face yours. */
private val OpponentOrder = listOf(BackRow, FrontRow)

/** Test tags for the regions, which carry no distinctive text of their own. */
object BattlefieldTestTags {
    const val BOARD: String = "battlefield"

    /** The land corner's own row name, for [row]. */
    const val LAND_ZONE: String = "lands"

    /** One player's half. */
    fun side(playerId: String): String = "battlefield-side-$playerId"

    /**
     * One region of one half — `front`, `back` or [LAND_ZONE]. Absent entirely when it is empty.
     */
    fun row(
        playerId: String,
        row: String,
    ): String = "battlefield-row-$playerId-$row"

    /** One stack, by the id an action on it would name. */
    fun pile(actionId: String): String = "battlefield-pile-$actionId"

    /** A stack's count badge, present only past [PILE_FAN_LIMIT]. */
    fun pileCount(actionId: String): String = "battlefield-pile-count-$actionId"
}

/**
 * How much of the board's width the land corner may take.
 *
 * A ceiling on the least interesting permanents, which is §7.4's whole point about them. It is not a
 * reservation: a side with no lands draws no zone at all, and the creatures get the width back.
 */
private const val LAND_ZONE_SHARE = 0.28f

/**
 * The size a card is drawn at when the board has room for it.
 *
 * A ceiling, not a target: it is what a quiet board looks like, and every other constraint can only
 * take it down.
 */
private val PreferredCardWidth = 112.dp

/** Below this a card stops being readable, so the row scrolls rather than shrinking further. */
private val MinCardWidth = 44.dp

/** Space around the whole board, because a card against the screen edge is awkward to touch. */
private val BoardMargin = 12.dp

private val ZoneGap = 8.dp
private val CardGap = 3.dp
private val RowGap = 3.dp
