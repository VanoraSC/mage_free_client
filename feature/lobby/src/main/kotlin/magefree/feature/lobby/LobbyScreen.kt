package magefree.feature.lobby

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import magefree.designsystem.component.EmptyState
import magefree.designsystem.component.ErrorState
import magefree.designsystem.component.LoadingState
import magefree.designsystem.component.MagePrimaryButton
import magefree.designsystem.component.MageSectionHeader
import magefree.designsystem.component.MageTextButton
import magefree.designsystem.component.MageTopAppBar
import magefree.designsystem.theme.MageTheme
import magefree.designsystem.theme.Spacing
import magefree.model.LobbyTable

/** Screen title; shared with tests so the two agree. */
const val LOBBY_TITLE: String = "Lobby"

/** Accessibility label on the refresh action (also the error-with-data retry). */
const val LOBBY_REFRESH_CONTENT_DESCRIPTION: String = "Refresh lobby"

/** Accessibility label on the host-a-table action (story 0038). */
const val LOBBY_HOST_CONTENT_DESCRIPTION: String = "Host a table"

/** Label on the (now enabled) join action in a table's detail sheet (story 0038). */
const val LOBBY_JOIN_LABEL: String = "Join"

/** Label on the spectate action in a table's detail sheet (story 0038). */
const val LOBBY_WATCH_LABEL: String = "Watch"

/** Accessibility label on the back / up control. */
const val LOBBY_BACK_CONTENT_DESCRIPTION: String = "Back"

/**
 * Stateful lobby route: binds [LobbyViewModel] to the stateless [LobbyScreen], kicks off the first
 * load, and holds the ephemeral tapped-table detail selection locally (pure UI state, not VM state).
 */
@Composable
fun LobbyRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onHost: () -> Unit = {},
    onJoin: (LobbyTable) -> Unit = {},
    onWatch: (LobbyTable) -> Unit = {},
    viewModel: LobbyViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var detailTable by remember { mutableStateOf<LobbyTable?>(null) }

    // Manual initial load on entering the lobby (no auto-refresh loop). No-ops to idle if disconnected.
    LaunchedEffect(Unit) { viewModel.refresh() }

    LobbyScreen(
        uiState = uiState,
        onBack = onBack,
        onRefresh = viewModel::refresh,
        onGameTypeSelected = viewModel::setGameTypeFilter,
        onHidePasswordedChange = viewModel::setHidePassworded,
        onHideFullChange = viewModel::setHideFull,
        onSortSelected = viewModel::setSort,
        onResetFilters = viewModel::resetFilters,
        detailTable = detailTable,
        onTableSelected = { detailTable = it },
        onDetailDismiss = { detailTable = null },
        onHost = onHost,
        onJoin = onJoin,
        onWatch = onWatch,
        modifier = modifier,
    )
}

