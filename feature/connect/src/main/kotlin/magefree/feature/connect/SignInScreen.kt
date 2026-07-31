package magefree.feature.connect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import magefree.designsystem.component.MagePrimaryButton
import magefree.designsystem.component.MageSectionHeader
import magefree.designsystem.component.MageTextButton
import magefree.designsystem.component.MageTopAppBar
import magefree.designsystem.theme.MageTheme
import magefree.designsystem.theme.Spacing
import magefree.model.ServerTarget

/** Visible label on the primary Connect action; shared with tests. */
const val CONNECT_LABEL: String = "Connect"

/**
 * Stateless sign-in screen. When [SignInUiState.phase] is [ConnectPhase.Idle] it shows the credential
 * form with a thumb-reachable, validation-gated [CONNECT_LABEL] primary; otherwise it delegates to the
 * matching connection-state surface. Every failure surface routes through [DecisionPrompt] (inside the
 * surfaces) for the retry/cancel choice, and [ConnectPhase.AuthFailed] vs [ConnectPhase.VersionUnsupported]
 * render as visibly distinct surfaces.
 *
 * All events are hoisted; the Composable fetches nothing and holds no business state (local `remember`
 * is used only for the password-reveal toggle).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignInScreen(
    uiState: SignInUiState,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onConnect: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val server = uiState.server
    Scaffold(
        modifier = modifier,
        topBar = {
            MageTopAppBar(
                title = "Sign in",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back to servers",
                        )
                    }
                },
            )
        },
        bottomBar = {
            if (uiState.phase == ConnectPhase.Idle) {
                MagePrimaryButton(
                    text = CONNECT_LABEL,
                    onClick = onConnect,
                    enabled = uiState.canSubmit,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(Spacing.medium),
                )
            }
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            when (uiState.phase) {
                ConnectPhase.Idle ->
                    SignInForm(
                        server = server,
                        username = uiState.username,
                        password = uiState.password,
                        onUsernameChange = onUsernameChange,
                        onPasswordChange = onPasswordChange,
                    )
                ConnectPhase.Connecting ->
                    server?.let { ConnectingSurface(server = it, reconnecting = false) }
                ConnectPhase.Reconnecting ->
                    server?.let { ConnectingSurface(server = it, reconnecting = true) }
                ConnectPhase.Restoring ->
                    server?.let { RestoringSurface(server = it) }
                ConnectPhase.Connected ->
                    server?.let { ConnectedSurface(server = it, onContinue = onContinue) }
                ConnectPhase.AuthFailed ->
                    AuthFailedSurface(onRetry = onRetry, onCancel = onCancel)
                ConnectPhase.VersionUnsupported ->
                    VersionUnsupportedSurface(onRetry = onRetry, onCancel = onCancel, detail = uiState.detail)
                ConnectPhase.Network ->
                    NetworkSurface(onRetry = onRetry, onCancel = onCancel, detail = uiState.detail)
                ConnectPhase.SessionLost ->
                    SessionLostSurface(
                        // Re-authenticate returns to the credential form with the same server still
                        // bound (last server pre-selected); "choose another server" pops to the list.
                        onReauthenticate = onCancel,
                        onBackToServers = onBack,
                        detail = uiState.detail,
                    )
            }
        }
    }
}

/** The idle-phase credential form. */
@Composable
private fun SignInForm(
    server: ServerTarget?,
    username: String,
    password: String,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
) {
    var revealPassword by remember { mutableStateOf(false) }
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.medium),
        verticalArrangement = Arrangement.spacedBy(Spacing.small),
    ) {
        MageSectionHeader(text = "Server")
        Text(
            text = server?.let { "${it.title} · ${it.endpoint}" } ?: "No server selected",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.medium),
        )
        MageSectionHeader(text = "Credentials")
        OutlinedTextField(
            value = username,
            onValueChange = onUsernameChange,
            label = { Text("Username") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password,
            onValueChange = onPasswordChange,
            label = { Text("Password") },
            singleLine = true,
            visualTransformation =
                if (revealPassword) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions =
                androidx.compose.foundation.text
                    .KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                MageTextButton(
                    text = if (revealPassword) "Hide" else "Show",
                    onClick = { revealPassword = !revealPassword },
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = "Password is used only to sign in and is never saved.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private val PreviewServer = ServerTarget(host = "bridge.local", port = 9000, displayName = "Local bridge")

@Preview(name = "Sign in form - light", showBackground = true, heightDp = 640)
@Preview(
    name = "Sign in form - dark",
    showBackground = true,
    heightDp = 640,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun SignInFormPreview() {
    MageTheme {
        SignInScreen(
            uiState = SignInUiState(server = PreviewServer, username = "pete", phase = ConnectPhase.Idle),
            onUsernameChange = {},
            onPasswordChange = {},
            onConnect = {},
            onRetry = {},
            onCancel = {},
            onContinue = {},
            onBack = {},
        )
    }
}

@Preview(name = "Sign in connecting", showBackground = true, heightDp = 640)
@Composable
private fun SignInConnectingPreview() {
    MageTheme {
        SignInScreen(
            uiState = SignInUiState(server = PreviewServer, username = "pete", phase = ConnectPhase.Connecting),
            onUsernameChange = {},
            onPasswordChange = {},
            onConnect = {},
            onRetry = {},
            onCancel = {},
            onContinue = {},
            onBack = {},
        )
    }
}

@Preview(name = "Sign in auth failed", showBackground = true, heightDp = 640)
@Composable
private fun SignInAuthFailedPreview() {
    MageTheme {
        SignInScreen(
            uiState = SignInUiState(server = PreviewServer, username = "pete", phase = ConnectPhase.AuthFailed),
            onUsernameChange = {},
            onPasswordChange = {},
            onConnect = {},
            onRetry = {},
            onCancel = {},
            onContinue = {},
            onBack = {},
        )
    }
}
