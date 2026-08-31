package magefree.feature.cards

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import magefree.cards.art.ArtDownloadManager
import magefree.cards.art.CardArtCachePolicy
import magefree.cards.art.CardArtCachePolicyRepository
import magefree.cards.art.PrefetchProgress
import magefree.cards.art.PrefetchScope
import magefree.cards.art.PrefetchStatus

/**
 * The narrow art-cache surface the settings ViewModel drives, wrapping the
 * [CardArtCachePolicyRepository] (Persistent/SessionOnly) and [ArtDownloadManager] (opt-in bulk
 * pre-download). Kept as a feature-local interface so the ViewModel is unit-testable against a fake —
 * :core:cards is consumed read-only and its concrete classes need Android/Coil to construct.
 */
interface ArtCacheController {
    /** The active, persisted cache policy. */
    val policy: Flow<CardArtCachePolicy>

    /** Live progress of the bulk pre-download. */
    val prefetchProgress: StateFlow<PrefetchProgress>

    /** Persist a new cache policy (a downgrade to SessionOnly clears the disk cache). */
    suspend fun setPolicy(policy: CardArtCachePolicy)

    /** Start the opt-in bulk pre-download for [scope] (no-op if one is already running). */
    fun startPrefetch(scope: PrefetchScope)

    /** Cancel an in-progress bulk pre-download (warmed art is retained; a re-run resumes). */
    fun cancelPrefetch()
}

/** Production [ArtCacheController] delegating to the policy repository + download manager. */
class DefaultArtCacheController
    constructor(
        private val policyRepository: CardArtCachePolicyRepository,
        private val downloadManager: ArtDownloadManager,
    ) : ArtCacheController {
        override val policy: Flow<CardArtCachePolicy> = policyRepository.policy
        override val prefetchProgress: StateFlow<PrefetchProgress> = downloadManager.progress

        override suspend fun setPolicy(policy: CardArtCachePolicy) = policyRepository.setPolicy(policy)

        // No size argument: the manager's default warms every size the UI displays. Pinning one size
        // here is what left the browse grid blank offline after a completed download (defect A).
        override fun startPrefetch(scope: PrefetchScope) = downloadManager.start(scope)

        override fun cancelPrefetch() = downloadManager.cancel()
    }

/**
 * Immutable UI state for the lightweight art cache/download affordance (not a full settings screen —
 * Surfaces the current [policy] and the [prefetch] progress.
 */
data class CardArtSettingsUiState(
    val policy: CardArtCachePolicy = CardArtCachePolicy.DEFAULT,
    val prefetch: PrefetchProgress = PrefetchProgress(),
) {
    /** True while a bulk pre-download is running (drives the progress + cancel affordance). */
    val isPrefetching: Boolean get() = prefetch.status == PrefetchStatus.RUNNING
}

/**
 * MVVM ViewModel for the art cache-policy + bulk pre-download affordance. Observes the
 * policy + prefetch progress via [ArtCacheController] and hoists the mutations. Deliberately minimal:
 * the cache toggle and an opt-in "download all art" with progress/cancel — the full settings screen
 * lives in settings.
 */

class CardArtSettingsViewModel
    constructor(
        private val controller: ArtCacheController,
    ) : ViewModel() {
        val uiState: StateFlow<CardArtSettingsUiState> =
            combine(controller.policy, controller.prefetchProgress) { policy, prefetch ->
                CardArtSettingsUiState(policy = policy, prefetch = prefetch)
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = CardArtSettingsUiState(),
            )

        /** Switch the cache policy (Persistent <-> SessionOnly). */
        fun setPolicy(policy: CardArtCachePolicy) {
            viewModelScope.launch { controller.setPolicy(policy) }
        }

        /** Start the opt-in bulk pre-download of all catalog art. */
        fun downloadAllArt() {
            controller.startPrefetch(PrefetchScope.All)
        }

        /** Cancel an in-progress bulk pre-download. */
        fun cancelDownload() {
            controller.cancelPrefetch()
        }

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }
