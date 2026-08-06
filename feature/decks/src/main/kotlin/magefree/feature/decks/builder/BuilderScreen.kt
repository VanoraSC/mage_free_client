package magefree.feature.decks.builder

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import magefree.cards.model.CardId
import magefree.decks.model.DeckFormat
import magefree.decks.model.DeckId
import magefree.decks.model.DeckZone
import magefree.designsystem.card.CardDisplay
import magefree.designsystem.component.EmptyState
import magefree.designsystem.component.LoadingState
import magefree.designsystem.component.MageSectionHeader
import magefree.designsystem.component.MageTextButton
import magefree.designsystem.component.MageTopAppBar
import magefree.designsystem.theme.MageShapes
import magefree.designsystem.theme.MageTheme
import magefree.designsystem.theme.Spacing

/**
 * Stateless deck-builder screen: the deck grouped by card type, a live mana curve, a format picker with
 * live legality, and a sideboard section — each line with fast quantity controls. The add-cards surface,
 * share, and deck-scoped art download are hoisted (opened by the route). Every deck operation is offline;
 * submitting/playing the deck is EPIC-07 and intentionally absent.
 */
@Composable
fun BuilderScreen(
    uiState: BuilderUiState,
    onBack: () -> Unit,
    onAddCards: () -> Unit,
    onInspectCard: (CardId) -> Unit,
    onChangeQuantity: (String, DeckZone, Int) -> Unit,
    onRemove: (String, DeckZone) -> Unit,
    onFormatSelected: (DeckFormat?) -> Unit,
    onShare: () -> Unit,
    onOpenArtSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            MageTopAppBar(
                title = uiState.name.ifBlank { "Deck" },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to library")
                    }
                },
                actions = { BuilderOverflow(onShare = onShare, onOpenArtSettings = onOpenArtSettings) },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                text = { Text("Add cards") },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                onClick = onAddCards,
            )
        },
    ) { innerPadding ->
        when (uiState.phase) {
            BuilderPhase.Loading -> LoadingState(message = "Loading deck", modifier = Modifier.padding(innerPadding))
            BuilderPhase.NotFound ->
                EmptyState(
                    title = "Deck not found",
                    message = "This deck is no longer in your library.",
                    modifier = Modifier.padding(innerPadding),
                )
            BuilderPhase.Ready ->
                BuilderContent(
                    uiState = uiState,
                    onInspectCard = onInspectCard,
                    onChangeQuantity = onChangeQuantity,
                    onRemove = onRemove,
                    onFormatSelected = onFormatSelected,
                    modifier = Modifier.padding(innerPadding),
                )
        }
    }
}

@Composable
private fun BuilderContent(
    uiState: BuilderUiState,
    onInspectCard: (CardId) -> Unit,
    onChangeQuantity: (String, DeckZone, Int) -> Unit,
    onRemove: (String, DeckZone) -> Unit,
    onFormatSelected: (DeckFormat?) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = Spacing.extraLarge),
        verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
    ) {
        item {
            Text(
                text = "${uiState.mainCount} main · ${uiState.sideboardCount} sideboard",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.medium, vertical = Spacing.small),
            )
        }
        item { MageSectionHeader(text = "Mana curve") }
        item { ManaCurveView(curve = uiState.manaCurve) }
        item { MageSectionHeader(text = "Legality") }
        item {
            LegalityPanel(
                format = uiState.format,
                result = uiState.legality,
                onFormatSelected = onFormatSelected,
            )
        }

        if (uiState.mainCount == 0 && uiState.sideboardCount == 0) {
            item {
                Text(
                    text = "No cards yet. Tap “Add cards” to search the catalog and build your deck.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(Spacing.medium),
                )
            }
        }

        uiState.groups.forEach { group ->
            item(key = "group-${group.type}") {
                MageSectionHeader(text = "${group.type} (${group.count})")
            }
            items(items = group.rows, key = { "main-${it.key}" }) { row ->
                DeckLineRow(
                    row = row,
                    onInspect = { row.cardId?.let(onInspectCard) },
                    onIncrement = { onChangeQuantity(row.key, DeckZone.MAIN, 1) },
                    onDecrement = { onChangeQuantity(row.key, DeckZone.MAIN, -1) },
                    onRemove = { onRemove(row.key, DeckZone.MAIN) },
                )
            }
        }

        if (uiState.sideboard.isNotEmpty()) {
            item(key = "sideboard-header") {
                MageSectionHeader(text = "Sideboard (${uiState.sideboardCount})")
            }
            items(items = uiState.sideboard, key = { "side-${it.key}" }) { row ->
                DeckLineRow(
                    row = row,
                    onInspect = { row.cardId?.let(onInspectCard) },
                    onIncrement = { onChangeQuantity(row.key, DeckZone.SIDEBOARD, 1) },
                    onDecrement = { onChangeQuantity(row.key, DeckZone.SIDEBOARD, -1) },
                    onRemove = { onRemove(row.key, DeckZone.SIDEBOARD) },
                )
            }
        }
    }
}

