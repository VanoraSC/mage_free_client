package magefree.cards.art

import magefree.cards.CardCatalog
import magefree.cards.model.CardFilter

/** What the user chose to bulk pre-download. */
sealed interface PrefetchScope {
    /** Every card in the catalog (one printing each). */
    data object All : PrefetchScope

    /** Every card that has a printing in [setCode] (that set's printing). */
    data class Set(
        val setCode: String,
    ) : PrefetchScope

    /** Every card legal in any of [legalSets] (a set-restricted format's pool). */
    data class Format(
        val legalSets: kotlin.collections.Set<String>,
    ) : PrefetchScope
}

/**
 * The image sizes a bulk pre-download warms: **every** [CardArtSize], because every one of them is a
 * size the UI displays — SMALL is the browse/add results thumbnail, LARGE the inspection view and the
 * builder's deck rows.
 *
 * This matters because the art cache is keyed by resolved URL and [XMageImageSource] appends
 * `version=small` for SMALL, so SMALL and LARGE are **different cache entries**. Warming one size
 * leaves every surface that displays the other blank offline, which defeats the point of the feature
 *. Derived from [CardArtSize.entries] so a new size cannot be silently missed.
 */
val PREFETCH_SIZES: Set<CardArtSize> = CardArtSize.entries.toSet()

/** Lifecycle of a bulk pre-download run. */
enum class PrefetchStatus {
    /** Nothing has run yet. */
    IDLE,

    /** A run is in progress. */
    RUNNING,

    /**
     * Every target was attempted. [PrefetchProgress.failed] `> 0` means a **partial** run — some
     * targets could not be warmed (a 404, a corrupt cache entry, no connectivity); a re-run resumes
     * over them, skipping what is already cached.
     */
    COMPLETED,

    /** The user cancelled mid-run (already-warmed art stays cached; a re-run resumes). */
    CANCELLED,

    /** The run could not enumerate its targets, or failed structurally before finishing them. */
    FAILED,
}

/**
 * Immutable snapshot of a bulk pre-download, exposed as a `StateFlow` by [ArtDownloadManager].
 *
 * [done] = [warmed] + [skipped] + [failed]; [skipped] are entries that were already cached (this is
 * what makes a cancelled run *resumable* — re-running skips completed work).
 */
data class PrefetchProgress(
    val status: PrefetchStatus = PrefetchStatus.IDLE,
    val total: Int = 0,
    val warmed: Int = 0,
    val skipped: Int = 0,
    val failed: Int = 0,
    val current: String? = null,
    val error: String? = null,
) {
    val done: Int get() = warmed + skipped + failed

    /**
     * True once the run has stopped for good. Every run reaches one of these, so a UI bound to this
     * flow always gets to stop showing progress.
     */
    val isTerminal: Boolean
        get() = status == PrefetchStatus.COMPLETED || status == PrefetchStatus.CANCELLED || status == PrefetchStatus.FAILED

    /** Progress in `[0, 1]`; `0` before a total is known. */
    val fraction: Float get() = if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)
}

/**
 * Resolves a [PrefetchScope] to the concrete [CardArtRequest]s to warm **at one [CardArtSize]**. A run
 * that covers several sizes ([PREFETCH_SIZES]) calls this once per size and warms the union.
 */
interface PrefetchTargetSource {
    suspend fun requests(
        scope: PrefetchScope,
        size: CardArtSize,
    ): List<CardArtRequest>
}

/**
 * The production [PrefetchTargetSource]: enumerates the bundled 0030 catalog (read-only — no catalog
 * change) via [CardCatalog.filter], one printing per card, and adds the back-face request for
 * double-faced cards so full offline art includes card backs.
 */
class CatalogPrefetchTargetSource(
    private val catalog: CardCatalog,
) : PrefetchTargetSource {
    override suspend fun requests(
        scope: PrefetchScope,
        size: CardArtSize,
    ): List<CardArtRequest> {
        val limit = catalog.counts().cardCount.coerceAtLeast(1)
        val cards =
            when (scope) {
                PrefetchScope.All -> catalog.filter(CardFilter(), limit)
                is PrefetchScope.Set -> catalog.filter(CardFilter(setCode = scope.setCode), limit)
                is PrefetchScope.Format -> catalog.filter(CardFilter(legalSets = scope.legalSets), limit)
            }

        return cards.flatMap { card ->
            val printing =
                when (scope) {
                    PrefetchScope.All -> card.printings.firstOrNull()
                    is PrefetchScope.Set ->
                        card.printings.firstOrNull { it.setCode.equals(scope.setCode, ignoreCase = true) }
                            ?: card.printings.firstOrNull()
                    is PrefetchScope.Format ->
                        card.printings.firstOrNull { it.setCode in scope.legalSets }
                            ?: card.printings.firstOrNull()
                } ?: return@flatMap emptyList()

            buildList {
                add(CardArtRequest.of(printing, CardArtFace.FRONT, size))
                if (card.faces.doubleFaced || card.faces.modalDoubleFaced) {
                    add(CardArtRequest.of(printing, CardArtFace.BACK, size))
                }
            }
        }
    }
}
