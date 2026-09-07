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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
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
import magefree.designsystem.card.BOARD_CARD_ASPECT_RATIO
import magefree.designsystem.card.BoardCard
import magefree.designsystem.card.CounterPalette
import magefree.designsystem.card.boardCardWidthFitting
import magefree.designsystem.card.rememberCounterPalette
import magefree.designsystem.component.phase.PhaseBar
import magefree.designsystem.component.phase.PhaseBarState
import magefree.designsystem.component.phase.PhaseBarStep

/*
 * The board, in three columns.
 *
 * ```
 *  ┌────────┬─────────────┬────────────────────────────┐
 *  │ opp    │             │   [ other permanents ]     │  back
 *  │ vitals │  opponent   │   [ creatures ]            │  front
 *  │ grave  │   lands     ├────────────────────────────┤
 *  │ other  │             │                            │
 *  │ exile  ├─────────────┤   [ creatures ]            │  front
 *  │ exile  │   your      │   [ other permanents ]     │  back
 *  │ other  │   lands     ├────────────────────────────┤
 *  │ grave  │             │   phase bar                │
 *  │ vitals │             │   hand                     │
 *  └────────┴─────────────┴────────────────────────────┘
 * ```
 *
 * **Three columns, because the three things have different jobs.** The status rail is read
 * occasionally and must never move. The lands are a fixed, bounded cost that grows all game. The
 * battlefield is what actually changes. The arrangement this replaced gave each *player* half the
 * screen and put their lands in a corner of it, which meant lands and creatures competed for the same
 * width — so a fourth kind of land pushed the creatures around for reasons that had nothing to do with
 * the game. A column of their own is what stops that: the lands can fill it and the battlefield never
 * notices.
 *
 * **Front means nearest the middle.** Creatures are what a player looks at — they attack, block and
 * change state constantly — so each side puts its creatures against the centre line, where the two
 * sides meet and where combat happens. Non-creature permanents sit on their own horizontal behind
 * them, toward the outside, where they are out of the way of the row that changes every combat.
 *
 * **Mirrored, not rotated.** Each side's rows run in the opposite vertical order so the creatures
 * face each other. The cards themselves are drawn the right way up: a player reads an opponent's board
 * constantly, and turning the text over to complete the metaphor would trade legibility for a picture
 * of a table.
 *
 * Rules that are easy to lose, and are therefore stated as code rather than intent:
 *
 * - **No empty region holds height** — on the battlefield. A side with no lands has no land zone, not
 *   an empty one. The status rail is the deliberate exception, and says so itself.
 * - **No chrome.** No borders, banners, headers or rules between the regions. A region is identified
 *   by its position and its shade of grey.
 * - **A card has a size, and a quiet board does not make it bigger.** Every constraint can only take
 *   the size down from a preferred one.
 * - **Nothing sits against the edge of the screen.** A card in the corner is a card that is awkward to
 *   touch, and the board's own margin is cheaper than finding that out per device.
 */

/**
 * The whole board.
 *
 * @param model the two sides, from [battlefieldModel].
 * @param modifier the [Modifier] for the board.
 * @param artFor resolves a permanent's art from the printing the server named. Without it every card
 *   falls back to its placeholder and the arrangement is still exactly what it will be — but the
 *   arrangement is much harder to judge that way, because a board of grey rectangles hides whether a
 *   card is actually readable at the size it was given.
 * @param onInspect called with a permanent's id when its card is tapped, or `null` for a board that is
 *   only being looked at. Lands do not go through it: see [onLandPress].
 * @param hand the viewer's own cards, from [handCards]. Empty for a spectator, and for anyone whose
 *   hand the board is not showing — an empty hand draws nothing rather than an empty strip.
 * @param vitals each seat, from [tableVitals]. Empty draws nothing.
 * @param onExpandVitals opens a seat's full list, or `null` for a board that is only being read.
 * @param zones each seat's piles — graveyard and the two exiles — from [tableZones].
 * @param onOpenZone called with a pile when it is pressed.
 * @param phases the turn and where in it the game is. Null draws no bar — the same rule as everywhere
 *   else here, and the state a board has before a game starts.
 * @param onToggleStop invoked when a stoppable step is pressed.
 * @param onPlayFromHand called with a hand card's id when it is tapped. What that *does* is the cast
 *   flow's business; the board only says which card the player reached for.
 * @param onLandPress called with a land stack and the half of it that was pressed. Lands are separate
 *   because a stack is two affordances rather than one — the upright copies are the card you would pick
 *   up, and the turned ones are the cards already lying down — and what each *means* is a question
 *   about the game rather than about the layout.
 */
