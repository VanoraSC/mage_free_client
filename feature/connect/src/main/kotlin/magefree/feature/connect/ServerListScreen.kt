package magefree.feature.connect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import magefree.designsystem.component.EmptyState
import magefree.designsystem.component.LoadingState
import magefree.designsystem.component.MageListRow
import magefree.designsystem.component.MagePrimaryButton
import magefree.designsystem.component.MageSectionHeader
import magefree.designsystem.component.MageTextButton
import magefree.designsystem.component.MageTopAppBar
import magefree.designsystem.theme.MageTheme
import magefree.designsystem.theme.Spacing
import magefree.model.ServerTarget

const val ADD_SERVER_LABEL: String = "Add server"

/**
 * Stateless server list / add-server screen. Lists the persisted [ServerTarget]s as tappable
 * [MageListRow]s (design system), shows the design system's loading / empty surfaces, and anchors the
 * primary [ADD_SERVER_LABEL] action at the bottom for thumb reach. The add/edit form is a
 * [ServerEditorDialog] shown when [ServerListUiState.editor] is set.
 *
 * Every event is hoisted; the Composable fetches nothing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerListScreen(
    uiState: ServerListUiState,
    onSelectServer: (ServerTarget) -> Unit,
    onAddServer: () -> Unit,
    onEditServer: (ServerTarget) -> Unit,
    onRemoveServer: (ServerTarget) -> Unit,
    onEditorNameChange: (String) -> Unit,
    onEditorHostChange: (String) -> Unit,
    onEditorPortChange: (String) -> Unit,
    onEditorSecureChange: (Boolean) -> Unit,
    onEditorSave: () -> Unit,
    onEditorDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = { MageTopAppBar(title = "Servers") },
        bottomBar = {
            MagePrimaryButton(
                text = ADD_SERVER_LABEL,
                onClick = onAddServer,
                icon = Icons.Filled.Add,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(Spacing.medium),
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            when {
                uiState.isLoading -> LoadingState(message = "Loading servers")
                uiState.isEmpty ->
                    EmptyState(
                        title = "No servers yet",
                        message = "Add a bridge server to sign in and start playing.",
                        actionLabel = ADD_SERVER_LABEL,
                        onAction = onAddServer,
                    )
                else -> {
                    MageSectionHeader(text = "Saved servers")
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(items = uiState.servers, key = { "${it.host}:${it.port}" }) { server ->
                            MageListRow(
                                headline = server.title,
                                supportingText = server.endpoint,
                                onClick = { onSelectServer(server) },
                                trailingContent = {
                                    androidx.compose.foundation.layout.Row {
                                        IconButton(onClick = { onEditServer(server) }) {
                                            Icon(
                                                imageVector = Icons.Filled.Edit,
                                                contentDescription = "Edit ${server.title}",
                                            )
                                        }
                                        IconButton(onClick = { onRemoveServer(server) }) {
                                            Icon(
                                                imageVector = Icons.Filled.Delete,
                                                contentDescription = "Remove ${server.title}",
                                            )
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    uiState.editor?.let { editor ->
        ServerEditorDialog(
            state = editor,
            onNameChange = onEditorNameChange,
            onHostChange = onEditorHostChange,
            onPortChange = onEditorPortChange,
            onSecureChange = onEditorSecureChange,
            onSave = onEditorSave,
            onDismiss = onEditorDismiss,
        )
    }
}

/** The add/edit server form, rendered as an [AlertDialog] over the list. */
@Composable
private fun ServerEditorDialog(
    state: ServerEditorState,
    onNameChange: (String) -> Unit,
    onHostChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onSecureChange: (Boolean) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = if (state.isEdit) "Edit server" else ADD_SERVER_LABEL) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
                OutlinedTextField(
                    value = state.name,
                    onValueChange = onNameChange,
                    label = { Text("Name (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = state.host,
                    onValueChange = onHostChange,
                    label = { Text("Host") },
                    isError = state.host.isNotBlank() && state.hostError != null,
                    supportingText = state.hostError?.let { { Text(it) } },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = state.port,
                    onValueChange = onPortChange,
                    label = { Text("Port") },
                    isError = state.port.isNotBlank() && state.portError != null,
                    supportingText = state.portError?.let { { Text(it) } },
                    singleLine = true,
                    keyboardOptions =
                        androidx.compose.foundation.text
                            .KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                androidx.compose.foundation.layout.Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.extraSmall),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(text = "Use secure connection (wss)", style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = state.secure, onCheckedChange = onSecureChange)
                }
            }
        },
        confirmButton = {
            MagePrimaryButton(text = "Save", onClick = onSave, enabled = state.canSave)
        },
        dismissButton = {
            MageTextButton(text = "Cancel", onClick = onDismiss)
        },
    )
}

@Preview(name = "Server list - light", showBackground = true, heightDp = 640)
@Preview(
    name = "Server list - dark",
    showBackground = true,
    heightDp = 640,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun ServerListPopulatedPreview() {
    MageTheme {
        ServerListScreen(
            uiState =
                ServerListUiState(
                    isLoading = false,
                    servers =
                        listOf(
                            ServerTarget(host = "bridge.local", port = 9000, displayName = "Local bridge"),
                            ServerTarget(host = "xmage.example.com", port = 443, secure = true),
                        ),
                ),
            onSelectServer = {},
            onAddServer = {},
            onEditServer = {},
            onRemoveServer = {},
            onEditorNameChange = {},
            onEditorHostChange = {},
            onEditorPortChange = {},
            onEditorSecureChange = {},
            onEditorSave = {},
            onEditorDismiss = {},
        )
    }
}

@Preview(name = "Server list - empty", showBackground = true, heightDp = 640)
@Composable
private fun ServerListEmptyPreview() {
    MageTheme {
        ServerListScreen(
            uiState = ServerListUiState(isLoading = false, servers = emptyList()),
            onSelectServer = {},
            onAddServer = {},
            onEditServer = {},
            onRemoveServer = {},
            onEditorNameChange = {},
            onEditorHostChange = {},
            onEditorPortChange = {},
            onEditorSecureChange = {},
            onEditorSave = {},
            onEditorDismiss = {},
        )
    }
}

@Preview(name = "Server editor", showBackground = true, heightDp = 640)
@Composable
private fun ServerEditorPreview() {
    MageTheme {
        ServerListScreen(
            uiState =
                ServerListUiState(
                    isLoading = false,
                    servers = emptyList(),
                    editor = ServerEditorState(host = "bridge.local", port = "9000"),
                ),
            onSelectServer = {},
            onAddServer = {},
            onEditServer = {},
            onRemoveServer = {},
            onEditorNameChange = {},
            onEditorHostChange = {},
            onEditorPortChange = {},
            onEditorSecureChange = {},
            onEditorSave = {},
            onEditorDismiss = {},
        )
    }
}
