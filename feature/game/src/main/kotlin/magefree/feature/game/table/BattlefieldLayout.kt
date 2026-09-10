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
import androidx.compose.ui.zIndex
import magefree.designsystem.board.BoardSurface
import magefree.designsystem.card.BOARD_CARD_ASPECT_RATIO
import magefree.designsystem.card.BoardCard
import magefree.designsystem.card.BoardFocus
import magefree.designsystem.card.CounterPalette
import magefree.designsystem.card.boardCardWidthFitting
import magefree.designsystem.card.rememberCounterPalette
import magefree.designsystem.component.phase.PhaseBarTurn
import magefree.designsystem.component.phase.PhaseRailState
import magefree.designsystem.component.phase.PhaseRailStep
import magefree.network.game.CombatGroup

/*
 * The board, in three columns.
 *
 * ```
 *  ┌──────┬───────────────┬──────────────────────────────┐
 *  │[card]│               │   [ other permanents ]       │  back
 *  │ ▤5 ✝2│   opponent    │   [ creatures ]              │  front
 *  │ ░│UP│ │    lands      ├──────────────────────────────┤
 *  │ ░│M1│ │               │                              │
 *  │ ░│EN│●├───────────────┤   [ creatures ]              │  front
 *  │ ▤3 ✝1│    your       │   [ other permanents ]       │  back
 *  │[card]│    lands      ├──────────────────────────────┤
 *  │      │               │   hand        [ elsewhere ]  │
 *  └──────┴───────────────┴──────────────────────────────┘
 * ```
 *
 * **Three columns, because the three things have different jobs.** The left rail is read
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
 * @param playableElsewhere what the server is offering that is not in hand, from [playableElsewhere] —
 *   drawn beside the hand and set apart from it.
 * @param hand the viewer's own cards, from [handCards]. Empty for a spectator, and for anyone whose
 *   hand the board is not showing — an empty hand draws nothing rather than an empty strip.
 * @param vitals each seat, from [tableVitals]. Empty draws nothing.
 * @param onExpandVitals opens a seat's full window — its status and every one of its piles — or `null`
 *   for a board that is only being read.
 * @param lifeTotals each seat's life, drawn on the centre line of their own edge. Separate from
 *   [vitals] because it is a different thing in a different place: the rail is read when you go
 *   looking for it, and this is where a player is *pointed at* — by a spell on the stack, and by a
 *   player answering a target question. See [LifeTotal].
 * @param onPickPlayer answers the outstanding question with a player, by their id. Called only for a
 *   life total the prompt marked pickable; `null` for a board that is only being read.
 * @param phases the turn and where in it the game is, from [phaseRailState]. Null draws no rail — the same rule as everywhere
 *   else here, and the state a board has before a game starts.
 * @param onToggleStop invoked with the step and the side whose column was pressed.
 * @param zones every seat's piles: the rail draws each graveyard's top card and opens them.
 * @param onOpenPiles opens piles — a graveyard on its own, or everything behind a seat's counts.
 * @param onPlayFromHand called with a hand card's id when it is tapped. What that *does* is the cast
 *   flow's business; the board only says which card the player reached for.
 * @param stackVisible whether to draw the stack at all. False is *Show battlefield* — the layer is a
 *   layer, and the one thing it can still cover is a permanent the player is being asked to pick. The
 *   stack is passed either way rather than emptied, so the animation host does not see a spell it has
 *   already flown in arrive a second time when the panel comes back.
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
    focus: BoardFocus = BoardFocus.Quiet,
    hand: List<TableCard> = emptyList(),
    playableElsewhere: List<TableCard> = emptyList(),
    onPlayFromHand: ((String) -> Unit)? = null,
    vitals: List<TableVitals> = emptyList(),
    onExpandVitals: ((TableVitals) -> Unit)? = null,
    lifeTotals: LifeTotals = LifeTotals(opponents = emptyList(), viewer = null),
    onPickPlayer: ((String) -> Unit)? = null,
    opponentHand: KnownHand = KnownHand(),
    phases: PhaseRailState? = null,
    onToggleStop: ((PhaseRailStep, PhaseBarTurn) -> Unit)? = null,
    zones: List<TableZonePile> = emptyList(),
    onOpenPiles: ((List<TableZonePile>) -> Unit)? = null,
    stack: List<TableStackObject> = emptyList(),
    stackVisible: Boolean = true,
    combat: List<CombatGroup> = emptyList(),
) {
    val palette = rememberCounterPalette()
    // Where everything is, measured as it is placed, so the target arrows can be drawn between real
    // positions rather than from a second copy of this layout's arithmetic.
    val anchors = rememberBoardAnchors()

    androidx.compose.runtime.CompositionLocalProvider(LocalBoardFocus provides focus) {
        BoxWithConstraints(
            modifier =
                modifier
                    .fillMaxSize()
                    .background(BoardSurface.table)
                    .testTag(BattlefieldTestTags.BOARD)
                    .then(anchors.rootModifier()),
        ) {
            val sides = model.opponents + listOfNotNull(model.viewer)
            val boardWidth = maxWidth - BoardMargin * 2
            val boardHeight = maxHeight - BoardMargin * 2

            // The bottom of the screen is the viewer's hand, read between decisions, so the board's own
            // rows stop above it rather than being overlaid by it.
            val handTile = handTileWidth(boardHeight * HAND_HEIGHT_SHARE)
            val bottomStack = bottomStackHeight(hand, handTile)

            // **And the top of the screen is the opponent's hand, which cost the budget nothing.** It
            // was added after this arithmetic was written and never entered it, so every card on the
            // board was sized against a whole card height the board did not have — which is why the
            // non-creature row ran off the bottom and over the hand on a board with anything in it.
            // It is a full card tall: unlike the viewer's, none of it hangs off the edge.
            val topStack = topStackHeight(opponentHand, handTile)
            val contentHeight = (boardHeight - bottomStack - topStack).coerceAtLeast(0.dp)

            // **The gap between the two sides is height too.** `CentreLineGap` separates them and is
            // deliberately much larger than a row gap, and it was never taken out of the budget — so
            // each side was sized for half a gap more than it had. On a board with slack that was
            // invisible; with a hand on screen the slack is gone, and the surplus came out as the
            // non-creature row overlapping the creatures and running under the phase bar.
            val sideHeight = sideHeightFor(contentHeight, sides.size)

            // **The rail is a column of numbers, so it is as narrow as numbers are.** It was a card wide
            // while it drew the top card of every pile; those became counts, and the width they were using
            // went back to the battlefield.
            val hasRail = vitals.isNotEmpty()
            val railWidth = if (hasRail) RailWidth else 0.dp
            val afterRail = boardWidth - railWidth - if (hasRail) ZoneGap else 0.dp

            // **The land column takes what it needs, up to a ceiling.** A share carved off would hold width
            // open on a board with two lands and run out on one with six kinds of them — and running out is
            // what puts a Swamp on its own line below the Islands. So it asks for one row of stacks per
            // side and is capped, never reserved.
            val landWidth = landCardWidth(sides, afterRail * LAND_ZONE_CEILING, sideHeight)
            val landZoneWidth = minOf(landZoneWidth(sides, landWidth), afterRail * LAND_ZONE_CEILING)
            val mainWidth = afterRail - landZoneWidth - if (landZoneWidth > 0.dp) ZoneGap else 0.dp

            // A size per kind of permanent, each shared across both sides: a creature on the far side is the
            // same size as one on this side, because the game does not say one is nearer. See [MainCardWidths].
            val cardWidths = mainCardWidths(sides, mainWidth, sideHeight)

            // **The creatures belong on the screen's centre line, not their column's.** The battlefield is
            // the third column, so centring inside it puts the creatures well right of the middle with a
            // hole where the player is looking. The rows slide back toward the screen's own centre by the
            // difference — but only as far as their own slack allows, so a row wide enough to need its
            // whole column stays in it and never slides under the lands.
            val leftColumns = boardWidth - mainWidth
            val centreShift = (leftColumns + mainWidth / 2 - boardWidth / 2).coerceAtLeast(0.dp)

            Row(modifier = Modifier.fillMaxSize().padding(BoardMargin)) {
                if (hasRail) {
                    // **The turn lives here now, not under the hand.** A vertical rail says whose turn
                    // it is and which phase at once — one glance, two answers — and it costs the
                    // battlefield nothing, because the counts and a card were already the widest
                    // things in this column and the steps went in between them.
                    StatusRail(
                        vitals = vitals,
                        palette = palette,
                        rail = phases,
                        zones = zones,
                        artFor = artFor,
                        onExpand = onExpandVitals,
                        onOpenPiles = onOpenPiles,
                        onToggleStop = onToggleStop,
                        modifier = Modifier.width(railWidth).fillMaxHeight(),
                    )
                    Spacer(modifier = Modifier.width(ZoneGap))
                }

                Column(modifier = Modifier.fillMaxSize()) {
                    // **Their hand, along their own edge.** Mirrored exactly as the battlefields are,
                    // so how many cards they are holding is something read rather than looked up. It
                    // hangs off the top for the reason the viewer's hangs off the bottom: only the part
                    // carrying the name is worth the room. See [OpponentHandRegion].
                    OpponentHandRegion(
                        hand = opponentHand,
                        tileWidth = handTile,
                        artFor = artFor,
                        onInspect = onInspect,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    // The two board columns share what is left above the hand. `weight` rather than the
                    // measured `contentHeight`, so the arithmetic that sized the cards can be an estimate
                    // without the layout inheriting its error.
                    //
                    // **The life totals are drawn over this, not in it.** They sit on the centre line of
                    // each player's own edge — the one place on a mirrored board that belongs to a player
                    // rather than to a zone — and a row that reserved height for them would take it from
                    // the battlefield in every game, including the ones where nothing is ever targeted.
                    Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                        Row(modifier = Modifier.fillMaxSize()) {
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

                            // **A gap on the centre line.** Each side packs its creatures against the middle,
                            // so without one the two front rows touch and the board reads as one crowd of
                            // creatures rather than as two armies facing each other — which is the single most
                            // important thing a glance at a battlefield has to answer.
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(CentreLineGap),
                            ) {
                                model.opponents.forEach { side ->
                                    SideRows(
                                        side = side,
                                        order = OpponentOrder,
                                        cardWidths = cardWidths,
                                        centreShift = centreShift,
                                        palette = palette,
                                        artFor = artFor,
                                        onInspect = onInspect,
                                        anchors = anchors,
                                        modifier = Modifier.fillMaxWidth().weight(1f),
                                    )
                                }

                                model.viewer?.let { side ->
                                    SideRows(
                                        side = side,
                                        order = ViewerOrder,
                                        cardWidths = cardWidths,
                                        centreShift = centreShift,
                                        palette = palette,
                                        artFor = artFor,
                                        onInspect = onInspect,
                                        anchors = anchors,
                                        modifier = Modifier.fillMaxWidth().weight(1f),
                                    )
                                }
                            }
                        }

                        // Mirrored exactly as the battlefields are, so whose life it is needs no label.
                        // Anchored like any other target, so an arrow from a spell that names a player
                        // has somewhere real to point.
                        Row(
                            modifier = Modifier.align(Alignment.TopCenter),
                            horizontalArrangement = Arrangement.spacedBy(ZoneGap),
                        ) {
                            lifeTotals.opponents.forEach { seat -> SeatLife(seat, onPickPlayer, anchors) }
                        }

                        lifeTotals.viewer?.let { seat ->
                            Box(modifier = Modifier.align(Alignment.BottomCenter)) {
                                SeatLife(seat, onPickPlayer, anchors)
                            }
                        }
                    }

                    // The hand hangs off the bottom edge: only the top of a card is read, and the quarter
                    // that falls off screen is the quarter that carries nothing a player in a hurry needs.
                    HandRegion(
                        cards = hand,
                        elsewhere = playableElsewhere,
                        tileWidth = handTile,
                        artFor = artFor,
                        onPlay = onPlayFromHand,
                        onInspect = onInspect,
                        anchors = anchors,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // **The stack floats on the centre line rather than sitting in it.** In the flow it had a
            // weight, so a spell arriving compressed both battlefields — and because one card width is
            // shared by the whole table, compressing the centre resized every card on it. A player
            // watching a spell go on the stack watched their board shrink around it.
            //
            // Floating costs nothing it was buying: it still lands on the centre line, where a table
            // puts it and where the arrows have the shortest way to go. It simply stops changing the
            // size of everything else while it is there.
            //
            // **Which arrival is still travelling**, so the stack can lay a card out without drawing
            // it yet — see [StackFlights.arriving]. Read before the region is composed because that
            // is what it is for.
            val flights = rememberCardFlights(stack = stack, anchors = anchors, visible = stackVisible)
            val landed = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(emptySet<String>()) }

            if (stackVisible && stack.isNotEmpty()) {
                StackRegion(
                    stack = stack,
                    cardWidth = cardWidths.largest,
                    palette = palette,
                    artFor = artFor,
                    anchors = anchors,
                    onInspect = onInspect,
                    arriving = flights.arriving - landed.value,
                    modifier =
                        Modifier
                            .align(Alignment.Center)
                            .fillMaxWidth()
                            .padding(horizontal = StackInset)
                            // **A floating layer says that it floats.** Draw order in a `Box` is
                            // composition order, which is a fact about this function's text rather
                            // than about the board — and a permanent drew over the stack panel while
                            // every line here said it should not. What is a layer is stated.
                            .zIndex(STACK_LAYER_Z),
                )
            }

            // **Over everything, and touching nothing.** The arrows are drawn last so they are not covered
            // by the cards they run between, and the `Canvas` takes no pointer input, so the cards
            // underneath answer a press exactly as they did before there were arrows.
            // **An object that is not drawn has nothing to draw an arrow from.** [BoardAnchors] does
            // not prune, so with the stack hidden its ids still answer with the box they last had —
            // and the arrow would come out of empty air on the board the player just asked to see.
            TargetArrows(
                stack = if (stackVisible) stack else emptyList(),
                anchors = anchors,
                combat = combat,
                modifier = Modifier.fillMaxSize().zIndex(ARROW_LAYER_Z),
            )

            // **A card arriving on the stack, drawn travelling.** Above the arrows and above the cards,
            // because it is the one thing on the board that is momentarily more important than either;
            // it lands exactly on the stack card the region is holding a place for and then stops
            // existing, so nothing here is load-bearing for correctness — see [CardFlights].
            CardFlightOverlay(
                flights = flights.flights.filterNot { it.id in landed.value },
                palette = palette,
                artFor = artFor,
                onLanded = { id -> landed.value = landed.value + id },
                modifier = Modifier.zIndex(FLIGHT_LAYER_Z),
            )
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

/**
 * One seat's life total, pressable only when the outstanding question can be answered with them.
 *
 * The board decides nothing here: [LifeTotalState.isPickable] came from the prompt's own candidate
 * list, and pressing sends the player's own server id, because that is what upstream targets a player
 * by.
 */
