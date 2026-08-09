package magefree.feature.cards

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import magefree.cards.art.CardArtRequest
import magefree.cards.model.CardColor
import magefree.cards.model.CardId
import magefree.cards.model.Rarity
import magefree.designsystem.card.CardDisplay
import magefree.designsystem.card.CardTile
import magefree.designsystem.component.EmptyState
import magefree.designsystem.component.ErrorState
import magefree.designsystem.component.LoadingState
import magefree.designsystem.component.MageTopAppBar
import magefree.designsystem.theme.MageTheme
import magefree.designsystem.theme.Spacing

/** Screen title; shared with tests so the two agree. */
const val CARDS_TITLE: String = "Cards"

/** Accessibility label on the back / up control. */
const val CARDS_BACK_CONTENT_DESCRIPTION: String = "Back"

/** Accessibility label on the art cache/download settings action. */
const val CARDS_ART_SETTINGS_CONTENT_DESCRIPTION: String = "Card art settings"

/** Placeholder text for the search field. */
const val CARDS_SEARCH_HINT: String = "Search cards"

/** The minimum tile width the results grid packs to (an adaptive, responsive grid). */
private val TileMinWidth = 150.dp

/**
 * Stateless card search/browse screen. A debounced [OutlinedTextField] over the catalog, the
 * [CardFilters] controls, and an adaptive grid of design-system [CardTile]s (art via the injected
 * [CardArtRenderer]). Renders the idle prompt / loading / "no matches" empty surfaces via the design
 * system's [EmptyState] / [LoadingState], and a catalog read failure via [ErrorState] with retry.
 * Every event is hoisted; the composable fetches nothing.
 */
@Composable
fun CardSearchScreen(
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
    onCardSelected: (CardId) -> Unit,
    onRetry: () -> Unit,
    onOpenArtSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            MageTopAppBar(
                title = CARDS_TITLE,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = CARDS_BACK_CONTENT_DESCRIPTION,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onOpenArtSettings) {
                        Icon(
                            imageVector = Icons.Outlined.Settings,
                            contentDescription = CARDS_ART_SETTINGS_CONTENT_DESCRIPTION,
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            verticalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            SearchField(
                query = uiState.query,
                onQueryChange = onQueryChange,
                onClearQuery = onClearQuery,
                modifier = Modifier.padding(horizontal = Spacing.medium, vertical = Spacing.small),
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
                        message = "Type a card name, or pick a filter to browse the catalog.",
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
                CardSearchPhase.Error ->
                    ErrorState(
                        message = uiState.errorMessage ?: "Couldn't read the card catalog",
                        onRetry = onRetry,
                    )
                CardSearchPhase.Results ->
                    CardResultsGrid(
                        results = uiState.results,
                        artRenderer = artRenderer,
                        onCardSelected = onCardSelected,
                    )
            }
        }
    }
}

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onClearQuery: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        placeholder = { Text(CARDS_SEARCH_HINT) },
        leadingIcon = { Icon(imageVector = Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = onClearQuery) {
                    Icon(imageVector = Icons.Filled.Clear, contentDescription = "Clear search")
                }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
    )
}

@Composable
private fun CardResultsGrid(
    results: List<CardRow>,
    artRenderer: CardArtRenderer,
    onCardSelected: (CardId) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = TileMinWidth),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(Spacing.medium),
        horizontalArrangement = Arrangement.spacedBy(Spacing.medium),
        verticalArrangement = Arrangement.spacedBy(Spacing.medium),
    ) {
        items(items = results, key = { it.id.value }) { row ->
            CardTile(
                card = row.display,
                onTap = { onCardSelected(row.id) },
                art = artRenderer.slotFor(row.artRequest, row.display),
            )
        }
    }
}

// ---- Previews ---------------------------------------------------------------------------------

private val sampleResults =
    listOf(
        CardRow(
            id = CardId(1),
            display = CardDisplay(name = "Lightning Bolt", manaCost = "{R}", typeLine = "Instant"),
            artRequest = CardArtRequest("2ed", "161"),
            setLabel = "2ED",
            isMultiFace = false,
        ),
        CardRow(
            id = CardId(2),
            display = CardDisplay(name = "Grizzly Bears", manaCost = "{1}{G}", typeLine = "Creature — Bear"),
            artRequest = null,
            setLabel = "M11",
            isMultiFace = false,
        ),
        CardRow(
            id = CardId(3),
            display = CardDisplay(name = "Delver of Secrets", manaCost = "{U}", typeLine = "Creature — Human Wizard"),
            artRequest = null,
            setLabel = "ISD",
            isMultiFace = true,
        ),
    )

private fun previewState(
    phase: CardSearchPhase,
    query: String = "",
    results: List<CardRow> = sampleResults,
    filters: CardBrowseFilters = CardBrowseFilters(),
): CardSearchUiState =
    CardSearchUiState(
        query = query,
        filters = filters,
        results =
            if (phase ==
                CardSearchPhase.Results
            ) {
                results
            } else {
                emptyList()
            },
        phase = phase,
        errorMessage = if (phase == CardSearchPhase.Error) "Couldn't read the card catalog" else null,
    )

@Composable
private fun previewScreen(uiState: CardSearchUiState) {
    MageTheme {
        CardSearchScreen(
            uiState = uiState,
            artRenderer = PlaceholderCardArtRenderer,
            onBack = {},
            onQueryChange = {},
            onClearQuery = {},
            onColorToggle = {},
            onCardTypeSelected = {},
            onManaValueSelected = {},
            onRaritySelected = {},
            onResetFilters = {},
            onCardSelected = {},
            onRetry = {},
            onOpenArtSettings = {},
        )
    }
}

@Preview(name = "Card search - results (light)", showBackground = true, heightDp = 780)
@Preview(name = "Card search - results (dark)", showBackground = true, heightDp = 780, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun CardSearchResultsPreview() = previewScreen(previewState(CardSearchPhase.Results, query = "bolt"))

@Preview(name = "Card search - idle", showBackground = true, heightDp = 780)
@Composable
private fun CardSearchIdlePreview() = previewScreen(previewState(CardSearchPhase.Idle))

@Preview(name = "Card search - empty", showBackground = true, heightDp = 780)
@Composable
private fun CardSearchEmptyPreview() =
    previewScreen(previewState(CardSearchPhase.Empty, query = "zzz", filters = CardBrowseFilters(colors = setOf(CardColor.RED))))

@Preview(name = "Card search - loading", showBackground = true, heightDp = 780)
@Composable
private fun CardSearchLoadingPreview() = previewScreen(previewState(CardSearchPhase.Loading, query = "bo"))

@Preview(name = "Card search - catalog error", showBackground = true, heightDp = 780)
@Composable
private fun CardSearchErrorPreview() = previewScreen(previewState(CardSearchPhase.Error, query = "bolt"))
