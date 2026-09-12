package magefree.feature.game.table

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import magefree.designsystem.board.BoardSurface
import magefree.designsystem.board.BoardTypography
import magefree.designsystem.card.BoardCard
import magefree.designsystem.card.BoardCardState
import magefree.designsystem.card.CounterPalette
import magefree.designsystem.card.rememberCounterPalette

/*
 * One pile, opened and scrolled, in the order the game put it in.
 *
 * **Order is the whole point, and it is the server's.** A graveyard is not a set: what died first is
 * at the bottom and what died last is on top, and half the reason to look at one is to answer *what
 * just went there* or *what was there before this*. The old window drew a pile as a column of cards
 * with nothing saying which end was which, which is a list with the one useful fact removed.
 *
 * **Top first, reading downward.** [TableZonePile.cards] is the server's own order, oldest first, so
 * this reverses it: the card on top of the pile is the card at the top of the list, which is where a
 * player's eye starts and which is the card they came to see. The oldest is at the bottom, where it
 * would be on a table.
 *
 * **It floats, and closing it is a press outside.** The same scrim-and-press every other floating
 * surface on this board uses, because a player should not have to learn two ways to put something
 * down.
 */

/**
 * The piles, each scrollable, each in order.
 *
 * One pile is a graveyard opened from the rail. Several is the counts opened as a set: everything
 * this seat has that is not its graveyard and not its hand — the graveyard has its own card on the
 * rail, and the hand is already on the board.
 *
 * @param piles the zones, from [tableZones]. Empty draws nothing at all, which is the rule every
 *   region on this board follows.
 * @param onDismiss called on a press outside the panel.
 * @param modifier the [Modifier] for the overlay.
 * @param artFor resolves each card's art from the printing the server named.
 * @param onInspect called with a card's id when it is tapped — which opens the card preview, the same
 *   surface every other card on this board opens.
 * @param palette the board's live counter palette, so a counter kind keeps one colour everywhere.
 */
@Composable
fun ZoneViewer(
    piles: List<TableZonePile>,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    artFor: TableArtResolver? = null,
    onInspect: ((String) -> Unit)? = null,
    palette: CounterPalette = rememberCounterPalette(),
) {
    if (piles.isEmpty()) return

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // A sibling behind the panel rather than a wrapper around it, for the reason the card preview
        // learned: wrapped, the scrim's `clickable` merges the whole panel into one accessibility node
        // and every press inside it dismisses.
        Box(
            modifier =
                Modifier
                    .matchParentSize()
                    .background(ScrimColor)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ).testTag(ZoneViewerTestTags.SCRIM),
        )

        Row(
            modifier =
                Modifier
                    .fillMaxHeight(PANEL_HEIGHT_SHARE)
                    .background(BoardSurface.floating, PanelShape)
                    .pointerInput(Unit) { detectTapGestures { } }
                    .padding(PanelPadding)
                    .horizontalScroll(rememberScrollState())
                    .testTag(ZoneViewerTestTags.PANEL),
            horizontalArrangement = Arrangement.spacedBy(PanelPadding),
        ) {
            // A column per pile, side by side, each scrolling on its own. Side by side rather than one
            // list with headings, because these are *different piles* and a single scroll would make
            // "what is in exile" a question about how far down you had got.
            piles.forEach { pile ->
                PileColumn(
                    pile = pile,
                    artFor = artFor,
                    onInspect = onInspect,
                    palette = palette,
                    modifier = Modifier.width(ColumnWidth).fillMaxHeight(),
                )
            }
        }
    }
}

/** One pile: what it is called, how many are in it, and the cards, top of the pile first. */
@Composable
private fun PileColumn(
    pile: TableZonePile,
    artFor: TableArtResolver?,
    onInspect: ((String) -> Unit)?,
    palette: CounterPalette,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.testTag(ZoneViewerTestTags.pile(pile.kind)),
        verticalArrangement = Arrangement.spacedBy(PanelPadding),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = pile.title ?: pile.kind.label,
                style = BoardTypography.promptTitle,
                color = BoardSurface.onSurface,
                modifier = Modifier.testTag(ZoneViewerTestTags.title(pile.kind)),
            )
            Text(
                text = "${pile.count}",
                style = BoardTypography.promptTitle,
                color = BoardSurface.onSurfaceMuted,
                modifier = Modifier.testTag(ZoneViewerTestTags.count(pile.kind)),
            )
        }

        // **An empty pile says so.** It is a real answer — an exile with nothing in it is a fact about
        // the game, and the one a player goes looking for — and a column that opened onto nothing at
        // all would read as a failure to load.
        if (pile.cards.isEmpty()) {
            Text(
                text = EMPTY_PILE,
                style = BoardTypography.promptBody,
                color = BoardSurface.onSurfaceMuted,
                modifier = Modifier.testTag(ZoneViewerTestTags.empty(pile.kind)),
            )
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f).testTag(ZoneViewerTestTags.list(pile.kind)),
            verticalArrangement = Arrangement.spacedBy(CardGap),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Reversed: the server's order is oldest first, and the card on **top** of the pile is the
            // one a player opened this to see.
            items(items = pile.cards.asReversed(), key = { it.id }) { card ->
                BoardCard(
                    state =
                        BoardCardState(
                            card = card.card,
                            power = card.power,
                            toughness = card.toughness,
                            signals = setOfNotNull(card.signal),
                        ),
                    width = CardWidth,
                    // **The art crop, not the whole printing.** `TableCard.art` is the request as the
                    // server named it, at whatever size; a Board-tier card draws the illustration
                    // alone inside its own frame, so handing it a full card scan draws a card inside a
                    // card — a whole printing, borders and text box, shrunk into a name plate.
                    // `boardArt` is the same request at `ART_CROP`, which is what the hand and the
                    // battlefield already ask for.
                    art = artFor?.invoke(card.boardArt, card.card),
                    onTap = onInspect?.let { inspect -> { inspect(card.id) } },
                    counterPalette = palette,
                    modifier = Modifier.testTag(ZoneViewerTestTags.card(card.id)),
                )
            }
        }
    }
}

/** Test tags for the viewer, whose parts are told apart by which pile they belong to. */
object ZoneViewerTestTags {
    const val SCRIM: String = "zone-viewer-scrim"
    const val PANEL: String = "zone-viewer"

    fun pile(kind: TableZoneKind): String = "zone-viewer-pile-$kind"

    fun title(kind: TableZoneKind): String = "zone-viewer-title-$kind"

    fun count(kind: TableZoneKind): String = "zone-viewer-count-$kind"

    fun list(kind: TableZoneKind): String = "zone-viewer-list-$kind"

    fun empty(kind: TableZoneKind): String = "zone-viewer-empty-$kind"

    fun card(cardId: String): String = "zone-viewer-card-$cardId"
}

/** What an opened pile with nothing in it says. */
private const val EMPTY_PILE = "Nothing here."

private val PanelShape = RoundedCornerShape(10.dp)
private val PanelPadding = 10.dp
private val CardGap = 6.dp

/**
 * One column of cards, wide enough to read a name and an illustration at once.
 *
 * A single column rather than a grid: the pile is *ordered*, and a grid asks the reader to work out
 * whether it wraps by row or by column before they can say which card is on top.
 */
private val CardWidth = 132.dp

/** How wide one pile's column is: a card, and the room its own frame needs around it. */
private val ColumnWidth = 150.dp

private const val PANEL_HEIGHT_SHARE = 0.9f

private val ScrimColor = Color.Black.copy(alpha = 0.6f)