@Composable
private fun SeatLife(
    seat: LifeTotalState,
    onPickPlayer: ((String) -> Unit)?,
    anchors: BoardAnchors,
) {
    LifeTotal(
        state = seat,
        onPick = onPickPlayer?.takeIf { seat.isPickable }?.let { pick -> { pick(seat.playerId) } },
        modifier = anchors.anchorModifier(seat.playerId),
    )
}

/** One player's rows of the battlefield: creatures against the centre line, everything else behind. */
@Composable
private fun SideRows(
    side: BattlefieldSide,
    order: List<BattlefieldRow>,
    cardWidths: MainCardWidths,
    centreShift: Dp,
    palette: CounterPalette,
    artFor: TableArtResolver?,
    onInspect: ((String) -> Unit)?,
    anchors: BoardAnchors,
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
            val content = side.entriesIn(row.role)
            if (content.isNotEmpty()) {
                PermanentRow(
                    entries = content,
                    tag = BattlefieldTestTags.row(side.playerId, row.name),
                    width = cardWidths.forRole(row.role),
                    // Only the centred rows slide. A row already pinned to the outside edge is where
                    // it was put on purpose.
                    centreShift = if (row.alignment == Alignment.Center) centreShift else 0.dp,
                    palette = palette,
                    artFor = artFor,
                    onInspect = onInspect,
                    anchors = anchors,
                    alignment = row.alignment,
                    towardCentre = towardCentre,
                )
            }
        }
    }
}

