package magefree.feature.lobby

import android.content.res.Configuration
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import magefree.designsystem.component.MageTextButton
import magefree.designsystem.theme.MageTheme
import magefree.designsystem.theme.Spacing

/**
 * The lobby's client-side filter + sort controls: a scrollable row of format [FilterChip]s (an "All"
 * chip plus one per advertised game type), two toggle chips (hide passworded / hide full), a sort
 * selector, and a "showing N of M" summary with a reset affordance when filters are active.
 *
 * Stateless: the active [LobbyFilter] and every change flow through hoisted callbacks.
 */
@Composable
fun LobbyFilters(
    uiState: LobbyUiState,
    onGameTypeSelected: (String?) -> Unit,
    onHidePasswordedChange: (Boolean) -> Unit,
    onHideFullChange: (Boolean) -> Unit,
    onSortSelected: (LobbySort) -> Unit,
    onResetFilters: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val filter = uiState.filter
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.small),
    ) {
        if (uiState.gameTypeNames.isNotEmpty()) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = Spacing.medium),
                horizontalArrangement = Arrangement.spacedBy(Spacing.small),
            ) {
                FilterChip(
                    selected = filter.gameType == null,
                    onClick = { onGameTypeSelected(null) },
                    label = { Text("All formats") },
                )
                uiState.gameTypeNames.forEach { type ->
                    FilterChip(
                        selected = filter.gameType == type,
                        onClick = {
                            onGameTypeSelected(if (filter.gameType == type) null else type)
                        },
                        label = { Text(type) },
                    )
                }
            }
        }

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.medium),
            horizontalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            FilterChip(
                selected = filter.hidePassworded,
                onClick = { onHidePasswordedChange(!filter.hidePassworded) },
                label = { Text("Hide passworded") },
            )
            FilterChip(
                selected = filter.hideFull,
                onClick = { onHideFullChange(!filter.hideFull) },
                label = { Text("Hide full") },
            )
            SortChip(current = filter.sort, onSortSelected = onSortSelected)
        }

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.medium),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = uiState.countSummary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (filter.hasActiveFilters) {
                MageTextButton(text = "Reset", onClick = onResetFilters)
            }
        }
    }
}

/** A [FilterChip] that opens a [DropdownMenu] of the [LobbySort] options. */
@Composable
private fun SortChip(
    current: LobbySort,
    onSortSelected: (LobbySort) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = false,
            onClick = { expanded = true },
            label = { Text("Sort: ${current.label}") },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            LobbySort.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = {
                        onSortSelected(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Preview(name = "LobbyFilters - light", showBackground = true)
@Preview(
    name = "LobbyFilters - dark",
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun LobbyFiltersPreview() {
    MageTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            LobbyFilters(
                uiState =
                    LobbyUiState(
                        phase = LobbyPhase.Content,
                        tables = sampleTables,
                        totalTables = sampleTables.size,
                        roomUserCount = 4,
                        gameTypeNames = listOf("Two Player Duel", "Booster Draft", "Commander Free For All"),
                        filter = LobbyFilter(hidePassworded = true),
                    ),
                onGameTypeSelected = {},
                onHidePasswordedChange = {},
                onHideFullChange = {},
                onSortSelected = {},
                onResetFilters = {},
                modifier = Modifier.padding(vertical = Spacing.small),
            )
        }
    }
}
