package magefree.feature.game.table

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import magefree.designsystem.card.CardArtSlot
import magefree.designsystem.card.CounterPalette
import magefree.designsystem.card.boardCardWidthFitting
import magefree.designsystem.card.rememberCounterPalette

/*
 * The battlefield, arranged as §7.4 describes it.
 *
 * ```
 *    ┌─ opponent's battlefield (mirrored) ──────────────┐
 *    │  [ lands ]        [ other permanents ]           │   back
 *    │            [ creatures ]                         │   front
 *    ├──────────────────────────────────────────────────┤
 *    │            [ creatures ]                         │   front
 *    │  [ lands ]        [ other permanents ]           │   back
 *    └─ your battlefield ───────────────────────────────┘
 * ```
 *
 * **Front means nearest the middle.** Creatures are what a player looks at — they attack, block and
 * change state constantly — so each side puts its creatures against the centre line, where the two
 * sides meet and where combat happens. The back row holds the lands and everything else, present and
 * readable but not competing with what is about to matter.
 *
 * **Mirrored, not rotated.** The opponent's rows run in the opposite vertical order so their creatures
 * face yours. Their cards are drawn the right way up: a player reads an opponent's board constantly,
 * and turning the text upside down to complete the metaphor would make it unreadable to serve a
 * picture of a table.
 *
 * Three rules from §7.4 are easy to lose and are therefore stated as code rather than intent:
 *
 * - **No empty region holds height.** A side with no lands has no land row, not an empty one. This is
 *   what pays for the card size; today's board reserves a fixed `StatusRailHeight` and
 *   `StackStripHeight` whether or not anything is in them.
 * - **No chrome.** No borders, banners, headers or rules between the regions. A region is identified
 *   by its position and its shade of grey.
 * - **The size is derived.** Card width comes from the busiest row, floored at a legibility minimum,
 *   and a row that cannot fit at the floor scrolls instead of shrinking further.
 */

/**
 * Both battlefields, arranged.
 *
 * @param model the two sides, from [battlefieldModel].
 * @param modifier the [Modifier] for the board.
 * @param artFor resolves card art; without it every card falls back to its placeholder and the
 *   arrangement is still exactly what it will be.
 * @param onInspect called with a permanent's id when its card is tapped, or `null` for a board that
 *   is only being looked at.
 */