/** One row of permanents, scrolling sideways when it cannot fit at the floor width. */
@Composable
private fun PermanentRow(
    entries: List<RowEntry>,
    tag: String,
    width: Dp,
    centreShift: Dp,
    palette: CounterPalette,
    artFor: TableArtResolver?,
    onInspect: ((String) -> Unit)?,
    anchors: BoardAnchors,
    alignment: Alignment,
    towardCentre: Alignment.Vertical,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth(), contentAlignment = alignment) {
        // How far this row *may* slide left before it would leave its own column: half of whatever
        // width it is not using. A row that fills the column does not move at all, which is what keeps
        // it out from under the land column however busy the board gets.
        // A pile costs more than a card — staggered faces, and the fan reaching right — so the row's
        // budget is measured in what each entry actually occupies rather than in cards.
        val content =
            entries.sumOf { it.widthInCards().toDouble() }.toFloat().let { width * it } +
                CardGap * (entries.size - 1)
        val slack = ((maxWidth - content) / 2).coerceAtLeast(0.dp)

        // **The row scrolls only once it has to, because scrolling clips.** A tapped permanent leans
        // forty-five degrees, and a square on its corner reaches a further √2⁄2 of a card past the box
        // it was laid out in — the card tier's claim that it "leans inside its own footprint" is true
        // of the space it *reserves* and not of the pixels it draws. A scroll container clips to its
        // bounds, so a lone tapped creature in a row exactly its own width had both its corners cut
        // off. Nothing else on this board clips, so a leaning card is free to overhang its neighbours
        // by the same margin a real card does when you turn it on a table.
        //
        // The scroll stays for the row that genuinely does not fit, which is the one place the board
        // admits it has run out of space; there the clip is the lesser problem.
        val scrolls = content > maxWidth

        Row(
            modifier =
                Modifier
                    .offset(x = -minOf(centreShift, slack))
                    .let { base -> if (scrolls) base.horizontalScroll(rememberScrollState()) else base }
                    .testTag(tag),
            horizontalArrangement = Arrangement.spacedBy(CardGap),
            // **Entries share the edge nearest the centre line, not their centres.** A pile is taller
            // than a card, so a row that centred them left a lone creature floating halfway down the
            // pile beside it — Liliana's Reaver sitting below the tokens it is standing next to. The
            // rows already meet on the centre line; their contents should too.
            verticalAlignment = towardCentre,
        ) {
            entries.forEach { entry ->
                when (entry) {
                    is RowEntry.Single ->
                        PermanentCard(
                            permanent = entry.permanent,
                            width = width,
                            palette = palette,
                            artFor = artFor,
                            onInspect = onInspect,
                            anchors = anchors,
                        )

                    // The lands' own renderer, because a pile of tokens *is* the same thing: several
                    // interchangeable copies of one card, read once. A token pile is uniformly upright
                    // or uniformly turned, so only one of its two halves is ever populated.
                    is RowEntry.Pile ->
                        LandStack(
                            stack = entry.asStack(),
                            width = width,
                            palette = palette,
                            artFor = artFor,
                            onPress = onInspect?.let { inspect -> { _ -> inspect(entry.permanents.first().id) } },
                            // On the front *card*, not on the pile: the pile's box is a card and a
                            // half wide and holds the staggering too, so an arrow measured against it
                            // left visibly empty air beside the cards.
                            // Every member, not just the front one: a pile draws them all in one
                            // place, and a member anchored where it used to be drew an arrow from there.
                            anchorModifier = anchors.anchorModifier(entry.permanents.map { it.id }),
                            // Only the half it has. A pile cannot gain the other one — a token that
                            // taps leaves for a pile of its own — so room kept for it is room nothing
                            // will ever occupy, and it showed as a pile of tapped tokens hanging a
                            // title bar below the cards beside it.
                            halves = entry.halves(),
                        )
                }
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
    anchors: BoardAnchors,
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
        // Where this permanent is, for an arrow from whatever is targeting it.
        modifier = anchors.anchorModifier(permanent.id),
        counterPalette = palette,
        // What the board is about right now, which decides which of a card's signals gets the strong
        // border. Ambient, because every card on one board shares the answer — see [LocalBoardFocus].
        focus = LocalBoardFocus.current,
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
 * How wide each kind of permanent is drawn.
 *
 * **Two sizes, because the two kinds are not read the same way.** A creature is the permanent a player
 * is asked about most — what is attacking, what can block, what its stats have become after four
 * effects — and it carries the counters and badges that say so. An artifact or an enchantment is
 * usually read once, when it arrives, and then remembered. Drawing both at one size spent the same
 * room on both, and the room is what a creature needs.
 */
internal data class MainCardWidths(
    val creature: Dp,
    val other: Dp,
) {
    /** The width [role] is drawn at. Lands are not here — they have their own column and their own size. */
    fun forRole(role: PermanentRole): Dp = if (role == PermanentRole.Creature) creature else other

    /**
     * The larger of the two, for the things on this board that are cards without being permanents.
     *
     * The stack is the case: an object waiting to resolve has no role — it may become either kind, or
     * neither — and it is the one object the whole game is currently waiting on, so it takes the
     * board's readable size rather than its quiet one.
     */
    val largest: Dp get() = maxOf(creature, other)
}

/**
 * How wide everything that is not a land is drawn, **per role**.
 *
 * Four constraints, and the smallest wins: the preferred size, the busiest row fitting across the main
 * area, the side's rows fitting down its half, and — the one that is easy to forget — a card carrying
 * attachments being *bigger than the card*, because upright attachments stack above the host so their
 * name bands show and turned ones reach out to the right. That last was a shipped bug in 0100 and it
 * shows only on the one board that has an Aura on it.
 *
 * **Crowding is answered per role, and height is answered across both.** A row's width problem is its
 * own: twelve creatures say nothing about how big an enchantment should be, and shrinking the back row
 * to pay for the front one was the board taking room from a card that had it. Height is the opposite —
 * the two rows are stacked in one side and share its height — so when a side does not fit, **both**
 * roles scale by the same factor. Scaling only the offender would leave the two sizes in whatever
 * ratio the crowding happened to produce, and the ratio is the thing this exists to state.
 *
 * Floored at [MinCardWidth]: below it a card stops being readable, which defeats the purpose of
 * shrinking it, so the row scrolls instead — the one place the board admits it has run out of space.
 */
private fun mainCardWidths(
    sides: List<BattlefieldSide>,
    mainWidth: Dp,
    sideHeight: Dp,
): MainCardWidths {
    // **Entries, not permanents.** A pile of twelve Zombie tokens is one thing in the row, and counting
    // the tokens instead is what made a token board size every card on the table — both sides, every
    // row — from a crowd that draws as a single stack.
    //
    // Across both sides, because one width is shared by them on purpose: the game does not say one
    // side's creatures are nearer than the other's.
    fun busiest(role: PermanentRole) = sides.maxOfOrNull { it.entriesIn(role).size } ?: 0

    // **A crowded row shrinks its cards only so far, and then scrolls.** Past [LegibleCardWidth] the
    // trade stops being worth making: the row that does not fit scrolls, and the board keeps a card it
    // can read.
    fun byWidth(role: PermanentRole): Dp {
        val count = busiest(role)
        if (count == 0) return preferredWidthFor(role)
        return ((mainWidth - CardGap * (count - 1)) / count).coerceAtLeast(LegibleCardWidth)
    }

    val roles = listOf(PermanentRole.Creature, PermanentRole.Other)
    if (roles.all { busiest(it) == 0 }) {
        return MainCardWidths(creature = PreferredCreatureWidth, other = PreferredOtherWidth)
    }

    val creature = minOf(PreferredCreatureWidth, byWidth(PermanentRole.Creature))
    var widths =
        MainCardWidths(
            creature = creature,
            // **Never wider than a creature.** A row's width problem is its own, so a crowded creature
            // row does not shrink the back row *to pay for it* — but it does cap it, because the
            // ordering is the whole point of having two sizes. A board of six creatures and three
            // enchantments drew the enchantments half again the size of the creatures: the cards a
            // player is asked about most, drawn smallest, on the busiest board. Equal rather than the
            // ratio applied downward, because shrinking a card that has the room buys nothing.
            other = minOf(PreferredOtherWidth, byWidth(PermanentRole.Other), creature),
        )

    // **A pile is taller than a card, and the height budget has to know it.** The fan staggers
    // downward and the turned half hangs below, so a row holding one costs `stackHeightInCards()`
    // card-widths of height rather than one card's worth. Budgeting a card per row is what put the
    // non-creature permanents below the bottom of the board — behind the phase bar — the moment a
    // token pile appeared.
    //
    // Everything is in **card-width units**, which is what `StackShape` already measures in, so a row
    // costs its height in cards times the width of the role that fills it, and no aspect ratio is
    // applied twice.
    val scale =
        sides.minOfOrNull { side ->
            val populated = ViewerOrder.count { side.entriesIn(it.role).isNotEmpty() }
            val cards =
                ViewerOrder.fold(0.dp) { total, row ->
                    total + widths.forRole(row.role) * side.entriesIn(row.role).heightInCards()
                }
            val room = sideHeight - RowGap * (populated - 1).coerceAtLeast(0)
            if (cards <= 0.dp) 1f else (room / cards).coerceAtMost(1f)
        } ?: 1f
    if (scale < 1f) widths = MainCardWidths(creature = widths.creature * scale, other = widths.other * scale)

    // An assembly is one permanent's problem, so it is answered in that permanent's own role: an Aura
    // on a creature says nothing about how wide an artifact may be drawn.
    return MainCardWidths(
        creature = widths.creature.afterAssemblies(sides, PermanentRole.Creature),
        other = widths.other.afterAssemblies(sides, PermanentRole.Other),
    )
}

/** This width, taken down by whatever a permanent of [role] carrying attachments cannot fit inside. */
private fun Dp.afterAssemblies(
    sides: List<BattlefieldSide>,
    role: PermanentRole,
): Dp {
    val rowHeight = this / BOARD_CARD_ASPECT_RATIO
    val fitted =
        sides
            .flatMap { it.permanents }
            .filter { it.role == role && it.state.attachments.isNotEmpty() }
            .minOfOrNull { boardCardWidthFitting(it.state, maxWidth = this, maxHeight = rowHeight) }
            ?: this
    return minOf(this, fitted).coerceAtLeast(MinCardWidth)
}

/** The size a permanent of [role] is drawn at when the board has room for it. */
private fun preferredWidthFor(role: PermanentRole): Dp = if (role == PermanentRole.Creature) PreferredCreatureWidth else PreferredOtherWidth

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

/**
 * How wide the status rail is.
 *
 * A fixed width rather than a share, because what is in it is fixed: a life total, four zone counts,
 * and a chip per counter. It is sized to the widest of those and not to the screen — a rail that grew
 * on a larger phone would be taking width from the battlefield to hold the same four numbers.
 */
private val RailWidth = 76.dp

/**
 * The size a **land** is drawn at when the board has room for it.
 *
 * A ceiling, not a target: it is what a quiet board looks like, and every other constraint can only
 * take it down. Smaller than the battlefield's own, because §7.4's whole point about lands is that
 * they are the most numerous permanents and the least individually interesting — a land is read by
 * which land it is, and that is the one thing its picture says at any size.
 */
private val PreferredCardWidth = 112.dp

/**
 * The size a **non-creature** permanent is drawn at when the board has room for it.
 *
 * Half again the land's, and the size Pete picked off a board he liked the look of — this is the one
 * of the two that was measured rather than derived. An artifact or an enchantment is usually read once,
 * when it arrives, and remembered after that; this is the width at which reading it once works.
 *
 * The land column is bounded, so the width this takes comes out of empty board rather than out of the
 * lands.
 */
internal val PreferredOtherWidth = 252.dp

/**
 * The size a **creature** is drawn at when the board has room for it.
 *
 * A quarter larger than [PreferredOtherWidth], which is Pete's own figure from the same board. It is
 * the one derived from the other on purpose: the pair is a *ratio*, and a ratio written as two
 * independent numbers drifts apart the first time either is adjusted.
 *
 * A creature is the permanent a player is asked about most — what is attacking, what can block, what
 * its power has become after four effects — and it is the one carrying the counters and badges that
 * say so, on top of the picture. The extra quarter is where those go.
 */
internal val PreferredCreatureWidth = PreferredOtherWidth * CREATURE_SIZE_ADVANTAGE

/** How much larger a creature is drawn than everything else that is not a land. Pete's own figure. */
private const val CREATURE_SIZE_ADVANTAGE = 1.25f

/**
 * Below this a card stops being readable, so the row scrolls rather than shrinking further.
 *
 * Set by the **title bar**, which is the one part of the card that is text: the square leaves it
 * roughly a quarter of the card's height, and a line of the board's own name style needs 13dp of
 * that. Under this width the name is clipped mid-line, which looks like a defect rather than like a
 * small card.
 */
private val MinCardWidth = 56.dp

/**
 * The narrowest a card is shrunk to make a crowded row fit.
 *
 * Below this its name band and its stats stop being readable, so shrinking further buys nothing a
 * player can use — the row scrolls instead. Distinct from [MinCardWidth], which is the hard floor for
 * a board so small that nothing fits at all.
 */
private val LegibleCardWidth = 96.dp

/** Space around the whole board, because a card against the screen edge is awkward to touch. */
private val BoardMargin = 12.dp

private val ZoneGap = 8.dp

/**
 * Between two permanents that are not attached to each other.
 *
 * Wide enough that a row reads as separate cards. A card carrying an attachment already overlaps its
 * own stack on purpose, and the gap has to be clearly more than that overlap or the two kinds of
 * adjacency — *these are one permanent* and *these are two* — look the same.
 */
private val CardGap = 10.dp
private val StackGap = 8.dp
private val RowGap = 3.dp

/**
 * Between the two sides, on the centre line.
 *
 * Much larger than [RowGap], and that is the point: the gap between one player's rows says *these
 * belong together*, and the gap between the two players says *these do not*. Read at a glance they
 * have to be obviously different distances, or a board is one crowd of creatures.
 */
private val CentreLineGap = 20.dp

/** The gap between the two sides, for a test that asserts the budget accounts for it. */
internal val CentreLineGapForTest: Dp get() = CentreLineGap

/**
 * How much of the board's height the hand is sized against.
 *
 * A share rather than a fixed dp, because the board derives everything else from its own size too. It
 * sizes the tile; the region then takes whatever that tile measures, so the share is a budget and not
 * a reservation — and a hand that is not there takes none of it.
 */
private const val HAND_HEIGHT_SHARE = 0.30f

/**
 * How much of the bottom of the screen the hand has already claimed.
 *
 * Used to work out what is left for the board's own columns. An allowance rather than a measurement:
 * threading a measured value up through the layout pass would couple the board to components that draw
 * themselves perfectly well without it, and the columns are placed by weight, so an allowance that is
 * slightly off costs a few dp of card size and nothing else.
 *
 * **The turn used to be down here too**, in a horizontal bar resting on the hand, and it took about
 * forty dp of the board's height with it. It is on the left rail now, in a column the counts and a
 * card had already sized, so that height went back to the battlefield — which is 0123's claim that the
 * rail costs the board no width, arriving as height rather than as width.
 */
private fun bottomStackHeight(
    hand: List<TableCard>,
    handTile: Dp,
): Dp = if (hand.isEmpty()) 0.dp else handVisibleHeight(handTile)

/**
 * How much of the top of the screen the opponent's hand has claimed.
 *
 * **A whole card, not the fraction the viewer's hand costs.** The viewer's hangs off the bottom edge
 * and only the part carrying the name is on screen; the opponent's sits inside the column and is drawn
 * complete. It arrived after the sizing arithmetic was written and was never added to it, so the board
 * sized every card against height it did not have — and an empty hand takes none of it, which is why
 * the fault only showed once the opponent was holding something.
 */
private fun topStackHeight(
    hand: KnownHand,
    handTile: Dp,
): Dp = if (hand.count == 0) 0.dp else handTile / BOARD_CARD_ASPECT_RATIO

/**
 * How far in from the centre line the stack floats.
 *
 * **The stack is a floating layer and takes no part in sizing the board.** It used to sit *in* the
 * centre column with a weight, which meant a spell arriving compressed both battlefields — and
 * because one card width is shared by the whole table, compressing the centre resized every card on
 * it. A player watching a spell go on the stack watched their board shrink around it.
 *
 * Floating it costs nothing that it was buying: it still lands on the centre line, which is where a
 * table puts it and where the arrows have the shortest way to go. What it stops doing is changing the
 * size of everything else while it is there.
 */
private val StackInset = 8.dp

/*
 * The board's own layers, stated rather than implied.
 *
 * A `Box` draws its children in composition order, which is a fact about the order lines appear in
 * this file — and it did not hold: the opponent's newest non-creature permanent drew over the stack
 * panel, which is composed after the whole battlefield. Anything that floats over the board now says
 * so, and the numbers say which is over which. The battlefield itself stays at the default 0.
 */

/** The stack: a panel over the board, opaque, and over the permanents it lands among. */
private const val STACK_LAYER_Z = 1f

/** The arrows, over the cards they run between — including the stack's own. */
private const val ARROW_LAYER_Z = 2f

/** A card in flight, over everything: for its half-second it is the most important thing drawn. */
private const val FLIGHT_LAYER_Z = 3f

/**
 * How much height one side gets, out of the space above the phase bar and the hand.
 *
 * **The gap between the two sides is height too.** `CentreLineGap` separates them and is deliberately
 * much larger than a row gap, and it was never taken out of the budget — so each side was sized for
 * half a gap more than it actually had. On a board with slack that was invisible; with a hand on
 * screen there is no slack, and the surplus came out as the non-creature row overlapping the creatures
 * and running under the phase bar.
 *
 * A gap between two sides is `sideCount - 1` of them, which is zero for a spectator's single side and
 * one for an ordinary game.
 */
internal fun sideHeightFor(
    contentHeight: Dp,
    sideCount: Int,
): Dp {
    val sides = sideCount.coerceAtLeast(1)
    val gaps = CentreLineGap * (sides - 1).coerceAtLeast(0)
    return ((contentHeight - gaps) / sides).coerceAtLeast(0.dp)
}
