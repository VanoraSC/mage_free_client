package magefree.feature.decks.library

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
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
import magefree.decks.model.DeckFormat
import magefree.decks.model.DeckId
import magefree.decks.model.DeckSummary
import magefree.designsystem.component.EmptyState
import magefree.designsystem.component.LoadingState
import magefree.designsystem.component.MageTopAppBar
import magefree.designsystem.theme.MageShapes
import magefree.designsystem.theme.MageTheme
import magefree.designsystem.theme.Spacing

/** Screen title; shared with the shell so the Decks tab reads "Decks". */
const val DECKS_LIBRARY_TITLE: String = "Decks"

/** Accessibility label on the card-catalog browse action. */
const val LIBRARY_BROWSE_CARDS_LABEL: String = "Browse cards"

/** Accessibility label on the import action. */
const val LIBRARY_IMPORT_LABEL: String = "Import deck"

/**
 * Stateless deck-library screen: the saved decks as a tappable list (favorite star, name, format +
 * counts, and an overflow with duplicate/rename/delete), plus create / import / browse-cards actions.
 * The empty state invites creating the first deck. Every deck operation is hoisted and served offline
 * by [LibraryViewModel]; the composable fetches nothing.
 */
@Composable
fun LibraryScreen(
    uiState: LibraryUiState,
    onOpenDeck: (DeckId) -> Unit,
    onCreateDeck: (String, DeckFormat?) -> Unit,
    onRenameDeck: (DeckId, String) -> Unit,
    onDuplicateDeck: (DeckId) -> Unit,
    onDeleteDeck: (DeckId) -> Unit,
    onToggleFavorite: (DeckId, Boolean) -> Unit,
    onImportDeck: (String) -> Unit,
    onBrowseCards: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showCreate by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<DeckSummary?>(null) }

    Scaffold(
        modifier = modifier,
        topBar = {
            MageTopAppBar(
                title = DECKS_LIBRARY_TITLE,
                actions = {
                    IconButton(onClick = { showImport = true }) {
                        Icon(imageVector = Icons.Filled.Add, contentDescription = LIBRARY_IMPORT_LABEL)
                    }
                    IconButton(onClick = onBrowseCards) {
                        Icon(imageVector = Icons.Filled.Search, contentDescription = LIBRARY_BROWSE_CARDS_LABEL)
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                text = { Text("New deck") },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                onClick = { showCreate = true },
            )
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            when (uiState.phase) {
                LibraryPhase.Loading -> LoadingState(message = "Loading decks")
                LibraryPhase.Empty ->
                    EmptyState(
                        title = "No decks yet",
                        message = "Create your first deck, or import one from a file.",
                        icon = Icons.Filled.Star,
                        actionLabel = "New deck",
                        onAction = { showCreate = true },
                    )
                LibraryPhase.Content ->
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(Spacing.medium),
                        verticalArrangement = Arrangement.spacedBy(Spacing.small),
                    ) {
                        items(items = uiState.decks, key = { it.id.value }) { deck ->
                            DeckRow(
                                deck = deck,
                                onOpen = { onOpenDeck(deck.id) },
                                onToggleFavorite = { onToggleFavorite(deck.id, !deck.favorite) },
                                onDuplicate = { onDuplicateDeck(deck.id) },
                                onRename = { renameTarget = deck },
                                onDelete = { onDeleteDeck(deck.id) },
                            )
                        }
                    }
            }
        }
    }

    if (showCreate) {
        DeckNameDialog(
            title = "New deck",
            confirmLabel = "Create",
            initialName = "",
            showFormat = true,
            onConfirm = { name, format ->
                onCreateDeck(name, format)
                showCreate = false
            },
            onDismiss = { showCreate = false },
        )
    }

    renameTarget?.let { target ->
        DeckNameDialog(
            title = "Rename deck",
            confirmLabel = "Rename",
            initialName = target.name,
            showFormat = false,
            onConfirm = { name, _ ->
                onRenameDeck(target.id, name)
                renameTarget = null
            },
            onDismiss = { renameTarget = null },
        )
    }

    if (showImport) {
        ImportDeckDialog(
            onConfirm = { text ->
                onImportDeck(text)
                showImport = false
            },
            onDismiss = { showImport = false },
        )
    }
}

@Composable
private fun DeckRow(
    deck: DeckSummary,
    onOpen: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDuplicate: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        onClick = onOpen,
        shape = MageShapes.medium,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(Spacing.small),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            IconButton(onClick = onToggleFavorite) {
                Icon(
                    imageVector = Icons.Filled.Star,
                    contentDescription = if (deck.favorite) "Unfavorite" else "Favorite",
                    tint =
                        if (deck.favorite) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = deck.name.ifBlank { "Untitled deck" },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = deckSubtitle(deck),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DeckOverflowMenu(onDuplicate = onDuplicate, onRename = onRename, onDelete = onDelete)
        }
    }
}

@Composable
private fun DeckOverflowMenu(
    onDuplicate: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(imageVector = Icons.Filled.MoreVert, contentDescription = "Deck actions")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("Rename") }, onClick = {
                expanded = false
                onRename()
            })
            DropdownMenuItem(text = { Text("Duplicate") }, onClick = {
                expanded = false
                onDuplicate()
            })
            DropdownMenuItem(text = { Text("Delete") }, onClick = {
                expanded = false
                onDelete()
            })
        }
    }
}

private fun deckSubtitle(deck: DeckSummary): String {
    val format = deck.format?.displayName ?: "No format"
    return "$format · ${deck.mainCount} main · ${deck.sideboardCount} sideboard"
}

// ---- Previews ---------------------------------------------------------------------------------

private val sampleDecks =
    listOf(
        DeckSummary(
            id = DeckId("1"),
            name = "Mono-Red Aggro",
            author = "Pete",
            format = DeckFormat.STANDARD,
            favorite = true,
            mainCount = 60,
            sideboardCount = 15,
            createdAt = 0,
            updatedAt = 0,
        ),
        DeckSummary(
            id = DeckId("2"),
            name = "Dimir Control",
            author = null,
            format = DeckFormat.PIONEER,
            favorite = false,
            mainCount = 60,
            sideboardCount = 15,
            createdAt = 0,
            updatedAt = 0,
        ),
    )

@Composable
private fun previewLibrary(uiState: LibraryUiState) {
    MageTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            LibraryScreen(
                uiState = uiState,
                onOpenDeck = {},
                onCreateDeck = { _, _ -> },
                onRenameDeck = { _, _ -> },
                onDuplicateDeck = {},
                onDeleteDeck = {},
                onToggleFavorite = { _, _ -> },
                onImportDeck = {},
                onBrowseCards = {},
            )
        }
    }
}

@Preview(name = "Library - content (light)", showBackground = true, heightDp = 640)
@Preview(name = "Library - content (dark)", showBackground = true, heightDp = 640, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun LibraryContentPreview() = previewLibrary(LibraryUiState(decks = sampleDecks, phase = LibraryPhase.Content))

@Preview(name = "Library - empty", showBackground = true, heightDp = 640)
@Composable
private fun LibraryEmptyPreview() = previewLibrary(LibraryUiState(phase = LibraryPhase.Empty))
