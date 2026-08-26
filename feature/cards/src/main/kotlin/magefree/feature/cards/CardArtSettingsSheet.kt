package magefree.feature.cards

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import magefree.cards.art.CardArtCachePolicy
import magefree.cards.art.PrefetchProgress
import magefree.cards.art.PrefetchStatus
import magefree.designsystem.component.MagePrimaryButton
import magefree.designsystem.component.MageSecondaryButton
import magefree.designsystem.component.MageSectionHeader
import magefree.designsystem.theme.MageTheme
import magefree.designsystem.theme.Spacing

/**
 * The lightweight art cache/download affordance — NOT a full settings screen.
 * Stateless: it surfaces the cache policy (Persistent / Session only) as a two-option toggle,
 * and an opt-in "download all art" action with live progress + cancel. Every event is hoisted.
 */
@Composable
fun CardArtSettingsSheet(
    uiState: CardArtSettingsUiState,
    onPolicyChange: (CardArtCachePolicy) -> Unit,
    onDownloadAll: () -> Unit,
    onCancelDownload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(bottom = Spacing.large),
        verticalArrangement = Arrangement.spacedBy(Spacing.small),
    ) {
        MageSectionHeader(text = "Card art cache")
        Text(
            text = "Choose whether downloaded art is kept on disk for offline use, or only for this session.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.medium),
        )
        CachePolicyToggle(
            policy = uiState.policy,
            onPolicyChange = onPolicyChange,
            modifier = Modifier.padding(horizontal = Spacing.medium),
        )

        MageSectionHeader(text = "Pre-download art")
        Text(
            text = "Warm the cache with every card's art for full offline browsing. This is optional and can be cancelled.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.medium),
        )
        PrefetchControls(
            uiState = uiState,
            onDownloadAll = onDownloadAll,
            onCancelDownload = onCancelDownload,
            modifier = Modifier.padding(horizontal = Spacing.medium),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CachePolicyToggle(
    policy: CardArtCachePolicy,
    onPolicyChange: (CardArtCachePolicy) -> Unit,
    modifier: Modifier = Modifier,
) {
    val options = listOf(CardArtCachePolicy.PERSISTENT, CardArtCachePolicy.SESSION_ONLY)
    SingleChoiceSegmentedButtonRow(modifier = modifier.fillMaxWidth()) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = policy == option,
                onClick = { onPolicyChange(option) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
            ) {
                Text(policyLabel(option))
            }
        }
    }
}

@Composable
private fun PrefetchControls(
    uiState: CardArtSettingsUiState,
    onDownloadAll: () -> Unit,
    onCancelDownload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val prefetch = uiState.prefetch
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.small),
    ) {
        if (uiState.isPrefetching) {
            LinearProgressIndicator(
                progress = { prefetch.fraction },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = "Downloading ${prefetch.done} of ${prefetch.total}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            MageSecondaryButton(text = "Cancel", onClick = onCancelDownload)
        } else {
            prefetchStatusLabel(prefetch)?.let { label ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            MagePrimaryButton(text = "Download all art", onClick = onDownloadAll)
        }
    }
}

private fun policyLabel(policy: CardArtCachePolicy): String =
    when (policy) {
        CardArtCachePolicy.PERSISTENT -> "Keep on disk"
        CardArtCachePolicy.SESSION_ONLY -> "Session only"
    }

private fun prefetchStatusLabel(progress: PrefetchProgress): String? =
    when (progress.status) {
        PrefetchStatus.COMPLETED -> "Downloaded ${progress.warmed} (skipped ${progress.skipped}, failed ${progress.failed})"
        PrefetchStatus.CANCELLED -> "Cancelled — ${progress.done} of ${progress.total} done. Re-run to resume."
        PrefetchStatus.FAILED -> "Couldn't start: ${progress.error ?: "unknown error"}"
        else -> null
    }

@Preview(name = "Art settings - idle (light)", showBackground = true)
@Preview(name = "Art settings - idle (dark)", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun CardArtSettingsIdlePreview() {
    MageTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            CardArtSettingsSheet(
                uiState = CardArtSettingsUiState(policy = CardArtCachePolicy.PERSISTENT),
                onPolicyChange = {},
                onDownloadAll = {},
                onCancelDownload = {},
            )
        }
    }
}

@Preview(name = "Art settings - downloading", showBackground = true)
@Composable
private fun CardArtSettingsDownloadingPreview() {
    MageTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            CardArtSettingsSheet(
                uiState =
                    CardArtSettingsUiState(
                        policy = CardArtCachePolicy.SESSION_ONLY,
                        prefetch = PrefetchProgress(status = PrefetchStatus.RUNNING, total = 200, warmed = 40, skipped = 5),
                    ),
                onPolicyChange = {},
                onDownloadAll = {},
                onCancelDownload = {},
            )
        }
    }
}
