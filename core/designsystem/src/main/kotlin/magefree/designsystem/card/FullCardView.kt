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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import magefree.designsystem.theme.MageTheme
import magefree.designsystem.theme.Sizing
import magefree.designsystem.theme.Spacing

/*
 * The full-bleed card inspection view — card inspection as a first-class, readable surface.
 *
 * A card art thumbnail is unreadable at phone size, so tapping a card opens THIS: a large art region
 * over the card's name, mana cost, type line, and scrollable oracle text, plus optional sections for
 * the card's current modifications and its activatable abilities (each a parameter list, empty by
 * default). Content scrolls so a text-heavy card stays readable, and a prominent, labelled close
 * control sits over the art. Stateless — art comes through a [CardArtSlot] and every event is hoisted.
 */

/**
 * The full-bleed card inspection surface.
 *
 * @param card the display fields for the card.
 * @param onClose invoked when the close control is activated.
 * @param modifier the [Modifier] for the surface.
 * @param art the card-art slot; when null the built-in [CardArtPlaceholder] is shown.
 * @param modifications the card's current modifications (e.g. "+1/+1 counter"); a section renders only
 *   when this is non-empty.
 * @param activatableAbilities the card's activatable abilities; a section renders only when non-empty.
 * @param closeContentDescription the accessibility label for the close control.
 */
@Composable
fun FullCardView(
    card: CardDisplay,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    art: CardArtSlot? = null,
    modifications: List<String> = emptyList(),
    activatableAbilities: List<String> = emptyList(),
    closeContentDescription: String = "Close card",
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
            ) {
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
                            .padding(Spacing.medium),
                    verticalArrangement = Arrangement.spacedBy(Spacing.medium),
                ) {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .semantics { heading() },
                        horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = card.name,
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        card.manaCost?.takeIf { it.isNotBlank() }?.let { cost ->
                            Text(
                                text = cost,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    card.typeLine?.takeIf { it.isNotBlank() }?.let { typeLine ->
                        Text(
                            text = typeLine,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    card.oracleText?.takeIf { it.isNotBlank() }?.let { oracle ->
                        Text(
                            text = oracle,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }

                    CardDetailSection(title = "Current modifications", items = modifications)
                    CardDetailSection(title = "Activatable abilities", items = activatableAbilities)
                }
            }

            // Prominent, labelled close control over the art (>= 48dp via IconButton).
            IconButton(
                onClick = onClose,
                modifier =
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(Spacing.small),
            ) {
                Box(
                    modifier =
                        Modifier
                            .background(
                                color = MaterialTheme.colorScheme.surface,
                                shape = CircleShape,
                            ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = closeContentDescription,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(Spacing.extraSmall).size(Sizing.iconSmall),
                    )
                }
            }
        }
    }
}

/**
 * A titled detail section (modifications / abilities) rendered only when [items] is non-empty. Its
 * heading aligns with the surrounding body text (unlike [MageSectionHeader], which owns screen-edge
 * padding), keeping the inspection column on one left margin.
 */
@Composable
private fun CardDetailSection(
    title: String,
    items: List<String>,
) {
    if (items.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        items.forEach { item ->
            Text(
                text = item,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

private val SampleCard =
    CardDisplay(
        name = "Lightning Bolt",
        manaCost = "R",
        typeLine = "Instant",
        oracleText = "Lightning Bolt deals 3 damage to any target.",
    )

private val SampleTextHeavyCard =
    CardDisplay(
        name = "Elspeth, Sun's Champion",
        manaCost = "4WW",
        typeLine = "Legendary Planeswalker — Elspeth",
        oracleText =
            "+1: Create three 1/1 white Soldier creature tokens.\n\n" +
                "-3: Destroy all creatures with power 4 or greater.\n\n" +
                "-7: You get an emblem with \"Creatures you control get +2/+2 and have flying.\"\n\n" +
                "Loyalty counters remain on Elspeth as long as she is on the battlefield. If her " +
                "loyalty reaches zero, she is put into her owner's graveyard.",
    )

@Preview(name = "FullCardView - light", showBackground = true, heightDp = 720)
@Preview(
    name = "FullCardView - dark",
    showBackground = true,
    heightDp = 720,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun FullCardViewPreview() {
    MageTheme {
        FullCardView(
            card = SampleCard,
            onClose = {},
            // A solid stand-in art slot (never a network image; no image dependency).
            art = { artModifier -> Box(artModifier.background(Color(0xFFB5462A))) },
            modifications = listOf("+1/+1 counter", "Gains haste until end of turn"),
            activatableAbilities = listOf("{T}: Deal 1 damage to any target"),
        )
    }
}

@Preview(name = "FullCardView - text heavy, placeholder art", showBackground = true, heightDp = 720)
@Composable
private fun FullCardViewTextHeavyPreview() {
    MageTheme {
        // Art missing -> built-in placeholder; long oracle text stays readable and scrolls.
        FullCardView(
            card = SampleTextHeavyCard,
            onClose = {},
        )
    }
}