@Composable
fun BattlefieldLayout(
    model: BattlefieldModel,
    modifier: Modifier = Modifier,
    artFor: ((String) -> CardArtSlot?)? = null,
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
        // Every side shares one card width, so a creature on the far side is the same size as one on
        // this side. Sizing each half independently would make the opponent's board read as nearer or
        // further away, which is a distinction the game does not have.
        val rows = sideRows(model)
        val width = cardWidth(model = model, available = maxWidth, height = maxHeight, rows = rows)

        Column(modifier = Modifier.fillMaxSize()) {
            model.opponents.forEach { side ->
                SideRows(
                    side = side,
                    order = OpponentOrder,
                    width = width,
                    palette = palette,
                    artFor = artFor,
                    onInspect = onInspect,
                    modifier = Modifier.weight(1f),
                )
            }
            model.viewer?.let { side ->
                SideRows(
                    side = side,
                    order = ViewerOrder,
                    width = width,
                    palette = palette,
                    artFor = artFor,
                    onInspect = onInspect,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * One side's rows, in [order] from top to bottom, packed against the centre line.
 *
 * **The rows take the height they need and no more.** They used to share the side's height equally,
 * which is the same mistake as sizing a card to its space: with a fixed card size that leaves a band
 * of empty grey between the two rows and pushes them apart for no reason the game gives. Packing them
 * toward the middle instead means the two front rows meet there, and whatever is left over falls at
 * the outside edges where nothing needs it.
 */
@Composable
private fun SideRows(
    side: BattlefieldSide,
    order: List<BattlefieldRow>,
    width: Dp,
    palette: CounterPalette,
    artFor: ((String) -> CardArtSlot?)?,
    onInspect: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    // The viewer's front row is first, so packing to the top puts it against the middle; the
    // opponent's is last, so theirs packs to the bottom. Same rule, mirrored.
    val towardCentre = if (order === ViewerOrder) Alignment.Top else Alignment.Bottom

    Column(
        modifier = modifier.fillMaxWidth().testTag(BattlefieldTestTags.side(side.playerId)),
        verticalArrangement = Arrangement.spacedBy(RowGap, towardCentre),
    ) {
        order.forEach { row ->
            val content = row.roles.flatMap(side::inRole)
            // The rule, in the one place it can be broken: nothing is emitted for an empty row, so it
            // occupies no height rather than an empty one.
            if (content.isNotEmpty()) {
                PermanentRow(
                    permanents = content,
                    tag = BattlefieldTestTags.row(side.playerId, row.name),
                    width = width,
                    alignment = row.alignment,
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
    alignment: Alignment,
    palette: CounterPalette,
    artFor: ((String) -> CardArtSlot?)?,
    onInspect: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = alignment) {
        Row(
            modifier =
                Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = BoardPadding)
                    .testTag(tag),
            horizontalArrangement = Arrangement.spacedBy(CardGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            permanents.forEach { permanent ->
                BoardCard(
                    state = permanent.state,
                    width = width,
                    art = artFor?.invoke(permanent.state.card.name),
                    onTap = onInspect?.let { inspect -> { inspect(permanent.id) } },
                    counterPalette = palette,
                )
            }
        }
    }
}

/**
 * The card width every side is drawn at.
 *
 * **A card has a size, and a sparse board does not make it bigger.** This is the constraint the first
 * cut of this got backwards: it sized cards to fill whatever space was going, so an opening board of
 * two lands drew two lands the height of the battlefield. Nothing about a game says a Forest is more
 * important when there is only one of it. So the width starts at [PreferredCardWidth] and the other
 * constraints only ever take it *down* — the board is a fixed size, and a card shrinks because the
 * board got busy, never grows because it got quiet.
 *
 * The rest are the reasons it shrinks, and the smallest wins.
 *
 * **Width:** the busiest row has to fit across the board, so the width is what leaves room for that
 * many cards and the gaps between them.
 *
 * **Height:** each side gets an equal share of the board and splits it between its rows, and a card is
 * taller than it is wide, so the row's own height caps the width too — the constraint that actually
 * binds on a phone in landscape, where the board is short.
 *
 * **Assemblies:** a card carrying attachments is *bigger than the card*, because upright attachments
 * stack above the host so their name bands show and turned ones reach out to the right. Sizing to the
 * card and not the assembly is the bug 0100 already shipped once, and it is invisible until the one
 * board with an Aura on it clips its host's name. So every permanent is asked how wide it can be and
 * the board takes the smallest answer.
 *
 * Floored at [MinCardWidth]. Below the floor a card stops being readable, which defeats the purpose of
 * shrinking it, so the row scrolls instead — the one place the board admits it has run out of space.
 */
private fun cardWidth(
    model: BattlefieldModel,
    available: Dp,
    height: Dp,
    rows: BattlefieldRowCounts,
): Dp {
    val widest = rows.busiestRow.coerceAtLeast(1)
    val byWidth = (available - BoardPadding * 2 - CardGap * (widest - 1)) / widest

    val rowsPerSide = rows.tallestSide.coerceAtLeast(1)
    val sideHeight = height / rows.sides.coerceAtLeast(1)
    val rowHeight = (sideHeight - RowGap * (rowsPerSide - 1)) / rowsPerSide
    val byHeight = rowHeight * CARD_ASPECT_RATIO

    val plain = minOf(PreferredCardWidth, byWidth, byHeight)
    val byAssembly =
        (model.opponents + listOfNotNull(model.viewer))
            .flatMap { it.permanents }
            .filter { it.state.attachments.isNotEmpty() }
            .minOfOrNull { boardCardWidthFitting(it.state, maxWidth = plain, maxHeight = rowHeight) }
            ?: plain

    return minOf(plain, byAssembly).coerceAtLeast(MinCardWidth)
}

/** What the sizing needs to know about the board, counted once. */
private data class BattlefieldRowCounts(
    val sides: Int,
    val busiestRow: Int,
    val tallestSide: Int,
)

private fun sideRows(model: BattlefieldModel): BattlefieldRowCounts {
    val sides = model.opponents + listOfNotNull(model.viewer)
    var busiest = 0
    var tallest = 0
    sides.forEach { side ->
        var populated = 0
        ViewerOrder.forEach { row ->
            val count = row.roles.sumOf { role -> side.inRole(role).size }
            if (count > 0) populated += 1
            if (count > busiest) busiest = count
        }
        if (populated > tallest) tallest = populated
    }
    return BattlefieldRowCounts(sides = sides.size, busiestRow = busiest, tallestSide = tallest)
}

/**
 * One row of a side, and which buckets feed it.
 *
 * The back row carries lands *and* the other permanents together, as §7.4 draws it — lands to the
 * side, other permanents beside them. They are one row because they compete for the same space and
 * neither should push the creatures off the board.
 */
private data class BattlefieldRow(
    val name: String,
    val roles: List<PermanentRole>,
    val alignment: Alignment,
)

/** Creatures, centred: they are what the eye goes to, and combat happens between the two of these. */
private val FrontRow =
    BattlefieldRow(
        name = "front",
        roles = listOf(PermanentRole.Creature),
        alignment = Alignment.Center,
    )

/**
 * Lands and everything else, against the near edge — §7.4's *"lands to the side, at the back"*.
 *
 * Lands come first inside the row, which is what puts them at the side rather than merely at the back.
 */
private val BackRow =
    BattlefieldRow(
        name = "back",
        roles = listOf(PermanentRole.Land, PermanentRole.Other),
        alignment = Alignment.CenterStart,
    )

/** The viewer reads bottom-up: their creatures sit against the centre line, above their lands. */
private val ViewerOrder = listOf(FrontRow, BackRow)

/** Mirrored: the opponent's lands are furthest away and their creatures face yours. */
private val OpponentOrder = listOf(BackRow, FrontRow)

/** Test tags for the regions, which carry no distinctive text of their own. */
object BattlefieldTestTags {
    const val BOARD: String = "battlefield"

    /** One player's half. */
    fun side(playerId: String): String = "battlefield-side-$playerId"

    /** One row of one half — `front` or `back`. Absent entirely when the row is empty. */
    fun row(
        playerId: String,
        row: String,
    ): String = "battlefield-row-$playerId-$row"
}

/**
 * The size a card is drawn at when the board has room for it.
 *
 * A ceiling, not a target: it is what a quiet board looks like, and every other constraint can only
 * take it down. Chosen so a phone in landscape holds a comfortable board — around a dozen permanents
 * across a row — at full size, before anything has to give.
 */
private val PreferredCardWidth = 58.dp

/** Below this a card stops being readable, so the row scrolls rather than shrinking further. */
private val MinCardWidth = 44.dp

private val BoardPadding = 4.dp
private val CardGap = 3.dp
private val RowGap = 3.dp
