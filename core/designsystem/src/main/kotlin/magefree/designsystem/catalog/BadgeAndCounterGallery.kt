package magefree.designsystem.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import magefree.designsystem.board.BoardSurface
import magefree.designsystem.card.BadgeSquare
import magefree.designsystem.card.BoardBadge
import magefree.designsystem.card.BoardCounter
import magefree.designsystem.card.CounterCircle
import magefree.designsystem.card.rememberCounterPalette
import magefree.designsystem.theme.Spacing

/*
 * Every badge and a spread of counters, at the size the board actually draws them.
 *
 * This exists because the board gallery can only show two or three at a time on a card, and the
 * question these decorations have to answer is *can you tell them apart at 13dp*. Seen together, a
 * wrong or duplicated glyph is obvious; seen one per card, it is not.
 *
 * The last entries of each row are the ones to check hardest. Three badges have no symbol at all — a
 * restriction and a target are game state rather than keywords, and an unrecognised icon is by
 * definition unknown — and they must still show their short form rather than an empty plate. A counter
 * kind the font has never heard of must still show its colour and its count, because that is most
 * counter kinds: the set is open and runs to hundreds.
 */

/** Every [BoardBadge] and a spread of counter kinds, drawn exactly as the board draws them. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BadgeAndCounterGallery(modifier: Modifier = Modifier) {
    val palette = rememberCounterPalette()

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.small),
    ) {
        GalleryCaption("Every badge, at board size — the last three have no symbol and keep their short form")
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.small),
            verticalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            BoardBadge.entries.forEach { badge ->
                Labelled(text = badge.label) { BadgeSquare(badge) }
            }
        }

        GalleryCaption("Counters — the symbol says which kind, the colour still separates the ones with none")
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.small),
            verticalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            CatalogCounters.forEach { counter ->
                Labelled(text = counter.name) { CounterCircle(counter = counter, palette = palette) }
            }
        }
    }
}

/** One entry: the decoration at its real size, with the name it stands for underneath. */
@Composable
private fun Labelled(
    text: String,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier.width(EntryWidth),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
    ) {
        content()
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = BoardSurface.onSurfaceMuted,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * The counter kinds worth looking at side by side.
 *
 * An even and an uneven boost, because the font draws those differently and the difference is easy to
 * lose; the two that a modern board is mostly made of; and two the font has no symbol for at all,
 * which is the ordinary case and has to keep working.
 */
private val CatalogCounters =
    listOf(
        BoardCounter("+1/+1", 3),
        BoardCounter("-1/-1", 1),
        BoardCounter("+1/+0", 2),
        BoardCounter("loyalty", 4),
        BoardCounter("charge", 7),
        BoardCounter("stun", 1),
        BoardCounter("shield", 1),
        BoardCounter("lore", 2),
        BoardCounter("energy", 12),
        BoardCounter("time", 3),
        BoardCounter("poison", 5),
        BoardCounter("moonsilver", 1),
    )

/** Wide enough for the longest keyword to wrap to two lines rather than being clipped. */
private val EntryWidth = 76.dp
