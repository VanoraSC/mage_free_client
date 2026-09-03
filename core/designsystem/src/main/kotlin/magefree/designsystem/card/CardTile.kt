package magefree.designsystem.card

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import magefree.designsystem.text.SymbolText
import magefree.designsystem.theme.Elevation
import magefree.designsystem.theme.MageShapes
import magefree.designsystem.theme.MageTheme
import magefree.designsystem.theme.Spacing

/*
 * The card tile — a compact, tappable representation of a single card.
 *
 * A card-shaped art region (backed by a [CardArtSlot], or the built-in [CardArtPlaceholder] when none
 * is supplied) above a short caption (name + mana cost, then the type line). The whole tile is a single
 * accessible, inspectable target: it reads as one card via a merged content description summarizing the
 * card, and it wires the tap-to-open / long-press-to-peek pattern via [Modifier.cardInspectable]. It is
 * stateless — the caller owns the art slot and the tap/peek events.
 */

/**
 * A compact, tappable card tile.
 *
 * @param card the display fields for the card.
 * @param onTap invoked on tap — opens the full inspection view (the guaranteed path).
 * @param modifier the [Modifier] for the tile.
 * @param art the card-art slot; when null the built-in [CardArtPlaceholder] is shown.
 * @param onLongPressPeek optional long-press accelerator that peeks at the card.
 * @param peekLabel accessibility label for the long-press action.
 */
@Composable
fun CardTile(
    card: CardDisplay,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    art: CardArtSlot? = null,
    onLongPressPeek: (() -> Unit)? = null,
    peekLabel: String = "Peek at card",
) {
    val summary = card.accessibleSummary
    Surface(
        modifier =
            modifier
                .semantics(mergeDescendants = true) {
                    contentDescription = summary
                    role = Role.Button
                }.cardInspectable(
                    onTap = onTap,
                    onLongPressPeek = onLongPressPeek,
                    tapLabel = "Open card",
                    peekLabel = peekLabel,
                ),
        shape = MageShapes.medium,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = Elevation.level1,
        shadowElevation = Elevation.level1,
    ) {
        Column {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(CARD_ASPECT_RATIO),
            ) {
                CardArtRegion(card = card, art = art, modifier = Modifier.fillMaxSize())
            }
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.small, vertical = Spacing.extraSmall),
                verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                ) {
                    Text(
                        text = card.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    card.manaCost?.takeIf { it.isNotBlank() }?.let { cost ->
                        SymbolText(
                            text = cost,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
                card.typeLine?.takeIf { it.isNotBlank() }?.let { typeLine ->
                    Text(
                        text = typeLine,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** Renders the supplied [art] slot, falling back to the built-in placeholder when none is given. */
@Composable
internal fun CardArtRegion(
    card: CardDisplay,
    art: CardArtSlot?,
    modifier: Modifier = Modifier,
) {
    if (art != null) {
        art(modifier)
    } else {
        CardArtPlaceholder(card = card, modifier = modifier)
    }
}

private val SampleCreature =
    CardDisplay(
        name = "Grizzly Bears",
        manaCost = "{1}{G}",
        typeLine = "Creature — Bear",
    )

private val SampleLongName =
    CardDisplay(
        name = "Rin and Seri, Inseparable",
        manaCost = "{1}{R}{G}{W}",
        typeLine = "Legendary Creature — Dog Cat",
    )

@Preview(name = "CardTile - light", showBackground = true)
@Preview(
    name = "CardTile - dark",
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun CardTilePreview() {
    MageTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            Row(
                modifier = Modifier.padding(Spacing.medium),
                horizontalArrangement = Arrangement.spacedBy(Spacing.medium),
            ) {
                // Art present — a solid stand-in slot (never a network image; no image dependency).
                CardTile(
                    card = SampleCreature,
                    onTap = {},
                    onLongPressPeek = {},
                    art = { artModifier -> Box(artModifier.background(Color(0xFF3B6E3B))) },
                    modifier = Modifier.width(140.dp),
                )
                // Art missing — the built-in placeholder, with a long name.
                CardTile(
                    card = SampleLongName,
                    onTap = {},
                    modifier = Modifier.width(140.dp),
                )
            }
        }
    }
}
