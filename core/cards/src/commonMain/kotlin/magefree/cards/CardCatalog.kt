package magefree.cards

import magefree.cards.model.Card
import magefree.cards.model.CardFilter
import magefree.cards.model.CardId
import magefree.cards.model.CatalogCounts

/**
 * The bundled, read-only, fully-offline XMage card catalog. Backed by a pre-built SQLite asset
 * generated from XMage's own card pool (see `tools/card-catalog-generator`); every query is a local,
 * indexed lookup — no bridge, no network. All calls are `suspend` and run on an injected IO
 * dispatcher so nothing blocks the main thread.
 *
 * Results are `:core:cards` app-schema [Card]s; no `mage.*` type is ever exposed on device.
 */
interface CardCatalog {
    /** Look up a single oracle card by its catalog id, or `null` if absent. */
    suspend fun card(id: CardId): Card?

    /**
     * Ranked name search. Matches are ordered exact → prefix → word-prefix → substring, then by
     * shorter name, then alphabetically. A blank query returns no results. Split-card halves are
     * matched too, so `"ice"` finds both `Ice` and `Fire // Ice`.
     */
    suspend fun search(
        query: String,
        limit: Int = DEFAULT_LIMIT,
    ): List<Card>

    /**
     * **Exact** case-insensitive name resolution for a batch of [names] — the deck path's lookup, and a
     * different query from [search]. Where [search] is a deliberately broad substring match for a human
     * typing into a box (`LIKE '%…%'`, which no index can serve), this is an equality predicate the
     * `card(name COLLATE NOCASE)` index answers directly, batched into one statement for a whole deck.
     *
     * The result maps each **lower-cased** requested name to its card; names the catalog doesn't know
     * are simply absent. Blank names are ignored. Callers key with `name.lowercase()`.
     */
    suspend fun cardsByName(names: Collection<String>): Map<String, Card>

    /** Single-name convenience over [cardsByName]; `null` when the catalog doesn't know the name. */
    suspend fun cardByName(name: String): Card? = cardsByName(listOf(name))[name.lowercase()]

    /** Apply a conjunctive [CardFilter] across the browse/build axes. */
    suspend fun filter(
        criteria: CardFilter,
        limit: Int = DEFAULT_LIMIT,
    ): List<Card>

    /** Total card / printing counts of the loaded bundle (coverage sanity). */
    suspend fun counts(): CatalogCounts

    companion object {
        const val DEFAULT_LIMIT: Int = 100
    }
}