@Composable
fun BattlefieldLayout(
    model: BattlefieldModel,
    modifier: Modifier = Modifier,
    artFor: TableArtResolver? = null,
    onInspect: ((String) -> Unit)? = null,
    onLandPress: ((TableLandStack, LandStackHalf) -> Unit)? = null,
    hand: List<TableCard> = emptyList(),
    onPlayFromHand: ((String) -> Unit)? = null,
    vitals: List<TableVitals> = emptyList(),
    onExpandVitals: ((TableVitals) -> Unit)? = null,
    zones: List<TableZonePile> = emptyList(),
    onOpenZone: ((TableZonePile) -> Unit)? = null,
    phases: PhaseBarState? = null,
    onToggleStop: ((PhaseBarStep) -> Unit)? = null,
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
        val boardWidth = maxWidth - BoardMargin * 2
        val boardHeight = maxHeight - BoardMargin * 2

        // The bottom of the screen is the hand with the phase bar resting on it. Both belong to the
        // viewer and both are read between decisions, so they sit together and the board's own rows
        // stop above them rather than being overlaid by them.
        val handTile = handTileWidth(boardHeight * HAND_HEIGHT_SHARE)
        val bottomStack = bottomStackHeight(hand, handTile, phases != null)
        val contentHeight = (boardHeight - bottomStack).coerceAtLeast(0.dp)
        val sideHeight = contentHeight / sides.size.coerceAtLeast(1)

        // The rail is a column of cards, so it is a card wide — capped, because on a small screen a
        // preferred-size card is a bigger share of the width than the rail is worth.
        val hasRail = zones.isNotEmpty() || vitals.isNotEmpty()
        val railWidth = if (hasRail) minOf(PreferredCardWidth, boardWidth * RAIL_CEILING) else 0.dp
        val afterRail = boardWidth - railWidth - if (hasRail) ZoneGap else 0.dp

        // **The piles are sized by the rail's height, not its width.** A seat has several of them and
        // its own numbers above or below, all in half a rail; sized to the rail's width they would
        // need three times the height there is. So the card is whatever fits, floored — below the
        // floor a pile marker stops reading as a card at all, and at that point the count on it is
        // doing the work anyway.
        val seatZones = zones.groupBy { it.playerId }.values.maxOfOrNull { it.size } ?: 0
        val railSeatHeight = boardHeight / sides.size.coerceAtLeast(1)
        val railCardWidth =
            if (seatZones == 0) {
                railWidth
            } else {
                val perPile = (railSeatHeight - VitalsAllowance - RailGap * seatZones) / seatZones
                minOf(railWidth, perPile * BOARD_CARD_ASPECT_RATIO).coerceAtLeast(MinRailCardWidth)
            }

        // **The land column takes what it needs, up to a ceiling.** A share carved off would hold width
        // open on a board with two lands and run out on one with six kinds of them — and running out is
        // what puts a Swamp on its own line below the Islands. So it asks for one row of stacks per
        // side and is capped, never reserved.
        val landWidth = landCardWidth(sides, afterRail * LAND_ZONE_CEILING, sideHeight)
        val landZoneWidth = minOf(landZoneWidth(sides, landWidth), afterRail * LAND_ZONE_CEILING)
        val mainWidth = afterRail - landZoneWidth - if (landZoneWidth > 0.dp) ZoneGap else 0.dp

        // One size for everything that is not a land, shared across both sides: a creature on the far
        // side is the same size as one on this side, because the game does not say one is nearer.
        val cardWidth = mainCardWidth(sides, mainWidth, sideHeight)

        // **The creatures belong on the screen's centre line, not their column's.** The battlefield is
        // the third column, so centring inside it puts the creatures well right of the middle with a
        // hole where the player is looking. The rows slide back toward the screen's own centre by the
        // difference — but only as far as their own slack allows, so a row wide enough to need its
        // whole column stays in it and never slides under the lands.
        val leftColumns = boardWidth - mainWidth
        val centreShift = (leftColumns + mainWidth / 2 - boardWidth / 2).coerceAtLeast(0.dp)

        Row(modifier = Modifier.fillMaxSize().padding(BoardMargin)) {
            if (hasRail) {
                StatusRail(
                    zones = zones,
                    vitals = vitals,
                    cardWidth = railCardWidth,
                    palette = palette,
                    artFor = artFor,
                    onOpenZone = onOpenZone,
                    onExpandVitals = onExpandVitals,
                    modifier = Modifier.width(railWidth).fillMaxHeight(),
                )
                Spacer(modifier = Modifier.width(ZoneGap))
            }

            Column(modifier = Modifier.fillMaxSize()) {
                // The two board columns share what is left above the hand. `weight` rather than the
                // measured `contentHeight`, so the arithmetic that sized the cards can be an estimate
                // without the layout inheriting its error.
                Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    if (landZoneWidth > 0.dp) {
                        LandColumn(
                            sides = sides,
                            width = landWidth,
                            palette = palette,
                            artFor = artFor,
                            onLandPress = onLandPress,
                            modifier = Modifier.width(landZoneWidth).fillMaxHeight(),
                        )
                        Spacer(modifier = Modifier.width(ZoneGap))
                    }

                    Column(modifier = Modifier.fillMaxSize()) {
                        model.opponents.forEach { side ->
                            SideRows(
                                side = side,
                                order = OpponentOrder,
                                cardWidth = cardWidth,
                                centreShift = centreShift,
                                palette = palette,
                                artFor = artFor,
                                onInspect = onInspect,
                                modifier = Modifier.fillMaxWidth().weight(1f),
                            )
                        }
                        model.viewer?.let { side ->
                            SideRows(
                                side = side,
                                order = ViewerOrder,
                                cardWidth = cardWidth,
                                centreShift = centreShift,
                                palette = palette,
                                artFor = artFor,
                                onInspect = onInspect,
                                modifier = Modifier.fillMaxWidth().weight(1f),
                            )
                        }
                    }
                }

                phases?.let { bar ->
                    PhaseBar(
                        state = bar,
                        onToggleStop = { step -> onToggleStop?.invoke(step) },
                        modifier = Modifier.fillMaxWidth().padding(bottom = PhaseBarGap),
                    )
                }

                // The hand hangs off the bottom edge: only the top of a card is read, and the quarter
                // that falls off screen is the quarter that carries nothing a player in a hurry needs.
                HandRegion(
                    cards = hand,
                    tileWidth = handTile,
                    artFor = artFor,
                    onPlay = onPlayFromHand,
                    onInspect = onInspect,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * The lands, in their own column, one side above the other.
 *
 * **One line per side, and it wraps only under protest.** The first cut wrapped freely into a grid,
 * and the result was a Swamp on its own row under the Islands — lands of one player scattered down
 * their half instead of reading as one row of stacks. They are the same kind of thing and they belong
 * on the same horizontal. The card size is derived so that one line fits; wrapping remains as the last
 * resort for a board with more kinds of land than anyone plays.
 *
 * Each side packs toward its own outer edge, mirrored across the middle exactly as the battlefield is.
 */
@Composable
private fun LandColumn(
    sides: List<BattlefieldSide>,
    width: Dp,
    palette: CounterPalette,
    artFor: TableArtResolver?,
    onLandPress: ((TableLandStack, LandStackHalf) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        sides.forEach { side ->
            val lands = side.landStacks()
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                // The viewer is the last side, so their lands pack down toward the bottom corner and
                // an opponent's up toward the top — the two halves meeting in the middle of the column.
                contentAlignment = if (side.isViewer) Alignment.BottomStart else Alignment.TopStart,
            ) {
                // The rule, in the one place it can be broken: a side with no lands emits no zone, so
                // the column costs that side nothing rather than holding an empty box.
                if (lands.isNotEmpty()) {
                    LandRow(
                        lands = lands,
                        tag = BattlefieldTestTags.row(side.playerId, BattlefieldTestTags.LAND_ZONE),
                        width = width,
                        palette = palette,
                        artFor = artFor,
                        onLandPress = onLandPress,
                    )
                }
            }
        }
    }
}

/** One side's land stacks, on one line where they fit. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LandRow(
    lands: List<TableLandStack>,
    tag: String,
    width: Dp,
    palette: CounterPalette,
    artFor: TableArtResolver?,
    onLandPress: ((TableLandStack, LandStackHalf) -> Unit)?,
) {
    FlowRow(
        modifier = Modifier.testTag(tag),
        horizontalArrangement = Arrangement.spacedBy(StackGap),
        verticalArrangement = Arrangement.spacedBy(StackGap),
    ) {
        lands.forEach { stack ->
            LandStack(
                stack = stack,
                width = width,
                palette = palette,
                artFor = artFor,
                onPress = onLandPress?.let { press -> { half -> press(stack, half) } },
            )
        }
    }
}

/** One player's rows of the battlefield: creatures against the centre line, everything else behind. */
@Composable
private fun SideRows(
    side: BattlefieldSide,
    order: List<BattlefieldRow>,
    cardWidth: Dp,
    centreShift: Dp,
    palette: CounterPalette,
    artFor: TableArtResolver?,
    onInspect: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    // The viewer's front row comes first, so packing to the top puts it against the middle; the
    // opponent's comes last, so theirs packs to the bottom. One rule, mirrored.
    val towardCentre = if (order === ViewerOrder) Alignment.Top else Alignment.Bottom

    Column(
        modifier = modifier.testTag(BattlefieldTestTags.side(side.playerId)),
        verticalArrangement = Arrangement.spacedBy(RowGap, towardCentre),
    ) {
        order.forEach { row ->
            val content = side.inRole(row.role)
            if (content.isNotEmpty()) {
                PermanentRow(
                    permanents = content,
                    tag = BattlefieldTestTags.row(side.playerId, row.name),
                    width = cardWidth,
                    // Only the centred rows slide. A row already pinned to the outside edge is where
                    // it was put on purpose.
                    centreShift = if (row.alignment == Alignment.Center) centreShift else 0.dp,
                    palette = palette,
                    artFor = artFor,
                    onInspect = onInspect,
                    alignment = row.alignment,
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
    centreShift: Dp,
    palette: CounterPalette,
    artFor: TableArtResolver?,
    onInspect: ((String) -> Unit)?,
    alignment: Alignment,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth(), contentAlignment = alignment) {
        // How far this row *may* slide left before it would leave its own column: half of whatever
        // width it is not using. A row that fills the column does not move at all, which is what keeps
        // it out from under the land column however busy the board gets.
        val content = width * permanents.size + CardGap * (permanents.size - 1)
        val slack = ((maxWidth - content) / 2).coerceAtLeast(0.dp)

        Row(
            modifier =
                Modifier
                    .offset(x = -minOf(centreShift, slack))
                    .horizontalScroll(rememberScrollState())
                    .testTag(tag),
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

/** One permanent, drawn at [width], with whatever is attached to it. */
@Composable
private fun PermanentCard(
    permanent: TablePermanent,
    width: Dp,
    palette: CounterPalette,
    artFor: TableArtResolver?,
    onInspect: ((String) -> Unit)?,
) {
    // Resolved here rather than inside the card, because loading an image is a composition-time thing
    // and the card tier takes a plain lambda. Keyed by the server's own id, which is what the card
    // hands back when a band is pressed.
    val attachmentSlots =
        permanent.attached.associate { attachment ->
            attachment.id to artFor?.invoke(attachment.art, attachment.card)
        }

    BoardCard(
        state = permanent.state,
        width = width,
        art = artFor?.invoke(permanent.art, permanent.state.card),
        attachmentArt = { attachment -> attachmentSlots[attachment.id] },
        onTap = onInspect?.let { inspect -> { inspect(permanent.id) } },
        // An attachment is its own permanent and its own card. Reporting the host instead would make
        // the Aura the one card on the board that cannot be opened — and it is the card most likely to
        // be the answer to whatever the player is asking.
        onAttachmentTap = onInspect?.let { inspect -> { attachment -> inspect(attachment.id) } },
        counterPalette = palette,
    )
}

/**
 * How wide a land is drawn.
 *
 * **Sized so one line of stacks fits.** A stack costs more than a card — up to three staggered faces
 * on the upright side, and a turned half beside it once anything is tapped — so the budget is spent in
 * stack-widths, not card-widths. The column then has to fit inside one side's own height too, because
 * a stack is taller than a card by the same staggering.
 *
 * Capped at [PreferredCardWidth] and floored at [MinCardWidth]: a board with two lands draws two
 * ordinary lands rather than two the height of the battlefield, and a board with more kinds of land
 * than anyone plays wraps rather than shrinking past legibility.
 */
private fun landCardWidth(
    sides: List<BattlefieldSide>,
    zoneCeiling: Dp,
    sideHeight: Dp,
): Dp {
    val stacks = sides.map { it.landStacks() }
    val most = stacks.maxOfOrNull { it.size } ?: 0
    if (most == 0) return PreferredCardWidth

    // The busiest side's line, measured in card widths, so the answer is one division rather than a
    // search. Every stack costs the same, occupied or not, which is what keeps the board from resizing
    // itself the moment a land taps.
    val widest = stackWidthInCards() * most
    val byWidth = (zoneCeiling - StackGap * (most - 1)) / widest.coerceAtLeast(1f)
    val byHeight = sideHeight / stackHeightInCards()

    return minOf(byWidth, byHeight, PreferredCardWidth).coerceAtLeast(MinCardWidth)
}

/** What the land column actually asks for at [landWidth] — one line of the busiest side's stacks. */
private fun landZoneWidth(
    sides: List<BattlefieldSide>,
    landWidth: Dp,
): Dp {
    val stacks = sides.map { it.landStacks() }
    val most = stacks.maxOfOrNull { it.size } ?: 0
    if (most == 0) return 0.dp
    return landWidth * stackWidthInCards() * most + StackGap * (most - 1)
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
    val byHeight = rowHeight * BOARD_CARD_ASPECT_RATIO
    val plain = minOf(PreferredCardWidth, byWidth, byHeight)

    val byAssembly =
        sides
            .flatMap { it.permanents }
            .filter { it.role != PermanentRole.Land && it.state.attachments.isNotEmpty() }
            .minOfOrNull { boardCardWidthFitting(it.state, maxWidth = plain, maxHeight = rowHeight) }
            ?: plain

    return minOf(plain, byAssembly).coerceAtLeast(MinCardWidth)
}

/**
 * One row of a side's battlefield: which bucket feeds it, and where it sits across the width.
 *
 * **Creatures centre and everything else goes to the outside.** They were sharing one centre line and
 * the non-creature permanents ended up drawn behind the creatures — an artifact is not less important
 * than a Bear, it is just less busy, and a row that hides it is worse than a row that puts it
 * somewhere quieter.
 */
private data class BattlefieldRow(
    val name: String,
    val role: PermanentRole,
    val alignment: Alignment,
)

private val FrontRow = BattlefieldRow(name = "front", role = PermanentRole.Creature, alignment = Alignment.Center)
private val BackRow = BattlefieldRow(name = "back", role = PermanentRole.Other, alignment = Alignment.CenterEnd)

/** The viewer reads bottom-up: their creatures sit against the centre line, above the rest. */
private val ViewerOrder = listOf(FrontRow, BackRow)

/** Mirrored: the opponent's other permanents are furthest away and their creatures face yours. */
private val OpponentOrder = listOf(BackRow, FrontRow)

/** Test tags for the regions, which carry no distinctive text of their own. */
object BattlefieldTestTags {
    const val BOARD: String = "battlefield"

    /** The land column's own row name, for [row]. */
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

    /** One land stack, by the id it reports when tapped. */
    fun stack(stackId: String): String = "battlefield-stack-$stackId"

    /** A stack count badge, present only past [PILE_FAN_LIMIT]. */
    fun stackCount(stackId: String): String = "battlefield-stack-count-$stackId"

    /** The turned half's count badge. */
    fun stackTappedCount(stackId: String): String = "battlefield-stack-tapped-count-$stackId"
}

/**
 * How much of the width left beside the rail the land column may take.
 *
 * A ceiling on the least interesting permanents, which is §7.4's whole point about them. It is not a
 * reservation: a board with no lands draws no column at all, and the creatures get the width back.
 */
private const val LAND_ZONE_CEILING = 0.34f

/** How much of the board's width the status rail may take. It is one card wide, and one card only. */
private const val RAIL_CEILING = 0.14f

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
private val StackGap = 8.dp
private val RowGap = 3.dp

/**
 * How much of the board's height the hand is sized against.
 *
 * A share rather than a fixed dp, because the board derives everything else from its own size too. It
 * sizes the tile; the region then takes whatever that tile measures, so the share is a budget and not
 * a reservation — and a hand that is not there takes none of it.
 */
private const val HAND_HEIGHT_SHARE = 0.30f

/** Between the phase bar and the top of the hand it sits on. */
private val PhaseBarGap = 4.dp

/**
 * How much of the bottom of the screen the hand and the phase bar have already claimed.
 *
 * Used to work out what is left for the board's own columns. An allowance rather than a measurement:
 * the bar's height comes from its text, and threading a measured value up through the layout pass
 * would couple the board to components that draw themselves perfectly well without it. The columns are
 * placed by weight, so an allowance that is slightly off costs a few dp of card size and nothing else.
 */
private fun bottomStackHeight(
    hand: List<TableCard>,
    handTile: Dp,
    hasPhases: Boolean,
): Dp {
    val handPart = if (hand.isEmpty()) 0.dp else handVisibleHeight(handTile)
    val phasePart = if (hasPhases) PhaseBarAllowance + PhaseBarGap else 0.dp
    return handPart + phasePart
}

/** Room the phase bar takes, for working out what is left above it. */
private val PhaseBarAllowance = 28.dp

/**
 * Room a seat's numbers take in the rail, for working out what is left for its piles.
 *
 * An allowance rather than a measurement, for the reason the phase bar's is: the column's height comes
 * from its text and its counters, and threading a measured value up through the layout pass would
 * couple the board to a component that draws itself perfectly well without it.
 */
private val VitalsAllowance = 92.dp

/** Between the regions of one seat's rail. Mirrors `StatusRail`'s own, which is what it is spacing. */
private val RailGap = 3.dp

/** Below this a pile marker stops reading as a card, and the count on it is doing the work anyway. */
private val MinRailCardWidth = 34.dp
