package magefree.feature.decks.library

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import magefree.decks.DeckRepository
import magefree.decks.io.DeckImportResult
import magefree.decks.model.Deck
import magefree.decks.model.DeckEntry
import magefree.decks.model.DeckFormat
import magefree.decks.model.DeckId
import magefree.decks.model.DeckSummary
import magefree.feature.decks.FakeDeckIO
import magefree.feature.decks.FakeDeckRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * The deck library must fail **soft** when the offline reads behind it fail (story 0042, defect B).
 *
 * Import resolves every line against the bundled catalog, and it runs inside a bare
 * `viewModelScope.launch`; the library list is a flow `launchIn(viewModelScope)`. `viewModelScope`'s
 * `SupervisorJob` has no `CoroutineExceptionHandler`, so before the fix either throw reached the
 * default handler and crashed the process.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryCatalogFailureTest {
    private val dispatcher = UnconfinedTestDispatcher()

    private val emptyImport = DeckImportResult(deck = Deck(id = DeckId(""), name = ""))

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun storageFailure(): () -> Throwable = { IOException("No space left on device") }

    @Test
    fun anImportWhoseCatalogReadFailsSurfacesAsStateNotACrash() =
        runTest {
            val repo = FakeDeckRepository()
            val deckIO = FakeDeckIO(emptyImport, failWith = storageFailure())
            val vm = LibraryViewModel(repo, deckIO)
            advanceUntilIdle()

            vm.importDeck("4 Lightning Bolt")
            advanceUntilIdle()

            val state = vm.uiState.value
            val detail = state.errorMessage
            assertNotNull(detail)
            assertTrue(detail!!, detail.contains("No space left on device"))
            // Nothing half-imported was persisted.
            assertTrue(repo.snapshot().isEmpty())
            assertNull(state.lastImport)
        }

    @Test
    fun aLaterImportStillWorksAfterAFailedOne() =
        runTest {
            val imported =
                Deck(id = DeckId(""), name = "Imported Burn", main = listOf(DeckEntry("Lightning Bolt", "2ED", "161", 4)))
            val repo = FakeDeckRepository()
            val deckIO = FakeDeckIO(DeckImportResult(deck = imported), failWith = storageFailure())
            val vm = LibraryViewModel(repo, deckIO)
            advanceUntilIdle()

            vm.importDeck("4 Lightning Bolt")
            advanceUntilIdle()
            assertNotNull(vm.uiState.value.errorMessage)

            // Storage freed: the ViewModel is still alive and the next import goes through.
            deckIO.failWith = null
            vm.importDeck("4 Lightning Bolt")
            advanceUntilIdle()

            val state = vm.uiState.value
            assertNull(state.errorMessage)
            assertNotNull(state.lastImport)
            assertEquals("Imported Burn", repo.snapshot().single().name)
            assertEquals(LibraryPhase.Content, state.phase)
        }

    @Test
    fun otherLibraryOperationsStillWorkAfterAFailedImport() =
        runTest {
            val repo = FakeDeckRepository()
            val vm = LibraryViewModel(repo, FakeDeckIO(emptyImport, failWith = storageFailure()))
            advanceUntilIdle()

            vm.importDeck("garbage")
            advanceUntilIdle()
            assertNotNull(vm.uiState.value.errorMessage)

            vm.createDeck("Aggro", DeckFormat.STANDARD)
            advanceUntilIdle()
            assertEquals(
                listOf("Aggro"),
                vm.uiState.value.decks
                    .map { it.name },
            )

            vm.clearError()
            assertNull(vm.uiState.value.errorMessage)
        }

    @Test
    fun aFailingLibraryFlowBecomesAnErrorPhaseAndRetryRecovers() =
        runTest {
            val repo = FlakyLibraryRepository()
            val vm = LibraryViewModel(repo, FakeDeckIO(emptyImport))
            advanceUntilIdle()

            assertEquals(LibraryPhase.Error, vm.uiState.value.phase)
            assertNotNull(vm.uiState.value.errorMessage)

            // The retry re-subscribes; the second subscription succeeds.
            vm.retry()
            advanceUntilIdle()

            assertEquals(LibraryPhase.Content, vm.uiState.value.phase)
            assertEquals(
                listOf("Recovered"),
                vm.uiState.value.decks
                    .map { it.name },
            )
            assertNull(vm.uiState.value.errorMessage)
        }

    /** Throws on the first subscription to [observeLibrary], succeeds on every later one. */
    private class FlakyLibraryRepository : DeckRepository {
        private var subscriptions = 0

        override fun observeLibrary(): Flow<List<DeckSummary>> =
            flow {
                if (++subscriptions == 1) throw IOException("database is locked")
                emit(
                    listOf(
                        DeckSummary(
                            id = DeckId("deck-1"),
                            name = "Recovered",
                            author = null,
                            format = null,
                            favorite = false,
                            mainCount = 0,
                            sideboardCount = 0,
                            createdAt = 0L,
                            updatedAt = 0L,
                        ),
                    ),
                )
            }

        override suspend fun load(id: DeckId): Deck? = null

        override suspend fun create(
            name: String,
            format: DeckFormat?,
        ): Deck = Deck(id = DeckId("deck-1"), name = name, format = format)

        override suspend fun save(deck: Deck) = Unit

        override suspend fun duplicate(id: DeckId): Deck? = null

        override suspend fun rename(
            id: DeckId,
            name: String,
        ) = Unit

        override suspend fun delete(id: DeckId) = Unit

        override suspend fun setFavorite(
            id: DeckId,
            favorite: Boolean,
        ) = Unit
    }
}
