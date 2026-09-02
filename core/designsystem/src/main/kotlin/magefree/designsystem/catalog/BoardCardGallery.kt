package magefree.designsystem.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import magefree.designsystem.board.BoardSurface
import magefree.designsystem.card.BoardCard
import magefree.designsystem.card.BoardCardSignal
import magefree.designsystem.card.BoardCardState
import magefree.designsystem.card.BoardCounter
import magefree.designsystem.card.CardDisplay
import magefree.designsystem.theme.MageShapes
import magefree.designsystem.theme.Spacing

/*
 * The Board card tier, rendered on the ground it is actually drawn on.
 *
 * Board cards are small, and the whole question about them is whether they stay readable at that size
 * — a name, a stat line, a counter, and which signal a border is carrying. Showing them on the board's
 * own grey rather than on the catalog's surface is the point: a signal that reads against a light
 * card-list background may not read against the battlefield.
 */

/** Every Board-tier state side by side: resting, tapped, countered, and each signal. */
@Composable
internal fun BoardCardGallery(modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.medium)) {
        GalleryCaption("Resting, tapped, and carrying counters — all at board size")
        BoardRow {
            LabelledCard("resting", BoardCardState(card = BEARS, power = "2", toughness = "2"))
            LabelledCard("tapped", BoardCardState(card = BEARS, power = "2", toughness = "2", tapped = true))
            LabelledCard(
                "counters",
                BoardCardState(
                    card = BEARS,
                    power = "4",
                    toughness = "4",
                    counters = listOf(BoardCounter("+1/+1", 2)),
                ),
            )
            LabelledCard(
                "many counters",
                BoardCardState(
                    card = BEARS,
                    power = "2",
                    toughness = "2",
                    counters =
                        listOf(
                            BoardCounter("+1/+1", 2),
                            BoardCounter("poison", 1),
                            BoardCounter("energy", 3),
                            BoardCounter("charge", 4),
                        ),
                ),
            )
            LabelledCard("no stats", BoardCardState(card = FOREST))
        }

        GalleryCaption("One signal per card — is each obvious, and is any pair confusable?")
        BoardRow {
            BoardCardSignal.entries.forEach { signal ->
                LabelledCard(
                    signal.name.lowercase(),
                    BoardCardState(card = BEARS, power = "2", toughness = "2", signals = setOf(signal)),
                )
            }
        }

        GalleryCaption("Signals at once: the border takes the most immediate, pips carry the rest")
        BoardRow {
            LabelledCard(
                "attacking + targeted",
                BoardCardState(
                    card = BEARS,
                    power = "2",
                    toughness = "2",
                    signals = setOf(BoardCardSignal.Attacking, BoardCardSignal.Targeted),
                ),
            )
            LabelledCard(
                "playable + pending cost",
                BoardCardState(
                    card = BEARS,
                    signals = setOf(BoardCardSignal.Playable, BoardCardSignal.PendingCost),
                ),
            )
            LabelledCard(
                "all six",
                BoardCardState(
                    card = BEARS,
                    power = "2",
                    toughness = "2",
                    signals = BoardCardSignal.entries.toSet(),
                ),
            )
        }
    }
}

/** A strip of the board's own ground, so the cards are judged against what they sit on. */
@Composable
private fun BoardRow(content: @Composable () -> Unit) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(BoardSurface.zone, MageShapes.medium)
                .padding(Spacing.small),
        horizontalArrangement = Arrangement.spacedBy(Spacing.small),
        verticalAlignment = Alignment.Bottom,
    ) {
        content()
    }
}

/** One card with its state named beneath it, since a border colour alone does not say what it means. */
@Composable
private fun LabelledCard(
    label: String,
    state: BoardCardState,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        BoardCard(state = state, width = GalleryCardWidth)
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = BoardSurface.onSurfaceMuted)
    }
}

/** A caption in the surrounding app theme, so the board's colours never explain themselves. */
@Composable
private fun GalleryCaption(text: String) {
    Text(text = text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Board size, near the small end of what the battlefield will derive, so legibility is judged honestly. */
private val GalleryCardWidth = 64.dp

private val BEARS = CardDisplay(name = "Grizzly Bears", manaCost = "1G", typeLine = "Creature — Bear")
private val FOREST = CardDisplay(name = "Forest", typeLine = "Basic Land — Forest")
