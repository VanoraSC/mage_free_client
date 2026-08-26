package magefree.decks

import kotlinx.coroutines.flow.Flow
import magefree.decks.model.Deck
import magefree.decks.model.DeckFormat
import magefree.decks.model.DeckId
import magefree.decks.model.DeckSummary

/**
 * The local, fully-offline deck library. Decks are the user's on-device data: every operation is a
 * local persistence read/write — no bridge, no network, ever. Reads run on an injected IO dispatcher
 * and the library list is exposed reactively so the UI updates on any change.
 *
 * Expected-but-absent conditions (loading/duplicating a deck that isn't there) are modelled as
 * `null`/no-op results rather than thrown exceptions, per the repo's error-as-state convention.
 */
interface DeckRepository {
    /** The whole library as a reactive list of [DeckSummary]s (favorites first, then most recent). */
    fun observeLibrary(): Flow<List<DeckSummary>>

    /** Load a full [Deck] (with its card lines) by id, or `null` if no such deck exists. */
    suspend fun load(id: DeckId): Deck?

    /** Create and persist a new empty deck, returning it (with generated id + timestamps). */
    suspend fun create(
        name: String,
        format: DeckFormat? = null,
    ): Deck

    /** Persist [deck] (insert or full replace of header + card lines), bumping its `updatedAt`. */
    suspend fun save(deck: Deck)

    /** Duplicate the deck with [id] under a new id (its name suffixed), or `null` if it's missing. */
    suspend fun duplicate(id: DeckId): Deck?

    /** Rename the deck with [id]; no-op if it doesn't exist. */
    suspend fun rename(
        id: DeckId,
        name: String,
    )

    /** Delete the deck with [id] (and its card lines); no-op if it doesn't exist. */
    suspend fun delete(id: DeckId)

    /** Set/clear the favorite flag on the deck with [id]; no-op if it doesn't exist. */
    suspend fun setFavorite(
        id: DeckId,
        favorite: Boolean,
    )
}
