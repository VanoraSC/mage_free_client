package magefree.feature.tables

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import magefree.decks.legality.DeckLegalityResult
import magefree.decks.legality.LegalityViolation
import magefree.decks.model.DeckFormat
import magefree.decks.model.DeckId
import magefree.decks.model.DeckSummary
import magefree.designsystem.component.EmptyState
import magefree.designsystem.component.MageListRow
import magefree.designsystem.theme.Spacing

/**
 * A stateless saved-deck picker over the offline library. Each deck is a single-select radio row showing
 * its name, format, and main/sideboard totals; an empty library renders the "build a legal deck" call to
 * action ([onBuildDeck]). Selection is hoisted ([onSelect]); no I/O happens here.
 */
@Composable
fun DeckPicker(
    decks: List<DeckSummary>,
    selectedId: DeckId?,
    onSelect: (DeckId) -> Unit,
    onBuildDeck: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (decks.isEmpty()) {
        EmptyState(
            modifier = modifier,
            title = "No saved decks",
            message = "You need a legal deck to sit down. Build one in the Decks tab, then come back.",
            actionLabel = "Build a deck",
            onAction = onBuildDeck,
        )
        return
    }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall)) {
        decks.forEach { deck ->
            val selected = deck.id == selectedId
            MageListRow(
                headline = deck.name,
                supportingText = deckSubtitle(deck),
                leadingContent = {
                    RadioButton(selected = selected, onClick = { onSelect(deck.id) })
                },
                modifier = Modifier.selectable(selected = selected, onClick = { onSelect(deck.id) }),
            )
        }
    }
}

/** A subtitle line for a deck row: format (when set) plus main/sideboard totals. */
private fun deckSubtitle(deck: DeckSummary): String {
    val format = deck.format?.displayName?.let { "$it · " } ?: ""
    return "$format${deck.mainCount} main / ${deck.sideboardCount} side"
}

/**
 * A stateless legality summary for the picked deck against the table's [format]. A legal deck shows a
 * green confirmation; an illegal one lists each structured violation; an unrecognised [format] explains
 * that the server will be the authority. Nothing renders until a deck is picked (a `null` [result] with a
 * known format).
 */
@Composable
fun LegalitySummary(
    format: DeckFormat?,
    result: DeckLegalityResult?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall)) {
        when {
            format == null ->
                Text(
                    text = "Format not recognised offline — the server checks legality when you sit down.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            result == null -> Unit
            result.isLegal ->
                Text(
                    text = "Legal in ${format.displayName}",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            else -> {
                Text(
                    text = "${result.violations.size} issue(s) in ${format.displayName}",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.error,
                )
                result.violations.forEach { violation ->
                    Text(
                        text = "• ${violation.describe()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Human-readable rendering of a structured [LegalityViolation] (mirrors the builder wording). */
private fun LegalityViolation.describe(): String =
    when (this) {
        is LegalityViolation.UnknownFormat -> "No bundled rules for '$formatKey' — legality can't be checked."
        is LegalityViolation.DeckTooSmall -> "Deck has $actual cards; needs at least $required."
        is LegalityViolation.SideboardTooLarge -> "Sideboard has $actual cards; max is $max."
        is LegalityViolation.BannedCard -> "$cardName is banned."
        is LegalityViolation.RestrictedOverLimit -> "$cardName is restricted (max 1); found $count."
        is LegalityViolation.TooManyCopies -> "$cardName appears $count times; max is $max."
        is LegalityViolation.IllegalSet -> "$cardName has no printing legal in this format."
        is LegalityViolation.InvalidRarity -> "$cardName is not a legal rarity for this format."
    }
