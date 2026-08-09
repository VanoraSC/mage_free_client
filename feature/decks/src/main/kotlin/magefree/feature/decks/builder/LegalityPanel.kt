package magefree.feature.decks.builder

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import magefree.decks.legality.DeckLegalityResult
import magefree.decks.legality.LegalityViolation
import magefree.decks.model.DeckFormat
import magefree.designsystem.theme.MageTheme
import magefree.designsystem.theme.Spacing
import magefree.feature.decks.library.FormatPicker

/**
 * The format picker + live legality panel. Choosing a format runs 0033's offline [DeckLegality]; a
 * legal deck shows a green confirmation, an illegal one lists each structured violation inline. With no
 * format chosen it prompts for one. Stateless — the [result] is computed by [BuilderViewModel].
 */
@Composable
fun LegalityPanel(
    format: DeckFormat?,
    result: DeckLegalityResult?,
    onFormatSelected: (DeckFormat?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = Spacing.medium),
        verticalArrangement = Arrangement.spacedBy(Spacing.small),
    ) {
        FormatPicker(selected = format, onSelected = onFormatSelected)
        when {
            format == null ->
                Text(
                    text = "Pick a format to check legality.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            result == null -> Unit
            result.isLegal ->
                LegalityBanner(
                    legal = true,
                    text = "Legal in ${format.displayName}",
                )
            else ->
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall)) {
                    LegalityBanner(
                        legal = false,
                        text = "${result.violations.size} issue(s) in ${format.displayName}",
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

@Composable
private fun LegalityBanner(
    legal: Boolean,
    text: String,
) {
    val color = if (legal) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = color,
        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.extraSmall),
    )
}

/** Human-readable rendering of a structured [LegalityViolation] for the builder. */
fun LegalityViolation.describe(): String =
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

@Preview(name = "Legality - legal", showBackground = true, widthDp = 360)
@Preview(name = "Legality - illegal (dark)", showBackground = true, widthDp = 360, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun LegalityPreview() {
    MageTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            LegalityPanel(
                format = DeckFormat.MODERN,
                result =
                    DeckLegalityResult(
                        format = DeckFormat.MODERN,
                        isLegal = false,
                        violations =
                            listOf(
                                LegalityViolation.DeckTooSmall(actual = 12, required = 60),
                                LegalityViolation.BannedCard("Splinter Twin"),
                            ),
                    ),
                onFormatSelected = {},
            )
        }
    }
}
