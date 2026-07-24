package magefree.designsystem.component

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import magefree.designsystem.theme.MageTheme
import magefree.designsystem.theme.Sizing
import magefree.designsystem.theme.Spacing

/*
 * Reusable state surfaces: loading, empty, and error.
 *
 * Each is a centered, token-styled column with a clear text message so state is never conveyed by
 * color alone (icons and color merely reinforce the text). All are stateless; the error surface hoists
 * an optional retry action. They share [StateScaffold] for consistent centering and spacing.
 */

/**
 * A centered loading surface with a progress indicator and an optional [message].
 *
 * @param modifier the [Modifier] for the surface.
 * @param message optional text shown beneath the spinner (also announced to accessibility services).
 */
@Composable
fun LoadingState(
    modifier: Modifier = Modifier,
    message: String? = null,
) {
    StateScaffold(modifier = modifier) {
        CircularProgressIndicator(
            modifier =
                Modifier.clearAndSetSemantics {
                    contentDescription = message ?: "Loading"
                },
        )
        if (message != null) {
            StateSpacer()
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * A centered empty-state surface: a [title], optional [message], optional reinforcing [icon], and an
 * optional call-to-action.
 *
 * @param title the primary empty-state line.
 * @param modifier the [Modifier] for the surface.
 * @param message optional secondary explanatory line.
 * @param icon optional decorative icon reinforcing the message (paired with text, never color-only).
 * @param actionLabel label for the optional call-to-action; the action renders only when both this
 *   and [onAction] are provided.
 * @param onAction invoked when the call-to-action is tapped.
 */
@Composable
fun EmptyState(
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    icon: ImageVector? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    StateScaffold(modifier = modifier) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(Sizing.iconLarge),
            )
            StateSpacer()
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        if (message != null) {
            StateSpacer()
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (actionLabel != null && onAction != null) {
            StateSpacer()
            MagePrimaryButton(text = actionLabel, onClick = onAction)
        }
    }
}

/**
 * A centered error surface: a [message] and an optional retry action.
 *
 * @param message the human-readable error description.
 * @param modifier the [Modifier] for the surface.
 * @param icon optional decorative icon reinforcing the error (tinted with the error role; paired with
 *   text so state is never color-only).
 * @param retryLabel label for the retry action; defaults to "Retry".
 * @param onRetry invoked when retry is tapped; the retry button renders only when this is non-null.
 */
@Composable
fun ErrorState(
    message: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    retryLabel: String = "Retry",
    onRetry: (() -> Unit)? = null,
) {
    StateScaffold(modifier = modifier) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(Sizing.iconLarge),
            )
            StateSpacer()
        }
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        if (onRetry != null) {
            StateSpacer()
            MageSecondaryButton(text = retryLabel, onClick = onRetry)
        }
    }
}

/** Shared centering + padding for the state surfaces. */
@Composable
private fun StateScaffold(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(Spacing.large),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        content = content,
    )
}

/** Consistent inter-element gap inside a state surface. */
@Composable
private fun StateSpacer() {
    Spacer(Modifier.size(Spacing.medium))
}

@Preview(name = "States - light", showBackground = true)
@Preview(
    name = "States - dark",
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun StateViewsPreview() {
    MageTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.large)) {
                LoadingState(message = "Loading decks")
                EmptyState(
                    title = "No decks yet",
                    message = "Create your first deck to get started.",
                    icon = Icons.Filled.Star,
                    actionLabel = "New deck",
                    onAction = {},
                )
                ErrorState(
                    message = "Couldn't reach the server.",
                    icon = Icons.Filled.Star,
                    onRetry = {},
                )
            }
        }
    }
}
