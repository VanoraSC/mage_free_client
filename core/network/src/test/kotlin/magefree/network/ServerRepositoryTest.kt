package magefree.network

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import magefree.model.ServerTarget
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * Round-trip coverage for [ServerRepository]: persistence, ordering, upsert-by-endpoint, removal, and
 * reactive observation via the `servers` [Flow].
 *
 * The repository is exercised against an in-memory [DataStore] of [Preferences] ([FakePreferencesDataStore]).
 * This keeps the test cross-platform: the file-backed `PreferenceDataStoreFactory` uses a `java.io`
 * storage whose `File.renameTo` cannot overwrite an existing file on a **Windows** host, so any second
 * write throws — a limitation of DataStore's own (AndroidX-tested, correct-on-device) file layer, not
 * of this repository. The fake honours the exact [DataStore] contract (`data` snapshot + serialized
 * `updateData`), so everything `ServerRepository` owns — the real `Preferences` key, the kotlinx
 * serialization round-trip, and the reactive flow — is exercised for real.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ServerRepositoryTest {
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var repository: ServerRepository

    private val local = ServerTarget(host = "bridge.local", port = 9000, displayName = "Local")
    private val remote = ServerTarget(host = "xmage.example.com", port = 443, secure = true)

    @Before
    fun setUp() {
        dataStore = FakePreferencesDataStore()
        repository = ServerRepository(dataStore)
    }

    @Test
    fun startsEmpty() =
        runTest {
            assertEquals(emptyList<ServerTarget>(), repository.servers.first())
        }

    @Test
    fun addPersistsAndPreservesOrder() =
        runTest {
            repository.add(local)
            repository.add(remote)

            assertEquals(listOf(local, remote), repository.servers.first())
        }

    @Test
    fun addRoundTripsThroughAFreshRepositoryInstance() =
        runTest {
            repository.add(remote)

            // A new repository over the same DataStore reads the persisted value back verbatim,
            // including the non-default secure/port fields — the full serialization round-trip.
            val reopened = ServerRepository(dataStore)
            assertEquals(listOf(remote), reopened.servers.first())
        }

    @Test
    fun addUpsertsByEndpointKeepingPosition() =
        runTest {
            repository.add(local)
            repository.add(remote)
            // Same (host, port) as `local`, new label — updates in place rather than duplicating.
            val relabelled = local.copy(displayName = "Home bridge")
            repository.add(relabelled)

            val servers = repository.servers.first()
            assertEquals(2, servers.size)
            assertEquals(relabelled, servers.last())
        }

    @Test
    fun removeDeletesByEndpoint() =
        runTest {
            repository.add(local)
            repository.add(remote)

            repository.remove(local)

            assertEquals(listOf(remote), repository.servers.first())
        }

    @Test
    fun serversFlowEmitsOnEveryChange() =
        runTest {
            repository.servers.test {
                assertEquals(emptyList<ServerTarget>(), awaitItem())

                repository.add(local)
                assertEquals(listOf(local), awaitItem())

                repository.add(remote)
                assertEquals(listOf(local, remote), awaitItem())

                repository.remove(local)
                assertEquals(listOf(remote), awaitItem())

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun corruptStoredValueDegradesToEmptyList() =
        runTest {
            // F6: a corrupt/unparseable stored JSON value must degrade to an empty list, not throw into
            // every collector (the server-list screen).
            dataStore.edit { prefs -> prefs[stringPreferencesKey("server_targets")] = "not-valid-json {{{" }

            assertEquals(emptyList<ServerTarget>(), repository.servers.first())
        }

    @Test
    fun aReadIOExceptionDegradesToEmptyList() =
        runTest {
            // F6: an IOException on the DataStore read path (the canonical failure) is recovered as
            // emptyPreferences → an empty list, rather than propagating to collectors.
            val throwing =
                object : DataStore<Preferences> {
                    override val data: Flow<Preferences> = flow { throw IOException("simulated read failure") }

                    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
                        throw UnsupportedOperationException("not needed for this test")
                }

            assertEquals(emptyList<ServerTarget>(), ServerRepository(throwing).servers.first())
        }

    /**
     * Minimal in-memory [DataStore] of [Preferences] honouring the contract `ServerRepository`
     * depends on: `data` replays the current snapshot then every update, and `updateData` applies its
     * transform under a mutex (serialized, read-modify-write). No file I/O — deterministic on every OS.
     */
    private class FakePreferencesDataStore : DataStore<Preferences> {
        private val mutex = Mutex()
        private val state = MutableStateFlow(emptyPreferences())

        override val data: Flow<Preferences> = state.asStateFlow()

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            mutex.withLock {
                // The next update copies via toMutablePreferences(), so storing the result directly
                // never leaks a shared mutable instance.
                val updated = transform(state.value)
                state.value = updated
                updated
            }
    }
}
