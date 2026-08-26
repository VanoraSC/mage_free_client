package magefree.feature.connect

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import magefree.designsystem.component.EmptyState
import magefree.designsystem.component.LoadingState
import magefree.designsystem.component.prompt.DecisionEmphasis
import magefree.designsystem.component.prompt.DecisionPrompt
import magefree.designsystem.component.prompt.DecisionPromptChoice
import magefree.designsystem.theme.MageTheme
import magefree.designsystem.theme.Spacing
import magefree.model.ServerTarget

/*
 * The connection-state surfaces for the sign-in flow. Each is a stateless, previewable Composable
 * built entirely from the design system (LoadingState / EmptyState / DecisionPrompt) so the
 * five ConnectionState outcomes read distinctly and the retry/cancel choice comes to the thumb.
 *
 * Labels shared with tests are hoisted as constants.
 */

const val CONNECT_RETRY_LABEL: String = "Try again"
const val CONNECT_CANCEL_LABEL: String = "Cancel"
const val CONNECT_CONTINUE_LABEL: String = "Continue"
const val AUTH_FAILED_TITLE: String = "Sign-in failed"
const val VERSION_UNSUPPORTED_TITLE: String = "Server version unsupported"
const val NETWORK_ERROR_TITLE: String = "Can't reach the server"
const val SESSION_LOST_TITLE: String = "Session lost"
const val SESSION_LOST_REAUTH_LABEL: String = "Sign in again"
const val SESSION_LOST_BACK_LABEL: String = "Choose another server"

/** In-progress surface: a spinner + message, distinct copy for a first connect vs a reconnect. */
@Composable
fun ConnectingSurface(
    server: ServerTarget,
    reconnecting: Boolean,
    modifier: Modifier = Modifier,
) {
    val message =
        if (reconnecting) {
            "Reconnecting to ${server.title}…"
        } else {
            "Connecting to ${server.title}…"
        }
    LoadingState(modifier = modifier.fillMaxSize(), message = message)
}

/**
 * Restoring surface: the socket is back and the held session is being re-attached (resume in
 * progress). A non-destructive, spinner-backed indicator with copy distinct from [ConnectingSurface]'s
 * reconnecting message — the player is told their session is being restored, not thrown back to the
 * form. Clears to the shell once [ConnectPhase.Connected] follows.
 */
@Composable
fun RestoringSurface(
    server: ServerTarget,
    modifier: Modifier = Modifier,
) {
    LoadingState(
        modifier = modifier.fillMaxSize(),
        message = "Restoring your session on ${server.title}…",
    )
}

/**
 * Success surface. The handoff to the shell is explicit — a thumb-reachable [CONNECT_CONTINUE_LABEL]
 * primary — so a stateless Composable never performs navigation as a side effect.
 */
@Composable
fun ConnectedSurface(
    server: ServerTarget,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    EmptyState(
        modifier = modifier.fillMaxSize(),
        title = "Connected to ${server.title}",
        message = "You're signed in and ready to play.",
        actionLabel = CONNECT_CONTINUE_LABEL,
        onAction = onContinue,
    )
}

/** Authentication-failure surface: distinct copy + a bottom-anchored retry/cancel [DecisionPrompt]. */
@Composable
fun AuthFailedSurface(
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FailureSurface(
        title = AUTH_FAILED_TITLE,
        message = "The server rejected your username or password. Check your details and try again.",
        icon = Icons.Filled.Lock,
        onRetry = onRetry,
        onCancel = onCancel,
        modifier = modifier,
    )
}

/**
 * Version-mismatch surface — first-class and visibly distinct from [AuthFailedSurface]. [detail]
 * carries the wire diagnostic (e.g. `"server=<v> bridge=<v>"`) and is shown when present.
 *
 * The `ConnectionRepository` seam carries this detail through
 * `connectionStatus`, so [detail] is populated live from a real version mismatch.
 */
@Composable
fun VersionUnsupportedSurface(
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null,
) {
    val base =
        "This client is not compatible with the server's version. Update the app, or connect to a " +
            "compatible server."
    FailureSurface(
        title = VERSION_UNSUPPORTED_TITLE,
        message = if (detail.isNullOrBlank()) base else "$base\n\n$detail",
        icon = Icons.Filled.Warning,
        onRetry = onRetry,
        onCancel = onCancel,
        modifier = modifier,
    )
}

/**
 * Network/timeout surface — a transport failure ended the attempt, distinct from a login or version
 * error. [detail] carries the transport reason (e.g. the socket/timeout message) and is shown when
 * present so the failure is diagnosable, while the primary copy stays user-actionable.
 */
@Composable
fun NetworkSurface(
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null,
) {
    val base =
        "Couldn't reach the server. Check your connection and the server address, then try again."
    FailureSurface(
        title = NETWORK_ERROR_TITLE,
        message = if (detail.isNullOrBlank()) base else "$base\n\n$detail",
        icon = Icons.Filled.Warning,
        onRetry = onRetry,
        onCancel = onCancel,
        modifier = modifier,
    )
}

