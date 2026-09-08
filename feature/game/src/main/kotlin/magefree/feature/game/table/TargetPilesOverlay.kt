package magefree.feature.game.table

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import magefree.designsystem.board.BoardSignal
import magefree.designsystem.board.BoardSurface
import magefree.designsystem.board.BoardTypography
import magefree.designsystem.card.BoardCard
import magefree.feature.game.board.BoardAction
import magefree.feature.game.board.ControlButton

/*
 * The two piles, on screen.
 *
 * **The overlay is where a set is assembled, because the board cannot show one.** Everything about the
 * board draws one card at a time being pressed; a partition is two groups, and the only honest way to
 * show which group a card is in is to put it there. Column membership *is* the answer, which is also
 * why no eighth board colour was invented for "chosen" — see `PromptPicks`.
 *
 * **Tap and drag do the same thing, deliberately.** Both send one `chooseTarget` for the card moved,
 * which upstream toggles: `HumanPlayer.choose` removes a target sent a second time. So a move in
 * either direction, by either gesture, is one message and needs no new verb — and a player who
 * reaches for the gesture the piles suggest finds it, while a player who just taps is not punished
 * for it.
 *
 * **Nothing here is local state except the drag in flight.** The columns are recomputed from the
 * server's own `chosenTargets` on every snapshot, so a move that the server declines simply does not
 * happen on screen, and a move it accepts appears when it has actually been made.
 */

/**
 * The two-column overlay for a target prompt whose candidates are on the board.
 *
 * @param piles the question and its two columns.
 * @param artFor the board's own art resolver, so a card reads identically to the one on the table.
 * @param onMove a card was moved between columns — one `chooseTarget`, the server decides which way.
 * @param onAction one of the prompt's own buttons.
 * @param onCollapse hide the overlay to read the board underneath. It is a look, not an answer: the
 *   question stays outstanding and the overlay comes back.
 */
@Composable
fun TargetPilesOverlay(
    piles: TargetPiles,
    artFor: TableArtResolver?,
    onMove: (String) -> Unit,
    onAction: (BoardAction) -> Unit,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Where each column is on screen, so a drag can be resolved to the column it ended over. Measured
    // rather than assumed: the two columns share the width and the split moves with the panel.
    var availableBounds by remember { mutableStateOf(Rect.Zero) }
    var chosenBounds by remember { mutableStateOf(Rect.Zero) }

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(ScrimColor)
                .testTag(TargetPilesTestTags.SCRIM),
    ) {
        Column(
            modifier =
                Modifier
                    .align(Alignment.Center)
                    .fillMaxSize()
                    .padding(PanelPadding)
                    .clip(PanelShape)
                    .background(BoardSurface.zone)
                    .padding(PanelPadding)
                    .testTag(TargetPilesTestTags.PANEL),
            verticalArrangement = Arrangement.spacedBy(Gap),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = piles.message.orEmpty(),
                    style = BoardTypography.annotation,
                    color = BoardSurface.onSurface,
                    modifier = Modifier.testTag(TargetPilesTestTags.MESSAGE),
                )
                Text(
                    text = SHOW_BATTLEFIELD_LABEL,
                    style = BoardTypography.counter,
                    color = BoardSurface.onSurfaceMuted,
                    modifier =
                        Modifier
                            .clip(PanelShape)
                            .pointerInput(Unit) { detectTapGestures { onCollapse() } }
                            .padding(horizontal = Gap, vertical = 2.dp)
                            .testTag(TargetPilesTestTags.COLLAPSE),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(Gap),
            ) {
                PileColumn(
                    title = AVAILABLE_TITLE,
                    cards = piles.available,
                    artFor = artFor,
                    tag = TargetPilesTestTags.AVAILABLE,
                    onMove = onMove,
                    // A card dragged out of this column is answered when it lands over the other one.
                    dropTarget = { chosenBounds },
                    onBoundsChanged = { availableBounds = it },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
                PileColumn(
                    title = CHOSEN_TITLE,
                    cards = piles.chosen,
                    artFor = artFor,
                    tag = TargetPilesTestTags.CHOSEN,
                    onMove = onMove,
                    dropTarget = { availableBounds },
                    onBoundsChanged = { chosenBounds = it },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().testTag(TargetPilesTestTags.BUTTONS),
                horizontalArrangement = Arrangement.spacedBy(Gap, Alignment.End),
            ) {
                piles.buttons.forEach { button -> PileButton(button = button, onAction = onAction) }
            }
        }
    }
}

/**
 * One column.
 *
 * **The header says how many, because the count is the thing being decided.** "Sacrifice all
 * permanents in the pile of their choice" is a question about sizes as much as contents, and a player
 * splitting eight permanents is counting.
 */
