package magefree.feature.connect

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import magefree.model.ServerTarget
import magefree.network.ServerRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Hermetic Turbine/coroutines-test coverage of [ServerListViewModel] over a **real** [ServerRepository]
 * backed by an in-memory [DataStore] — no device, no file I/O. Exercises the add / edit / remove /
 * validation events and the reactive list projection into the immutable [ServerListUiState].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ServerListViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    private val local = ServerTarget(host = "bridge.local", port = 9000, displayName = "Local")
    private val remote = ServerTarget(host = "xmage.example.com", port = 443, secure = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newViewModel(): ServerListViewModel = ServerListViewModel(ServerRepository(FakePreferencesDataStore()))

    /** Awaits items until one satisfies [predicate], returning it (drains intermediate emissions). */
    private suspend fun ReceiveTurbine<ServerListUiState>.awaitUntil(predicate: (ServerListUiState) -> Boolean): ServerListUiState {
        while (true) {
            val item = awaitItem()
            if (predicate(item)) return item
        }
    }

    /** Drives the editor to persist [target] and awaits the reactive list update. */
    private suspend fun ReceiveTurbine<ServerListUiState>.addServerVia(
        vm: ServerListViewModel,
        target: ServerTarget,
    ) {
        vm.openAddServer()
        awaitUntil { it.editor != null }
        vm.updateEditorName(target.displayName.orEmpty())
        vm.updateEditorHost(target.host)
        vm.updateEditorPort(target.port.toString())
        vm.updateEditorSecure(target.secure)
        vm.saveEditor()
        awaitUntil { state -> state.editor == null && state.servers.any { it.sameEndpoint(target) } }
    }

    private fun ServerTarget.sameEndpoint(other: ServerTarget) = host == other.host && port == other.port

    @Test
    fun startsLoadingThenSettlesToEmpty() =
        runTest {
            newViewModel().uiState.test {
                val settled = awaitUntil { !it.isLoading }
                assertTrue(settled.servers.isEmpty())
                assertTrue(settled.isEmpty)
                assertNull(settled.editor)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun addServerThroughEditorPersistsAndAppears() =
        runTest {
            val vm = newViewModel()
            vm.uiState.test {
                awaitUntil { !it.isLoading }

                vm.openAddServer()
                assertEquals(ServerEditorState(), awaitUntil { it.editor != null }.editor)

                vm.updateEditorName("Local")
                vm.updateEditorHost("bridge.local")
                vm.updateEditorPort("9000")
                vm.saveEditor()

                val done = awaitUntil { it.editor == null && it.servers.isNotEmpty() }
                assertEquals(listOf(local), done.servers)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun invalidPortBlocksSaveAndPersistsNothing() =
        runTest {
            val vm = newViewModel()
            vm.uiState.test {
                awaitUntil { !it.isLoading }
                vm.openAddServer()
                awaitUntil { it.editor != null }
                vm.updateEditorHost("bridge.local")
                vm.updateEditorPort("not-a-port")

                val invalid = awaitUntil { it.editor?.port == "not-a-port" }
                assertFalse("form must not be saveable with a bad port", invalid.editor!!.canSave)

                vm.saveEditor() // no-op while invalid
                assertTrue("nothing persisted", invalid.servers.isEmpty())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun blankHostBlocksSave() =
        runTest {
            val vm = newViewModel()
            vm.uiState.test {
                awaitUntil { !it.isLoading }
                vm.openAddServer()
                awaitUntil { it.editor != null }
                vm.updateEditorPort("9000")
                val state = awaitUntil { it.editor?.port == "9000" }
                assertFalse(state.editor!!.canSave)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun editUpsertsInPlaceWhenEndpointUnchanged() =
        runTest {
            val vm = newViewModel()
            vm.uiState.test {
                awaitUntil { !it.isLoading }
                addServerVia(vm, local)

                vm.openEditServer(local)
                awaitUntil { it.editor?.original == local }
                vm.updateEditorName("Home bridge")
                vm.saveEditor()

                val done = awaitUntil { it.editor == null && it.servers.singleOrNull()?.displayName == "Home bridge" }
                assertEquals(1, done.servers.size)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun editMovingEndpointRemovesOldAndAddsNew() =
        runTest {
            val vm = newViewModel()
            vm.uiState.test {
                awaitUntil { !it.isLoading }
                addServerVia(vm, local)

                vm.openEditServer(local)
                awaitUntil { it.editor?.original == local }
                vm.updateEditorPort("9443")
                vm.saveEditor()

                val done = awaitUntil { it.editor == null && it.servers.singleOrNull()?.port == 9443 }
                assertEquals(1, done.servers.size)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun removeServerDeletesIt() =
        runTest {
            val vm = newViewModel()
            vm.uiState.test {
                awaitUntil { !it.isLoading }
                addServerVia(vm, local)
                addServerVia(vm, remote)

                vm.removeServer(local)
                val done = awaitUntil { it.servers.none { s -> s.sameEndpoint(local) } }
                assertEquals(listOf(remote), done.servers)
                cancelAndIgnoreRemainingEvents()
            }
        }

    /**
     * In-memory [DataStore] of [Preferences] honouring the contract [ServerRepository] depends on —
     * mirrors the fake used in `:core:network`'s own `ServerRepositoryTest`, kept module-local. No file
     * I/O, deterministic on every OS.
     */
    private class FakePreferencesDataStore : DataStore<Preferences> {
        private val mutex = Mutex()
        private val state = MutableStateFlow(emptyPreferences())

        override val data: Flow<Preferences> = state.asStateFlow()

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            mutex.withLock {
                val updated = transform(state.value)
                state.value = updated
                updated
            }
    }
}
