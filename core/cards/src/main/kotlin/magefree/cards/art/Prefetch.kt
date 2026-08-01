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

/** Lifecycle of a bulk pre-download run. */
enum class PrefetchStatus {
    /** Nothing has run yet. */
    IDLE,

    /** A run is in progress. */
    RUNNING,

    /** Finished warming every target. */
    COMPLETED,

    /** The user cancelled mid-run (already-warmed art stays cached; a re-run resumes). */
    CANCELLED,

    /** The run could not enumerate its targets. */
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

    /** Progress in `[0, 1]`; `0` before a total is known. */
    val fraction: Float get() = if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)
}

/** Resolves a [PrefetchScope] to the concrete [CardArtRequest]s to warm. */
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
