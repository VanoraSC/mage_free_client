package magefree.feature.decks.builder

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
 * The builder's art affordance: the cache-policy toggle plus an opt-in **deck-scoped** art
 * pre-download (this deck's own printings) with live progress and cancel. Surfacing the policy here
 * lets a player choose on-disk caching and warm exactly this deck's art before going offline. Stateless
 * — all events hoisted; the deck-scoped download is enqueued by [BuilderViewModel].
 */
@Composable
fun DeckArtSheet(
    policy: CardArtCachePolicy,
    prefetch: PrefetchProgress,
    onPolicyChange: (CardArtCachePolicy) -> Unit,
    onDownloadDeckArt: () -> Unit,
    onCancelDownload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(bottom = Spacing.large),
        verticalArrangement = Arrangement.spacedBy(Spacing.small),
    ) {
        MageSectionHeader(text = "Card art cache")
        Text(
            text = "Keep art on disk for offline use, or only for this session.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.medium),
        )
        CachePolicyToggle(
            policy = policy,
            onPolicyChange = onPolicyChange,
            modifier = Modifier.padding(horizontal = Spacing.medium),
        )

        MageSectionHeader(text = "Download this deck's art")
        Text(
            text = "Warm the cache with the art for every card in this deck, so it is viewable offline. Optional; can be cancelled.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.medium),
        )
        DeckPrefetchControls(
            prefetch = prefetch,
            onDownloadDeckArt = onDownloadDeckArt,
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
private fun DeckPrefetchControls(
    prefetch: PrefetchProgress,
    onDownloadDeckArt: () -> Unit,
    onCancelDownload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.small),
    ) {
        if (prefetch.status == PrefetchStatus.RUNNING) {
            LinearProgressIndicator(progress = { prefetch.fraction }, modifier = Modifier.fillMaxWidth())
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
            MagePrimaryButton(text = "Download deck art", onClick = onDownloadDeckArt)
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

@Preview(name = "Deck art - idle (light)", showBackground = true)
@Preview(name = "Deck art - idle (dark)", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun DeckArtSheetPreview() {
    MageTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            DeckArtSheet(
                policy = CardArtCachePolicy.PERSISTENT,
                prefetch = PrefetchProgress(status = PrefetchStatus.RUNNING, total = 75, warmed = 20, skipped = 5),
                onPolicyChange = {},
                onDownloadDeckArt = {},
                onCancelDownload = {},
            )
        }
    }
}
