package magefree.feature.decks.art

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import magefree.cards.CardCatalog
import magefree.cards.art.ArtDownloadManager
import magefree.cards.art.ArtWarmer
import magefree.cards.art.CardArtFace
import magefree.cards.art.CardArtRequest
import magefree.cards.art.CardArtSize
import magefree.cards.art.PrefetchProgress
import magefree.cards.art.PrefetchScope
import magefree.cards.art.PrefetchTargetSource
import magefree.decks.model.Deck
import magefree.decks.model.DeckEntry

/**
 * The deck-scoped art pre-download surface the builder drives. It warms the art cache for **this deck's
 * own printings** (front faces, plus DFC/MDFC backs), honoring the active [CardArtCachePolicy] — so a
 * player can make a specific deck fully viewable offline before leaving connectivity. Opt-in, with live
 * [progress] and [cancel].
 *
 * Kept as a feature-local interface so [magefree.feature.decks.builder.BuilderViewModel] is unit-testable
 * against a fake; the production impl reuses the [ArtDownloadManager]/[ArtWarmer] unchanged.
 */
interface DeckArtDownloader {
    /** Live progress of the deck-scoped pre-download (shared shape with 0031's global one). */
    val progress: StateFlow<PrefetchProgress>

    /** Start warming art for [deck]'s printings (no-op if a run is already active). */
    fun download(deck: Deck)

    /** Cancel an in-progress run; already-warmed art is retained and a re-run resumes. */
    fun cancel()
}

/**
 * Production [DeckArtDownloader]. It composes the public [ArtDownloadManager] with a
 * deck-scoped [PrefetchTargetSource] ([DeckPrefetchTargetSource]) and the app's [ArtWarmer] (the
 * `CardImageLoader`). Nothing in `:core:cards` is modified: the manager, warmer, policy honoring,
 * progress model, and resume/cancel behavior are all reused as-is — only the *target set* differs.
 */
class DefaultDeckArtDownloader(
    catalog: CardCatalog,
    warmer: ArtWarmer,
    appScope: CoroutineScope,
) : DeckArtDownloader {
    private val targetSource = DeckPrefetchTargetSource(catalog)
    private val manager = ArtDownloadManager(targetSource = targetSource, warmer = warmer, appScope = appScope)

    override val progress: StateFlow<PrefetchProgress> get() = manager.progress

    override fun download(deck: Deck) {
        // Point the (single, reused) target source at this deck, then start. The manager enumerates the
        // deck's printings via the source at run time; the PrefetchScope argument is unused here.
        // No size argument either: the manager's default warms every size the UI displays — the
        // builder's rows (LARGE) *and* the add-cards results grid (SMALL). Pinning LARGE here left the
        // add grid blank offline, which is exactly what "viewable offline" promises (0043, defect A).
        targetSource.deck = deck
        manager.start(PrefetchScope.All)
    }

    override fun cancel() = manager.cancel()
}

/**
 * A [PrefetchTargetSource] whose targets are the current [deck]'s own printings rather than the whole
 * catalog. For every main + sideboard entry it emits the front-face request built from the entry's
 * stored set + collector number (offline — no catalog hit needed for the front), and additionally the
 * back-face request when the offline catalog knows the card is double-/modal-faced. Duplicates (a card
 * in both zones, or the same printing repeated) are collapsed. The [scope] argument is ignored — the
 * deck itself defines the scope.
 */
class DeckPrefetchTargetSource(
    private val catalog: CardCatalog,
) : PrefetchTargetSource {
    @Volatile
    var deck: Deck? = null

    override suspend fun requests(
        scope: PrefetchScope,
        size: CardArtSize,
    ): List<CardArtRequest> {
        val current = deck ?: return emptyList()
        val entries = current.main + current.sideboard
        // One batched, indexable exact-name lookup for the whole deck rather than a substring scan per
        // entry.
        val resolved = catalog.cardsByName(entries.map { it.cardName })
        val out = LinkedHashSet<CardArtRequest>()
        for (entry in entries) {
            if (entry.setCode.isBlank() && entry.collectorNumber.isBlank()) continue
            out += entry.request(CardArtFace.FRONT, size)
            val card = resolved[entry.cardName.lowercase()]
            if (card != null && (card.faces.doubleFaced || card.faces.modalDoubleFaced)) {
                out += entry.request(CardArtFace.BACK, size)
            }
        }
        return out.toList()
    }

    private fun DeckEntry.request(
        face: CardArtFace,
        size: CardArtSize,
    ): CardArtRequest = CardArtRequest(setCode = setCode, collectorNumber = collectorNumber, face = face, size = size)
}