/**
 * Stateless lobby browser. Renders one of four distinct surfaces from [LobbyUiState.phase]:
 * disconnected ("connect to browse"), loading, error (+retry), or the content list with filter/sort
 * controls and pull-to-refresh. Refresh is non-destructive: a re-fetch keeps the current list under
 * the pull indicator, and a refresh failure over existing data shows an inline retry banner rather
 * than clearing the list.
 *
 * Every event is hoisted; the composable fetches nothing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LobbyScreen(
    uiState: LobbyUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onGameTypeSelected: (String?) -> Unit,
    onHidePasswordedChange: (Boolean) -> Unit,
    onHideFullChange: (Boolean) -> Unit,
    onSortSelected: (LobbySort) -> Unit,
    onResetFilters: () -> Unit,
    detailTable: LobbyTable?,
    onTableSelected: (LobbyTable) -> Unit,
    onDetailDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onHost: () -> Unit = {},
    onJoin: (LobbyTable) -> Unit = {},
    onWatch: (LobbyTable) -> Unit = {},
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            MageTopAppBar(
                title = LOBBY_TITLE,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = LOBBY_BACK_CONTENT_DESCRIPTION,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onHost) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = LOBBY_HOST_CONTENT_DESCRIPTION,
                        )
                    }
                    IconButton(onClick = onRefresh) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = LOBBY_REFRESH_CONTENT_DESCRIPTION,
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            when (uiState.phase) {
                LobbyPhase.Disconnected ->
                    EmptyState(
                        title = "Connect to browse",
                        message = "Sign in to a server to see open tables and players.",
                        icon = Icons.AutoMirrored.Filled.List,
                    )
                LobbyPhase.Loading -> LoadingState(message = "Loading lobby")
                LobbyPhase.Error ->
                    ErrorState(
                        message = uiState.errorMessage ?: "Couldn't load the lobby.",
                        onRetry = onRefresh,
                    )
                LobbyPhase.Content ->
                    LobbyContent(
                        uiState = uiState,
                        onRefresh = onRefresh,
                        onGameTypeSelected = onGameTypeSelected,
                        onHidePasswordedChange = onHidePasswordedChange,
                        onHideFullChange = onHideFullChange,
                        onSortSelected = onSortSelected,
                        onResetFilters = onResetFilters,
                        onTableSelected = onTableSelected,
                    )
            }
        }
    }

    detailTable?.let { table ->
        TableDetailDialog(
            table = table,
            onDismiss = onDetailDismiss,
            onJoin = onJoin,
            onWatch = onWatch,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LobbyContent(
    uiState: LobbyUiState,
    onRefresh: () -> Unit,
    onGameTypeSelected: (String?) -> Unit,
    onHidePasswordedChange: (Boolean) -> Unit,
    onHideFullChange: (Boolean) -> Unit,
    onSortSelected: (LobbySort) -> Unit,
    onResetFilters: () -> Unit,
    onTableSelected: (LobbyTable) -> Unit,
) {
    PullToRefreshBox(
        isRefreshing = uiState.isRefreshing,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item(key = "players") {
                MageSectionHeader(text = "${uiState.roomUserCount} players in lobby")
            }
            item(key = "filters") {
                LobbyFilters(
                    uiState = uiState,
                    onGameTypeSelected = onGameTypeSelected,
                    onHidePasswordedChange = onHidePasswordedChange,
                    onHideFullChange = onHideFullChange,
                    onSortSelected = onSortSelected,
                    onResetFilters = onResetFilters,
                    modifier = Modifier.padding(vertical = Spacing.small),
                )
            }
            if (uiState.errorMessage != null) {
                item(key = "refresh-error") {
                    RefreshErrorBanner(message = uiState.errorMessage, onRetry = onRefresh)
                }
            }
            when {
                uiState.isEmpty ->
                    item(key = "empty") {
                        EmptyState(
                            title = "No open tables",
                            message = "No one has a table open right now. Pull down to refresh.",
                            icon = Icons.AutoMirrored.Filled.List,
                        )
                    }
                uiState.isFilteredEmpty ->
                    item(key = "filtered-empty") {
                        EmptyState(
                            title = "No tables match",
                            message = "No open tables match your filters.",
                            actionLabel = "Reset filters",
                            onAction = onResetFilters,
                        )
                    }
                else ->
                    items(items = uiState.tables, key = { it.id }) { table ->
                        TableRow(table = table, onClick = { onTableSelected(table) })
                    }
            }
        }
    }
}

/** Inline, non-destructive refresh-failure banner shown above the retained list. */
@Composable
private fun RefreshErrorBanner(
    message: String,
    onRetry: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.medium, vertical = Spacing.small),
    ) {
        Column(
            modifier = Modifier.padding(Spacing.medium),
            verticalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            Text(text = "Refresh failed: $message", style = MaterialTheme.typography.bodyMedium)
            MageTextButton(text = "Retry", onClick = onRetry)
        }
    }
}

/**
 * A minimal table detail: the table's settings at a glance and the (story 0038) join / spectate actions.
 * Join opens the deck-pick + submit flow; Watch opens a read-only room. A full table offers only Watch.
 */
