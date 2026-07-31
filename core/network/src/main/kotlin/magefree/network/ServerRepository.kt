package magefree.network

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import magefree.model.ServerTarget
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists the user's saved bridge servers (story 0017) so the list survives restarts.
 *
 * Backed by a Preferences [DataStore] holding one JSON-encoded, order-preserving list under a single
 * key. `:core:model`'s [ServerTarget] stays free of serialization concerns — it is mapped to a
 * `:core:network`-private [StoredServer] DTO at this boundary, mirroring how the module already keeps
 * wire types out of the domain.
 *
 * A server's identity is its (host, port) pair: [add] upserts on that key (updating a saved label /
 * scheme), and [remove] deletes by it.
 */
@Singleton
class ServerRepository
    @Inject
    constructor(
        private val dataStore: DataStore<Preferences>,
    ) {
        /**
         * The saved servers, observable and reactive to every [add] / [remove].
         *
         * Story 0026 F6 — the read path degrades gracefully instead of throwing into the server-list
         * screen: the canonical DataStore [catch] recovers a read [IOException] by emitting
         * [emptyPreferences] (→ an empty list), and [decodeServers] tolerates a corrupt/unparseable
         * stored value by returning `[]`.
         */
        val servers: Flow<List<ServerTarget>> =
            dataStore.data
                .catch { cause ->
                    if (cause is IOException) emit(emptyPreferences()) else throw cause
                }.map { prefs -> prefs.decodeServers() }

        /** Add [target], or update the existing entry with the same (host, port). */
        suspend fun add(target: ServerTarget) {
            dataStore.edit { prefs ->
                val current = prefs.decodeServers().filterNot { it.sameEndpoint(target) }
                prefs[KEY] = json.encodeToString(current.plus(target).map(::StoredServer))
            }
        }

        /** Remove the entry matching [target]'s (host, port); a no-op if none matches. */
        suspend fun remove(target: ServerTarget) {
            dataStore.edit { prefs ->
                val remaining = prefs.decodeServers().filterNot { it.sameEndpoint(target) }
                prefs[KEY] = json.encodeToString(remaining.map(::StoredServer))
            }
        }

        private fun Preferences.decodeServers(): List<ServerTarget> =
            this[KEY]
                ?.let { stored ->
                    // Story 0026 F6: a corrupt/stale value must not throw into every collector; degrade
                    // to an empty list rather than propagating the SerializationException.
                    try {
                        json.decodeFromString<List<StoredServer>>(stored)
                    } catch (failure: SerializationException) {
                        emptyList()
                    }
                }?.map(StoredServer::toModel)
                ?: emptyList()

        private fun ServerTarget.sameEndpoint(other: ServerTarget): Boolean = host == other.host && port == other.port

        /** `:core:network`-private serialized form of a [ServerTarget]. */
        @Serializable
        private data class StoredServer(
            val host: String,
            val port: Int,
            val secure: Boolean = false,
            val displayName: String? = null,
        ) {
            constructor(target: ServerTarget) : this(
                host = target.host,
                port = target.port,
                secure = target.secure,
                displayName = target.displayName,
            )

            fun toModel(): ServerTarget = ServerTarget(host = host, port = port, secure = secure, displayName = displayName)
        }

        private companion object {
            val KEY = stringPreferencesKey("server_targets")
            val json = Json { ignoreUnknownKeys = true }
        }
    }
