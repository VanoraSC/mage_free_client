package magefree.app.connection.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import magefree.app.connection.ConnectionSeverity
import magefree.app.connection.ConnectionState
import magefree.app.connection.ConnectionStatusUiState
import magefree.app.connection.ConnectionStatusViewModel
import magefree.app.connection.toUiState
import magefree.app.theme.MageTheme

/** Visible label on the (stubbed) retry affordance; shared with tests. */
const val CONNECTION_RETRY_LABEL: String = "Retry"

/**
 * Hilt-connected connection-status strip mounted in the app shell. It observes the
 * [ConnectionStatusViewModel] and renders the stateless [ConnectionStatusBar] below, so the bar
 * itself stays preview-able and unit/UI-testable without DI.
 */
@Composable
fun ConnectionStatusBar(
    modifier: Modifier = Modifier,
    viewModel: ConnectionStatusViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    ConnectionStatusBar(
        uiState = uiState,
        onRetry = viewModel::onRetry,
        modifier = modifier,
    )
}

/**
 * Stateless connection-status strip. Renders one [ConnectionStatusUiState] so that each state is
 * **distinct in multiple channels** — a severity-tinted background, a leading indicator (a spinner
 * while in-progress, otherwise a colored dot), and a text label — and carries a single, distinct
 * [contentDescription][ConnectionStatusUiState.contentDescription] for screen readers (never
 * color-only). An optional [onRetry] affordance shows when the state offers one.
 */
@Composable
fun ConnectionStatusBar(
    uiState: ConnectionStatusUiState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (container, content) = severityColors(uiState.severity)
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = container,
        contentColor = content,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 32.dp)
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start,
        ) {
            // Indicator + label are one focusable group announcing the distinct state description
            // (not color-only). The retry button stays a separate, independently-focusable control.
            Row(
                modifier =
                    Modifier.semantics(mergeDescendants = true) {
                        contentDescription = uiState.contentDescription
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LeadingIndicator(uiState = uiState, tint = content)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = uiState.label,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            if (uiState.showRetry) {
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = onRetry) {
                    Text(text = CONNECTION_RETRY_LABEL)
                }
            }
        }
    }
}

/** Progress states show a spinner; steady states show a colored status dot. */
@Composable
private fun LeadingIndicator(
    uiState: ConnectionStatusUiState,
    tint: Color,
) {
    if (uiState.severity == ConnectionSeverity.Progress) {
        CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            strokeWidth = 2.dp,
            color = tint,
        )
    } else {
        Spacer(
            modifier =
                Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(tint),
        )
    }
}

/** Maps a semantic [ConnectionSeverity] to a theme-aware (container, content) color pair. */
@Composable
private fun severityColors(severity: ConnectionSeverity): Pair<Color, Color> {
    val scheme = MaterialTheme.colorScheme
    return when (severity) {
        ConnectionSeverity.Positive -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        ConnectionSeverity.Progress -> scheme.secondaryContainer to scheme.onSecondaryContainer
        ConnectionSeverity.Warning -> scheme.primaryContainer to scheme.onPrimaryContainer
        ConnectionSeverity.Error -> scheme.errorContainer to scheme.onErrorContainer
    }
}

@Preview(name = "Connection bar — light", showBackground = true)
@Preview(
    name = "Connection bar — dark",
    showBackground = true,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun ConnectionStatusBarPreview() {
    MageTheme {
        Column {
            ConnectionState.entries.forEach { state ->
                ConnectionStatusBar(uiState = state.toUiState(), onRetry = {})
                Spacer(modifier = Modifier.size(2.dp))
            }
        }
    }
}