/**
 * Terminal **session-lost** recovery surface: shown only after 0024's reconnect/resume budget is
 * exhausted (or the parked session is gone and a fresh login is required). Distinct copy + a
 * re-authenticate CTA that routes back to sign-in with the last server pre-selected — visibly
 * different from [AuthFailedSurface]/[VersionUnsupportedSurface]/[NetworkSurface]. [detail] carries an
 * optional reason and is shown when present. Built on the design-system [EmptyState]/[DecisionPrompt].
 */
@Composable
fun SessionLostSurface(
    onReauthenticate: () -> Unit,
    onBackToServers: () -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null,
) {
    val base =
        "Your session ended and couldn't be restored. Sign in again to pick up where you left off."
    Box(modifier = modifier.fillMaxSize()) {
        EmptyState(
            modifier = Modifier.align(Alignment.Center).padding(bottom = Spacing.extraLarge),
            title = SESSION_LOST_TITLE,
            message = if (detail.isNullOrBlank()) base else "$base\n\n$detail",
            icon = Icons.Filled.Warning,
        )
        DecisionPrompt(
            modifier = Modifier.align(Alignment.BottomCenter),
            title = SESSION_LOST_TITLE,
            choices =
                listOf(
                    DecisionPromptChoice(label = SESSION_LOST_REAUTH_LABEL, emphasis = DecisionEmphasis.Primary),
                    DecisionPromptChoice(label = SESSION_LOST_BACK_LABEL, emphasis = DecisionEmphasis.Tertiary),
                ),
            onChoice = { choice ->
                when (choice.label) {
                    SESSION_LOST_REAUTH_LABEL -> onReauthenticate()
                    SESSION_LOST_BACK_LABEL -> onBackToServers()
                }
            },
        )
    }
}

/** Shared failure layout: a centered [EmptyState] explainer + a bottom [DecisionPrompt] for the choice. */
@Composable
private fun FailureSurface(
    title: String,
    message: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        EmptyState(
            modifier = Modifier.align(Alignment.Center).padding(bottom = Spacing.extraLarge),
            title = title,
            message = message,
            icon = icon,
        )
        DecisionPrompt(
            modifier = Modifier.align(Alignment.BottomCenter),
            title = title,
            choices =
                listOf(
                    DecisionPromptChoice(label = CONNECT_RETRY_LABEL, emphasis = DecisionEmphasis.Primary),
                    DecisionPromptChoice(label = CONNECT_CANCEL_LABEL, emphasis = DecisionEmphasis.Tertiary),
                ),
            onChoice = { choice ->
                when (choice.label) {
                    CONNECT_RETRY_LABEL -> onRetry()
                    CONNECT_CANCEL_LABEL -> onCancel()
                }
            },
        )
    }
}

private val PreviewServer = ServerTarget(host = "bridge.local", port = 9000, displayName = "Local bridge")

@Preview(name = "Connecting", showBackground = true, heightDp = 480)
@Composable
private fun ConnectingPreview() {
    MageTheme { ConnectingSurface(server = PreviewServer, reconnecting = false) }
}

@Preview(name = "Reconnecting", showBackground = true, heightDp = 480)
@Composable
private fun ReconnectingPreview() {
    MageTheme { ConnectingSurface(server = PreviewServer, reconnecting = true) }
}

@Preview(name = "Restoring", showBackground = true, heightDp = 480)
@Composable
private fun RestoringPreview() {
    MageTheme { RestoringSurface(server = PreviewServer) }
}

@Preview(name = "Connected", showBackground = true, heightDp = 480)
@Composable
private fun ConnectedPreview() {
    MageTheme { ConnectedSurface(server = PreviewServer, onContinue = {}) }
}

@Preview(name = "Session lost - light", showBackground = true, heightDp = 560)
@Preview(
    name = "Session lost - dark",
    showBackground = true,
    heightDp = 560,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun SessionLostPreview() {
    MageTheme {
        SessionLostSurface(
            onReauthenticate = {},
            onBackToServers = {},
            detail = "reconnect attempts exhausted",
        )
    }
}

@Preview(name = "Auth failed - light", showBackground = true, heightDp = 560)
@Preview(
    name = "Auth failed - dark",
    showBackground = true,
    heightDp = 560,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun AuthFailedPreview() {
    MageTheme { AuthFailedSurface(onRetry = {}, onCancel = {}) }
}

@Preview(name = "Version unsupported - light", showBackground = true, heightDp = 560)
@Preview(
    name = "Version unsupported - dark",
    showBackground = true,
    heightDp = 560,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun VersionUnsupportedPreview() {
    MageTheme {
        VersionUnsupportedSurface(onRetry = {}, onCancel = {}, detail = "server=1.4.61 bridge=1.4.60")
    }
}

@Preview(name = "Network error - light", showBackground = true, heightDp = 560)
@Preview(
    name = "Network error - dark",
    showBackground = true,
    heightDp = 560,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun NetworkPreview() {
    MageTheme {
        NetworkSurface(onRetry = {}, onCancel = {}, detail = "failed to open websocket: timeout")
    }
}