@Composable
private fun PileColumn(
    title: String,
    cards: List<PileCard>,
    artFor: TableArtResolver?,
    tag: String,
    onMove: (String) -> Unit,
    dropTarget: () -> Rect,
    onBoundsChanged: (Rect) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .onGloballyPositioned { onBoundsChanged(it.boundsInRoot()) }
                .clip(PanelShape)
                .border(ColumnBorder, BoardSurface.onSurfaceMuted, PanelShape)
                .padding(Gap)
                .testTag(tag),
        verticalArrangement = Arrangement.spacedBy(Gap),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "$title (${cards.size})",
            style = BoardTypography.counter,
            color = BoardSurface.onSurfaceMuted,
            modifier = Modifier.testTag(TargetPilesTestTags.count(tag)),
        )
        Column(
            modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Gap),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            cards.forEach { card ->
                PileCardView(
                    card = card,
                    artFor = artFor,
                    onMove = onMove,
                    dropTarget = dropTarget,
                )
            }
        }
    }
}

/**
 * One card in a column.
 *
 * **A tap moves it and a long press drags it.** They are the same message; the split is about which
 * gesture the player reaches for, not about what each one means. Reading a card is the third gesture
 * and it is deliberately the least prominent one here — a player in this overlay is sorting, not
 * studying, and the raised card is a press away on the board they came from.
 */
@Composable
private fun PileCardView(
    card: PileCard,
    artFor: TableArtResolver?,
    onMove: (String) -> Unit,
    dropTarget: () -> Rect,
) {
    var bounds by remember { mutableStateOf(Rect.Zero) }
    var drag by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier =
            Modifier
                .onGloballyPositioned { bounds = it.boundsInRoot() }
                .pointerInput(card.id) {
                    detectDragGesturesAfterLongPress(
                        onDragEnd = {
                            // The card is answered when it was let go over the *other* column. A drag
                            // that ends where it started is not a move, and saying so beats guessing.
                            if (dropTarget().overlaps(bounds.translate(drag))) onMove(card.id)
                            drag = Offset.Zero
                        },
                        onDragCancel = { drag = Offset.Zero },
                        onDrag = { change, amount ->
                            change.consume()
                            drag += amount
                        },
                    )
                }.pointerInput(card.id) {
                    detectTapGestures(onTap = { onMove(card.id) })
                }.testTag(TargetPilesTestTags.card(card.id)),
    ) {
        BoardCard(
            state = card.state,
            width = PileCardWidth,
            art = artFor?.invoke(card.art, card.state.card),
            // The card follows the finger without leaving its slot in the column: the layout does not
            // reflow mid-drag, so the two piles stay where the player is aiming at them.
            modifier =
                Modifier.graphicsLayer {
                    translationX = drag.x
                    translationY = drag.y
                },
        )
    }
}

@Composable
private fun PileButton(
    button: ControlButton,
    onAction: (BoardAction) -> Unit,
) {
    Text(
        text = button.label,
        style = BoardTypography.counter,
        color = if (button.isPrimary) BoardSignal.playable else BoardSurface.onSurface,
        modifier =
            Modifier
                .clip(PanelShape)
                .border(ColumnBorder, BoardSurface.onSurfaceMuted, PanelShape)
                .pointerInput(button.label) { detectTapGestures { onAction(button.action) } }
                .padding(horizontal = ButtonPadding, vertical = Gap)
                .testTag(TargetPilesTestTags.button(button.label)),
    )
}

/** Test tags for the overlay and its parts. */
object TargetPilesTestTags {
    const val SCRIM: String = "target-piles-scrim"
    const val PANEL: String = "target-piles-panel"
    const val MESSAGE: String = "target-piles-message"
    const val COLLAPSE: String = "target-piles-collapse"
    const val AVAILABLE: String = "target-piles-available"
    const val CHOSEN: String = "target-piles-chosen"
    const val BUTTONS: String = "target-piles-buttons"

    /** The count on one column's header. */
    fun count(columnTag: String): String = "$columnTag-count"

    /** One card, wherever it currently sits. */
    fun card(id: String): String = "target-piles-card-$id"

    /** One of the prompt's own buttons. */
    fun button(label: String): String = "target-piles-button-$label"
}

private const val AVAILABLE_TITLE = "Not chosen"

private const val CHOSEN_TITLE = "Chosen"

/** The same wording the controls panel uses, because it is the same act. */
private const val SHOW_BATTLEFIELD_LABEL = "Show battlefield"

private val ScrimColor = Color.Black.copy(alpha = 0.72f)
private val PanelShape = RoundedCornerShape(8.dp)
private val PanelPadding = 8.dp
private val Gap = 6.dp
private val ColumnBorder = 1.dp
private val ButtonPadding = 12.dp
private val PileCardWidth = 96.dp
