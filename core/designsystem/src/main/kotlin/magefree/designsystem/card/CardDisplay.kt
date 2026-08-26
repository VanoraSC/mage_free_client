package magefree.designsystem.card

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import magefree.designsystem.theme.Spacing

/*
 * The card display model and the abstract art-slot contract shared by the card-forward components.
 *
 * [CardDisplay] is a minimal, presentation-only view model: the display fields a card surface needs,
 * with no data source, image handle, or in-game state. Real card data (oracle text, art, rulings,
 * modifications) is mapped into this shape by later epics.
 *
 * [CardArtSlot] is the abstract art contract — a `@Composable (Modifier) -> Unit` slot. The design
 * system carries NO image-loading dependency: the card layer supplies a Coil-backed slot here
 * without changing these components. When a caller supplies no slot, the components render the
 * built-in [CardArtPlaceholder] so the shell degrades gracefully while art is loading or absent.
 */

/**
 * The abstract card-art slot: a composable that fills the [Modifier] it is handed with the card's art.
 *
 * The design system never loads images itself; a caller backs this slot with a real,
 * disk-cached loader. Previews use a solid/placeholder painter, never a network image.
 */
typealias CardArtSlot = @Composable (Modifier) -> Unit

/**
 * The width-to-height ratio of a Magic card face (63mm x 88mm ~= 0.716). Art regions use this so tiles
 * and the full view keep a card-true shape at any width.
 */
const val CARD_ASPECT_RATIO: Float = 0.716f

/**
 * Minimal, presentation-only display fields for a card surface. Carries no data source, image handle,
 * or in-game state — those are supplied by later epics through the art slot and parameter lists.
 *
 * @param name the card name (always present).
 * @param manaCost an optional human-readable mana cost (e.g. "1RR"); shown beside the name.
 * @param typeLine an optional type line (e.g. "Creature — Elf Warrior").
 * @param oracleText the optional rules / oracle text shown in the full inspection view.
 * @param contentDescription an optional accessibility override; when null a summary is built from the
 *   display fields via [accessibleSummary].
 */
data class CardDisplay(
    val name: String,
    val manaCost: String? = null,
    val typeLine: String? = null,
    val oracleText: String? = null,
    val contentDescription: String? = null,
) {
    /** The short summary announced for a compact surface (tile): the explicit override, else name/cost/type. */
    val accessibleSummary: String
        get() = contentDescription ?: buildCardContentDescription(this)
}

/**
 * Builds the accessibility summary for a [card]: its name, then mana cost and type line when present,
 * and — only for the full inspection view — its oracle text. Pure logic, unit-tested.
 */
internal fun buildCardContentDescription(
    card: CardDisplay,
    includeOracleText: Boolean = false,
): String =
    buildString {
        append(card.name)
        card.manaCost?.takeIf { it.isNotBlank() }?.let { append(", mana cost $it") }
        card.typeLine?.takeIf { it.isNotBlank() }?.let { append(", $it") }
        if (includeOracleText) {
            card.oracleText?.takeIf { it.isNotBlank() }?.let { append(". $it") }
        }
    }

/**
 * The built-in placeholder rendered when no [CardArtSlot] is supplied (art loading or absent). A plain,
 * token-styled surface showing the card name so the shell stays readable without any image dependency.
 * Purely decorative — the enclosing component owns the accessible description, so this clears semantics.
 *
 * @param card the card whose name labels the placeholder.
 * @param modifier the [Modifier] for the placeholder region (typically filling the art slot).
 */
@Composable
fun CardArtPlaceholder(
    card: CardDisplay,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = card.name,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(Spacing.medium),
        )
    }
}
