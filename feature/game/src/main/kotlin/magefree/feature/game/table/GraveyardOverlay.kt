package magefree.feature.game.table

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import magefree.designsystem.board.BoardSurface
import magefree.designsystem.board.BoardTypography
import magefree.designsystem.card.CardTile

/*
 * A graveyard, opened.
 *
 * §7.13's browser, for the one zone that needs no decisions made about it first: a graveyard is one
 * pile with one meaningful order, so there is nothing to group and nothing to name. Exile is the hard
 * case and it is a separate story.
 *
 * **Opening a zone is a look, not a decision** (§7.1, §7.4). It floats over the board, it takes
 * nothing from the battlefield, and it closes on a press outside — the same gesture that closes the
 * card preview and the vitals overlay, because a player should not have to learn a way to put down
 * each different thing they picked up.
 *
 * **The cards are in the server's order, newest last.** That is the order they went in, and the top of
 * a graveyard means the last entry to every effect that names one. The browser shows it as it is.
 */

/**
 * The cards in one graveyard.
 *
 * @param graveyard the zone, from [tableGraveyards].
 * @param onDismiss called on a press outside the panel.
 * @param modifier the [Modifier] for the overlay.
 * @param artFor resolves each card's art from the printing the server named.
 * @param onInspect called with a card's id when it is tapped — which opens the card preview, the same
 *   surface a card in hand opens, because reading a card is one thing wherever the card is.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GraveyardOverlay(
    graveyard: TableGraveyard,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    artFor: TableArtResolver? = null,
    onInspect: ((String) -> Unit)? = null,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // A sibling behind the panel rather than a wrapper around it: wrapped, the scrim's `clickable`
        // merges the whole panel into one node and every press inside it dismisses.
        Box(
            modifier =
                Modifier
                    .matchParentSize()
                    .background(ScrimColor)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ).testTag(GraveyardOverlayTestTags.SCRIM),
        )

        Column(
            modifier =
                Modifier
                    .fillMaxWidth(PANEL_WIDTH_SHARE)
                    .fillMaxHeight(PANEL_HEIGHT_SHARE)
                    .background(BoardSurface.floating, PanelShape)
                    .pointerInput(Unit) { detectTapGestures { } }
                    .padding(PanelPadding)
                    .testTag(GraveyardOverlayTestTags.PANEL),
            verticalArrangement = Arrangement.spacedBy(PanelPadding),
        ) {
            Text(
                text = "$GRAVEYARD_LABEL — ${graveyard.count}",
                style = BoardTypography.promptBody,
                color = BoardSurface.onSurface,
                modifier = Modifier.testTag(GraveyardOverlayTestTags.TITLE),
            )

            if (graveyard.cards.isEmpty()) {
                Text(
                    text = EMPTY_MESSAGE,
                    style = BoardTypography.annotation,
                    color = BoardSurface.onSurfaceMuted,
                    modifier = Modifier.testTag(GraveyardOverlayTestTags.EMPTY),
                )
                return@Column
            }

            // Wrapping rather than one long line: a graveyard is read by scanning it, and a row that
            // scrolled sideways would put half of a big one off screen in the direction a player is
            // least likely to look. It scrolls down, which is the direction a list of anything does.
            FlowRow(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(TileGap),
                verticalArrangement = Arrangement.spacedBy(TileGap),
            ) {
                graveyard.cards.forEach { card ->
                    CardTile(
                        card = card.card,
                        onTap = { onInspect?.invoke(card.id) },
                        art = artFor?.invoke(card.art, card.card),
                        caption = false,
                        signal = card.signal,
                        modifier =
                            Modifier
                                .width(TileWidth)
                                .testTag(GraveyardOverlayTestTags.card(card.id)),
                    )
                }
            }
        }
    }
}

/** Test tags for the overlay, whose parts are told apart by position rather than by text. */
object GraveyardOverlayTestTags {
    const val SCRIM: String = "graveyard-scrim"
    const val PANEL: String = "graveyard-panel"
    const val TITLE: String = "graveyard-title"
    const val EMPTY: String = "graveyard-empty"

    /** One card in the zone, by its server object id. */
    fun card(cardId: String): String = "graveyard-card-$cardId"
}

/** What an opened but empty graveyard says, since the panel is open and has to say something. */
private const val EMPTY_MESSAGE = "Nothing here yet."

/**
 * Dark enough to say the board is not the thing being touched, light enough to still read it.
 *
 * The same value the vitals overlay uses: two floating surfaces that dimmed the board by different
 * amounts would read as two different kinds of thing.
 */
private val ScrimColor = Color.Black.copy(alpha = 0.6f)

private const val PANEL_WIDTH_SHARE = 0.8f
private const val PANEL_HEIGHT_SHARE = 0.85f
private val PanelShape = RoundedCornerShape(8.dp)
private val PanelPadding = 12.dp
private val TileGap = 6.dp

/** Tile tier, at the size §7.5 draws a zone browser: readable without being a card in play. */
private val TileWidth: Dp = 96.dp
