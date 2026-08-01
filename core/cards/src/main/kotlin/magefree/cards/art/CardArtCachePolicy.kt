package magefree.cards.art

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

/**
 * How aggressively fetched card art is retained.
 *
 * - [PERSISTENT] — the default: art is cached to disk, so a card seen once is instant next time and
 *   available offline. This is also what a bulk pre-download warms.
 * - [SESSION_ONLY] — a storage/privacy-conscious setting: art is kept only in the in-memory LRU and
 *   never written to disk. Coil's disk cache is disabled entirely under this policy.
 */
enum class CardArtCachePolicy {
    PERSISTENT,
    SESSION_ONLY,
    ;

    /** True when the disk cache is active (only [PERSISTENT] persists across process restarts). */
    val usesDiskCache: Boolean get() = this == PERSISTENT

    companion object {
        val DEFAULT: CardArtCachePolicy = PERSISTENT

        internal fun fromName(raw: String?): CardArtCachePolicy = entries.firstOrNull { it.name == raw } ?: DEFAULT
    }
}

/**
 * Persists the user's [CardArtCachePolicy] (AGENTS.md: DataStore for prefs) so it survives restarts.
 *
 * The read path degrades gracefully — a DataStore [IOException] recovers to [emptyPreferences], i.e.
 * the [CardArtCachePolicy.DEFAULT] — mirroring `:core:network`'s `ServerRepository`.
 */
class CardArtCachePolicyRepository(
    private val dataStore: DataStore<Preferences>,
) {
    val policy: Flow<CardArtCachePolicy> =
        dataStore.data
            .catch { cause ->
                if (cause is IOException) emit(emptyPreferences()) else throw cause
            }.map { prefs -> CardArtCachePolicy.fromName(prefs[KEY]) }

    suspend fun setPolicy(policy: CardArtCachePolicy) {
        dataStore.edit { prefs -> prefs[KEY] = policy.name }
    }

    private companion object {
        val KEY = stringPreferencesKey("card_art_cache_policy")
    }
}