@Composable
private fun TableDetailDialog(
    table: LobbyTable,
    onDismiss: () -> Unit,
    onJoin: (LobbyTable) -> Unit,
    onWatch: (LobbyTable) -> Unit,
) {
    val hasOpenSeat = table.seatsFilled < table.seatsTotal
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = table.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall)) {
                DetailLine(label = "Host", value = table.host)
                DetailLine(label = "Format", value = table.gameType)
                if (table.deckType.isNotBlank()) DetailLine(label = "Deck", value = table.deckType)
                DetailLine(label = "Seats", value = seatsLabel(table))
                DetailLine(label = "State", value = table.state.label())
                DetailLine(label = "Skill", value = table.skillLevel.label())
                val flags = table.flags()
                if (flags.isNotEmpty()) {
                    DetailLine(label = "Flags", value = flags.joinToString(", ") { it.label })
                }
            }
        },
        confirmButton = {
            // Story 0038: Join is live for a table with an open seat; a full table offers Watch only.
            MagePrimaryButton(
                text = LOBBY_JOIN_LABEL,
                onClick = {
                    onJoin(table)
                    onDismiss()
                },
                enabled = hasOpenSeat,
            )
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
                MageTextButton(
                    text = LOBBY_WATCH_LABEL,
                    onClick = {
                        onWatch(table)
                        onDismiss()
                    },
                )
                MageTextButton(text = "Close", onClick = onDismiss)
            }
        },
    )
}

@Composable
private fun DetailLine(
    label: String,
    value: String,
) {
    Text(
        text = "$label: $value",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

// ---- Previews ---------------------------------------------------------------------------------

private fun previewState(
    phase: LobbyPhase,
    tables: List<LobbyTable> = sampleTables,
    filter: LobbyFilter = LobbyFilter(),
    isRefreshing: Boolean = false,
    errorMessage: String? = null,
): LobbyUiState =
    LobbyUiState(
        phase = phase,
        tables = tables.applyFilter(filter),
        totalTables = tables.size,
        roomUserCount = 5,
        gameTypeNames = listOf("Two Player Duel", "Booster Draft", "Commander Free For All"),
        filter = filter,
        isRefreshing = isRefreshing,
        errorMessage = errorMessage,
    )

@Composable
private fun previewScreen(
    uiState: LobbyUiState,
    detailTable: LobbyTable? = null,
) {
    MageTheme {
        LobbyScreen(
            uiState = uiState,
            onBack = {},
            onRefresh = {},
            onGameTypeSelected = {},
            onHidePasswordedChange = {},
            onHideFullChange = {},
            onSortSelected = {},
            onResetFilters = {},
            detailTable = detailTable,
            onTableSelected = {},
            onDetailDismiss = {},
        )
    }
}

@Preview(name = "Lobby content - light", showBackground = true, heightDp = 720)
@Preview(
    name = "Lobby content - dark",
    showBackground = true,
    heightDp = 720,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun LobbyContentPreview() = previewScreen(previewState(LobbyPhase.Content))

@Preview(name = "Lobby empty", showBackground = true, heightDp = 720)
@Composable
private fun LobbyEmptyPreview() = previewScreen(previewState(LobbyPhase.Content, tables = emptyList()))

@Preview(name = "Lobby loading", showBackground = true, heightDp = 720)
@Composable
private fun LobbyLoadingPreview() = previewScreen(previewState(LobbyPhase.Loading, tables = emptyList()))

@Preview(name = "Lobby error", showBackground = true, heightDp = 720)
@Composable
private fun LobbyErrorPreview() = previewScreen(previewState(LobbyPhase.Error, tables = emptyList(), errorMessage = "No active session"))

@Preview(name = "Lobby disconnected", showBackground = true, heightDp = 720)
@Composable
private fun LobbyDisconnectedPreview() = previewScreen(previewState(LobbyPhase.Disconnected, tables = emptyList()))

@Preview(name = "Lobby detail dialog", showBackground = true, heightDp = 720)
@Composable
private fun LobbyDetailPreview() = previewScreen(previewState(LobbyPhase.Content), detailTable = sampleTables[2])
