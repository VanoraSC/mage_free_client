package magefree.feature.connect

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import magefree.model.ServerTarget
import org.koin.androidx.compose.koinViewModel

/**
 * The `:feature:connect` entry point: a self-contained connect + sign-in flow the app shell mounts
 * behind its "connect" destination. It coordinates the two stateful routes — pick a server, then sign
 * in — and raises [onConnected] once a session is established and the player continues.
 *
 * Deliberately holds no navigation dependency of its own: the selected server is kept in a
 * config-change-surviving [rememberSaveable] and the flow hands control back to the caller via
 * [onConnected]. Wiring this into `:app`'s navigation graph is a separate, out-of-scope step (
 * keeps the module self-contained; see the scope note).
 */
@Composable
fun ConnectFlow(
    onConnected: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenDecks: () -> Unit = {},
) {
    var selectedServer by rememberSaveable(stateSaver = ServerTargetSaver) {
        mutableStateOf<ServerTarget?>(null)
    }

    val server = selectedServer
    if (server == null) {
        ServerListRoute(
            onSelectServer = { selectedServer = it },
            onOpenDecks = onOpenDecks,
            modifier = modifier,
        )
    } else {
        SignInRoute(
            server = server,
            onConnected = onConnected,
            onBack = { selectedServer = null },
            modifier = modifier,
        )
    }
}

/** Stateful server-list route: binds [ServerListViewModel] to the stateless [ServerListScreen]. */
@Composable
fun ServerListRoute(
    onSelectServer: (ServerTarget) -> Unit,
    modifier: Modifier = Modifier,
    onOpenDecks: () -> Unit = {},
    viewModel: ServerListViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    ServerListScreen(
        uiState = uiState,
        onSelectServer = onSelectServer,
        onAddServer = viewModel::openAddServer,
        onOpenDecks = onOpenDecks,
        onEditServer = viewModel::openEditServer,
        onRemoveServer = viewModel::removeServer,
        onEditorNameChange = viewModel::updateEditorName,
        onEditorHostChange = viewModel::updateEditorHost,
        onEditorPortChange = viewModel::updateEditorPort,
        onEditorSecureChange = viewModel::updateEditorSecure,
        onEditorSave = viewModel::saveEditor,
        onEditorDismiss = viewModel::dismissEditor,
        modifier = modifier,
    )
}

/** Stateful sign-in route: binds [SignInViewModel] to the stateless [SignInScreen] for [server]. */
@Composable
fun SignInRoute(
    server: ServerTarget,
    onConnected: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SignInViewModel = koinViewModel(),
) {
    LaunchedEffect(server) { viewModel.bind(server) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    SignInScreen(
        uiState = uiState,
        onUsernameChange = viewModel::updateUsername,
        onPasswordChange = viewModel::updatePassword,
        onConnect = viewModel::connect,
        onRetry = viewModel::retry,
        onCancel = viewModel::cancel,
        onContinue = onConnected,
        onBack = {
            viewModel.cancel()
            onBack()
        },
        modifier = modifier,
    )
}

/**
 * A [rememberSaveable] saver for the nullable selected [ServerTarget], persisting its fields so the
 * flow survives configuration change without making the `:core:model` type Parcelable.
 */
private val ServerTargetSaver =
    listSaver<ServerTarget?, Any?>(
        save = { target ->
            if (target == null) emptyList() else listOf(target.host, target.port, target.secure, target.displayName)
        },
        restore = { saved ->
            if (saved.isEmpty()) {
                null
            } else {
                ServerTarget(
                    host = saved[0] as String,
                    port = saved[1] as Int,
                    secure = saved[2] as Boolean,
                    displayName = saved[3] as String?,
                )
            }
        },
    )