@Composable
private fun DeckLineRow(
    row: DeckCardRow,
    onInspect: () -> Unit,
    onIncrement: () -> Unit,
    onDecrement: () -> Unit,
    onRemove: () -> Unit,
) {
    Surface(
        onClick = onInspect,
        shape = MageShapes.small,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.medium, vertical = Spacing.extraSmall),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(Spacing.small),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            Text(
                text = "${row.quantity}×",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.cardName,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                row.display.typeLine?.let { type ->
                    Text(
                        text = type,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            MageTextButton(text = "−", onClick = onDecrement)
            MageTextButton(text = "+", onClick = onIncrement)
            MageTextButton(text = "Remove", onClick = onRemove)
        }
    }
}

@Composable
private fun BuilderOverflow(
    onShare: () -> Unit,
    onOpenArtSettings: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    IconButton(onClick = { expanded = true }) {
        Icon(imageVector = Icons.Filled.MoreVert, contentDescription = "Deck options")
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        DropdownMenuItem(text = { Text("Share / export") }, onClick = {
            expanded = false
            onShare()
        })
        DropdownMenuItem(text = { Text("Download deck art") }, onClick = {
            expanded = false
            onOpenArtSettings()
        })
    }
}

// ---- Previews ---------------------------------------------------------------------------------

private fun sampleRow(
    name: String,
    type: String,
    mv: Int,
    qty: Int,
): DeckCardRow =
    DeckCardRow(
        key = "$name||",
        cardId = CardId(name.hashCode().toLong()),
        cardName = name,
        setCode = "TST",
        collectorNumber = "1",
        quantity = qty,
        zone = DeckZone.MAIN,
        display = CardDisplay(name = name, manaCost = null, typeLine = type, oracleText = null),
        artRequest = null,
        manaValue = mv,
        primaryType = type.substringBefore(' '),
        isDoubleFaced = false,
    )

@Preview(name = "Builder - ready (light)", showBackground = true, heightDp = 900)
@Preview(name = "Builder - ready (dark)", showBackground = true, heightDp = 900, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun BuilderReadyPreview() {
    MageTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            BuilderScreen(
                uiState =
                    BuilderUiState(
                        phase = BuilderPhase.Ready,
                        deckId = DeckId("1"),
                        name = "Mono-Red Aggro",
                        format = DeckFormat.STANDARD,
                        groups =
                            listOf(
                                DeckGroup("Creature", listOf(sampleRow("Goblin Guide", "Creature", 1, 4))),
                                DeckGroup("Instant", listOf(sampleRow("Lightning Bolt", "Instant", 1, 4))),
                            ),
                        manaCurve =
                            manaCurve(
                                listOf(
                                    sampleRow("Goblin Guide", "Creature", 1, 4),
                                    sampleRow("Lightning Bolt", "Instant", 1, 4),
                                ),
                            ),
                    ),
                onBack = {},
                onAddCards = {},
                onInspectCard = {},
                onChangeQuantity = { _, _, _ -> },
                onRemove = { _, _ -> },
                onFormatSelected = {},
                onShare = {},
                onOpenArtSettings = {},
            )
        }
    }
}
