package magefree.feature.decks.builder

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import magefree.cards.model.CardColor
import magefree.cards.model.CardId
import magefree.cards.model.Rarity
import magefree.decks.model.DeckZone
import magefree.designsystem.card.CardTile
import magefree.designsystem.component.EmptyState
import magefree.designsystem.component.LoadingState
import magefree.designsystem.component.MageSecondaryButton
import magefree.designsystem.component.MageTopAppBar
import magefree.designsystem.theme.Spacing
import magefree.feature.cards.CardArtRenderer
import magefree.feature.cards.CardFilters
import magefree.feature.cards.CardRow
import magefree.feature.cards.CardSearchPhase
import magefree.feature.cards.CardSearchUiState
import magefree.feature.cards.slotFor

/**
 * The builder's card search/add surface. It reuses story 0032's offline [CardSearchUiState] pipeline,
 * [CardFilters] controls, per-row [CardArtRenderer] art, and the design-system [CardTile] rendering —
 * layering deck-add affordances (add to main / sideboard) and tap-to-inspect on top. No card rendering
 * is hand-rolled here; only the add controls are new. Stateless; every event hoisted.
 */
@Composable
fun AddCardsScreen(
    uiState: CardSearchUiState,
    artRenderer: CardArtRenderer,
    onBack: () -> Unit,
    onQueryChange: (String) -> Unit,
    onClearQuery: () -> Unit,
    onColorToggle: (CardColor) -> Unit,
    onCardTypeSelected: (String?) -> Unit,
    onManaValueSelected: (Int?) -> Unit,
    onRaritySelected: (Rarity?) -> Unit,
    onResetFilters: () -> Unit,
    onAdd: (CardId, DeckZone) -> Unit,
    onInspect: (CardId) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            MageTopAppBar(
                title = "Add cards",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to deck")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            verticalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            OutlinedTextField(
                value = uiState.query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.medium, vertical = Spacing.small),
                singleLine = true,
                placeholder = { Text("Search cards to add") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (uiState.query.isNotEmpty()) {
                        IconButton(onClick = onClearQuery) {
                            Icon(Icons.Filled.Clear, contentDescription = "Clear search")
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            )
            CardFilters(
                uiState = uiState,
                onColorToggle = onColorToggle,
                onCardTypeSelected = onCardTypeSelected,
                onManaValueSelected = onManaValueSelected,
                onRaritySelected = onRaritySelected,
                onResetFilters = onResetFilters,
            )
            when (uiState.phase) {
                CardSearchPhase.Idle ->
                    EmptyState(
                        title = "Search for cards",
                        message = "Type a name or pick a filter, then add cards to your deck.",
                        icon = Icons.Filled.Search,
                    )
                CardSearchPhase.Loading -> LoadingState(message = "Searching")
                CardSearchPhase.Empty ->
                    EmptyState(
                        title = "No matches",
                        message = "No cards match your search or filters.",
                        actionLabel = if (uiState.hasActiveFilters) "Reset filters" else null,
                        onAction = if (uiState.hasActiveFilters) onResetFilters else null,
                    )
                CardSearchPhase.Results ->
                    AddResults(
                        results = uiState.results,
                        artRenderer = artRenderer,
                        onAdd = onAdd,
                        onInspect = onInspect,
                    )
            }
        }
    }
}

@Composable
private fun AddResults(
    results: List<CardRow>,
    artRenderer: CardArtRenderer,
    onAdd: (CardId, DeckZone) -> Unit,
    onInspect: (CardId) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(Spacing.medium),
        verticalArrangement = Arrangement.spacedBy(Spacing.medium),
    ) {
        items(items = results, key = { it.id.value }) { row ->
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall)) {
                CardTile(
                    card = row.display,
                    onTap = { onInspect(row.id) },
                    art = artRenderer.slotFor(row.artRequest, row.display),
                    modifier = Modifier.width(160.dp),
                )
                Column {
                    MageSecondaryButton(
                        text = "Add to main",
                        onClick = { onAdd(row.id, DeckZone.MAIN) },
                        icon = Icons.Filled.Add,
                    )
                    MageSecondaryButton(
                        text = "Add to sideboard",
                        onClick = { onAdd(row.id, DeckZone.SIDEBOARD) },
                        icon = Icons.Filled.Add,
                    )
                }
            }
        }
    }
}
