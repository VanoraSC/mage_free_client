package magefree.designsystem.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import magefree.designsystem.board.BoardSurface
import magefree.designsystem.card.BoardAttachment
import magefree.designsystem.card.BoardBadge
import magefree.designsystem.card.BoardCounter
import magefree.designsystem.card.BoardInspectState
import magefree.designsystem.card.BoardInspectView
import magefree.designsystem.card.CardArtSlot
import magefree.designsystem.card.CardDisplay
import magefree.designsystem.card.CounterPalette
import magefree.designsystem.card.InspectBadge
import magefree.designsystem.card.rememberCounterPalette
import magefree.designsystem.theme.MageShapes
import magefree.designsystem.theme.Spacing

/*
 * The board inspect view, on the ground it is actually drawn over.
 *
 * The three examples are chosen to show the panel earning its place rather than to look tidy. A bare
 * permanent has almost nothing to say, and the panel should not pretend otherwise. A heavily enchanted
 * one is where the board's compression is at its most lossy — including an Aura somebody else
 * controls, which is the one fact about an attachment a player cannot get by looking. And a permanent
 * covered in counter kinds this build has never heard of is the case the panel exists for: the board
 * gives each an arbitrary colour off the queue, and only the panel says what any of them are.
 *
 * One palette is shared across all three, deliberately: that is what the board does, and it is the
 * only way to see that a kind keeps its colour wherever it appears.
 */

/**
 * The inspect view in the three states worth looking at.
 *
 * @param modifier the [Modifier] for the gallery.
 * @param artFor resolves card art, as elsewhere in the catalog.
 */
@Composable
fun BoardInspectGallery(
    modifier: Modifier = Modifier,
    artFor: ((String) -> CardArtSlot?)? = null,
) {
    val palette = rememberCounterPalette()

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.medium)) {
        InspectExample("A bare permanent — the panel says only what there is to say", BareBear, palette, artFor)
        InspectExample("Heavily enchanted, including an Aura someone else controls", EnchantedBear, palette, artFor)
        InspectExample("Counter kinds this build has never heard of", CounterCoveredBear, palette, artFor)
    }
}

/** One example: a caption, and the view on the board's own ground at a landscape-shaped size. */
@Composable
private fun InspectExample(
    caption: String,
    state: BoardInspectState,
    palette: CounterPalette,
    artFor: ((String) -> CardArtSlot?)?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall)) {
        Text(text = caption, style = MaterialTheme.typography.labelMedium)
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(ExampleHeight)
                    .background(BoardSurface.ground, MageShapes.medium)
                    .padding(Spacing.small),
        ) {
            BoardInspectView(
                state = state,
                modifier = Modifier.fillMaxWidth().height(ExampleHeight - Spacing.medium),
                counterPalette = palette,
                art = artFor?.invoke(state.card.name),
                attachmentArt = { attachment -> artFor?.invoke(attachment.name) },
            )
        }
    }
}

private val ExampleHeight = 320.dp

private val BearCard = CardDisplay(name = "Grizzly Bears", manaCost = "{1}{G}", typeLine = "Creature — Bear")

private val BareBear =
    BoardInspectState(card = BearCard, power = "2", toughness = "2")

private val EnchantedBear =
    BoardInspectState(
        card = BearCard,
        power = "4",
        toughness = "4",
        tapped = true,
        counters = listOf(BoardCounter("+1/+1", 2)),
        badges =
            listOf(
                InspectBadge(BoardBadge.Flying, detail = "Flying"),
                InspectBadge(BoardBadge.HasRestrictions, detail = "Can't attack or block"),
            ),
        attachments =
            listOf(
                BoardAttachment(name = "Holy Strength", manaCost = "{W}"),
                BoardAttachment(name = "Bonesplitter", manaCost = "{1}", tapped = true),
                // The case worth seeing: their Pacifism on your creature, which looks like yours on the
                // board and is not.
                BoardAttachment(name = "Pacifism", manaCost = "{1}{W}", controlledByOther = true),
            ),
        modifications =
            listOf(
                "Enchanted creature can't attack or block.",
                "Enchanted creature gets +1/+2.",
                "Equipped creature gets +2/+0.",
            ),
    )

private val CounterCoveredBear =
    BoardInspectState(
        card = BearCard,
        power = "2",
        toughness = "2",
        counters =
            listOf(
                BoardCounter("+1/+1", 3),
                BoardCounter("stun", 1),
                BoardCounter("oil", 4),
                BoardCounter("everything", 2),
                BoardCounter("shield", 1),
            ),
    )
