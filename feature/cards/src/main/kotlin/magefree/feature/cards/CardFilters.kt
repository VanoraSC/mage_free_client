package magefree.feature.cards

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import magefree.cards.model.CardColor
import magefree.cards.model.Rarity
import magefree.designsystem.component.MageTextButton
import magefree.designsystem.theme.MageTheme
import magefree.designsystem.theme.Spacing

/**
 * The card browser's client-side filter controls: scrollable rows of WUBRG color chips, card-type,
 * mana-value, and rarity chips, plus a "showing N" count with a reset affordance when filters are
 * active. Stateless — the active [CardBrowseFilters] and every change flow through hoisted callbacks;
 * filtering itself happens in the catalog (via the ViewModel).
 */
@Composable
fun CardFilters(
    uiState: CardSearchUiState,
    onColorToggle: (CardColor) -> Unit,
    onCardTypeSelected: (String?) -> Unit,
    onManaValueSelected: (Int?) -> Unit,
    onRaritySelected: (Rarity?) -> Unit,
    onResetFilters: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val filters = uiState.filters
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.small),
    ) {
        ChipRow {
            BROWSE_COLORS.forEach { color ->
                FilterChip(
                    selected = color in filters.colors,
                    onClick = { onColorToggle(color) },
                    label = { Text(colorLabel(color)) },
                )
            }
        }

        ChipRow {
            DropdownChip(
                label = filters.cardType?.let { "Type: $it" } ?: "Type",
                options = BROWSE_CARD_TYPES,
                selected = filters.cardType,
                optionLabel = { it },
                onSelected = onCardTypeSelected,
            )
            DropdownChip(
                label = filters.manaValue?.let { "MV: ${manaValueLabel(it)}" } ?: "Mana value",
                options = (0..MANA_VALUE_MAX_BUCKET).toList(),
                selected = filters.manaValue,
                optionLabel = { manaValueLabel(it) },
                onSelected = onManaValueSelected,
            )
            DropdownChip(
                label = filters.rarity?.let { "Rarity: ${rarityLabel(it)}" } ?: "Rarity",
                options = BROWSE_RARITIES,
                selected = filters.rarity,
                optionLabel = { rarityLabel(it) },
                onSelected = onRaritySelected,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.medium),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = uiState.countSummary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (uiState.hasActiveFilters) {
                MageTextButton(text = "Reset", onClick = onResetFilters)
            }
        }
    }
}

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = Spacing.medium),
        horizontalArrangement = Arrangement.spacedBy(Spacing.small),
    ) {
        content()
    }
}

/**
 * A [FilterChip] that opens a [DropdownMenu] of [options]. Selecting the already-selected option
 * clears the axis (a "None" item is offered too), so every dropdown filter is toggleable to off.
 */
@Composable
private fun <T> DropdownChip(
    label: String,
    options: List<T>,
    selected: T?,
    optionLabel: (T) -> String,
    onSelected: (T?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = selected != null,
            onClick = { expanded = true },
            label = { Text(label) },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Any") },
                onClick = {
                    onSelected(null)
                    expanded = false
                },
            )
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = {
                        onSelected(if (option == selected) null else option)
                        expanded = false
                    },
                )
            }
        }
    }
}

private fun colorLabel(color: CardColor): String =
    when (color) {
        CardColor.WHITE -> "White"
        CardColor.BLUE -> "Blue"
        CardColor.BLACK -> "Black"
        CardColor.RED -> "Red"
        CardColor.GREEN -> "Green"
    }

private fun manaValueLabel(bucket: Int): String = if (bucket >= MANA_VALUE_MAX_BUCKET) "$MANA_VALUE_MAX_BUCKET+" else bucket.toString()

private fun rarityLabel(rarity: Rarity): String = rarity.name.lowercase().replaceFirstChar { it.uppercase() }

@Preview(name = "CardFilters - light", showBackground = true)
@Preview(
    name = "CardFilters - dark",
    showBackground = true,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun CardFiltersPreview() {
    MageTheme {
        CardFilters(
            uiState =
                CardSearchUiState(
                    query = "bolt",
                    filters = CardBrowseFilters(colors = setOf(CardColor.RED), cardType = "Instant"),
                    phase = CardSearchPhase.Results,
                ),
            onColorToggle = {},
            onCardTypeSelected = {},
            onManaValueSelected = {},
            onRaritySelected = {},
            onResetFilters = {},
            modifier = Modifier.padding(vertical = Spacing.small),
        )
    }
}
