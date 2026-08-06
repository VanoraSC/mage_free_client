package magefree.feature.decks.library

import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import magefree.decks.io.DeckImportResult
import magefree.decks.model.Deck
import magefree.decks.model.DeckEntry
import magefree.decks.model.DeckFormat
import magefree.decks.model.DeckId
import magefree.feature.decks.FakeDeckIO
import magefree.feature.decks.FakeDeckRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Hermetic Turbine coverage of [LibraryViewModel] over in-memory fakes — no Room, no device, no
 * network. Pins the offline library CRUD/favorite/import contract and the open-deck signal.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val emptyImport = DeckImportResult(deck = Deck(id = DeckId(""), name = ""))

    private fun viewModel(
        repository: FakeDeckRepository = FakeDeckRepository(),
        deckIO: FakeDeckIO = FakeDeckIO(emptyImport),
    ) = LibraryViewModel(repository, deckIO)

    @Test
    fun startsEmptyThenShowsContentAsDecksArrive() =
        runTest {
            val repo = FakeDeckRepository()
            val vm = viewModel(repository = repo)

            vm.uiState.test {
                assertEquals(LibraryPhase.Empty, awaitItem().phase)
                vm.createDeck("Aggro", DeckFormat.STANDARD)
                val content = awaitItem()
                assertEquals(LibraryPhase.Content, content.phase)
                assertEquals(listOf("Aggro"), content.decks.map { it.name })
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun createDeckSignalsOpenAndPersists() =
        runTest {
            val repo = FakeDeckRepository()
            val vm = viewModel(repository = repo)

            vm.openDeck.test {
                vm.createDeck("Control", DeckFormat.MODERN)
                val id = awaitItem()
                assertEquals("deck-1", id.value)
                cancelAndIgnoreRemainingEvents()
            }
            val saved = repo.snapshot().single()
            assertEquals("Control", saved.name)
            assertEquals(DeckFormat.MODERN, saved.format)
        }

    @Test
    fun favoriteRenameDuplicateDeleteMutateTheLibrary() =
        runTest {
            val repo = FakeDeckRepository(listOf(Deck(id = DeckId("deck-1"), name = "Deck A")))
            val vm = viewModel(repository = repo)

            vm.setFavorite(DeckId("deck-1"), true)
            assertTrue(repo.snapshot().single { it.id.value == "deck-1" }.favorite)

            vm.renameDeck(DeckId("deck-1"), "Renamed")
            assertEquals("Renamed", repo.snapshot().single { it.id.value == "deck-1" }.name)

            vm.duplicateDeck(DeckId("deck-1"))
            assertEquals(2, repo.snapshot().size)

            vm.deleteDeck(DeckId("deck-1"))
            assertTrue(repo.snapshot().none { it.id.value == "deck-1" })
        }

    @Test
    fun importAddsResolvedDeckToLibraryAndSignalsOpen() =
        runTest {
            val importedDeck =
                Deck(
                    id = DeckId(""),
                    name = "Imported Burn",
                    main = listOf(DeckEntry("Lightning Bolt", "2ED", "161", 4)),
                )
            val deckIO = FakeDeckIO(DeckImportResult(deck = importedDeck))
            val repo = FakeDeckRepository()
            val vm = viewModel(repository = repo, deckIO = deckIO)

            vm.openDeck.test {
                vm.importDeck("4 Lightning Bolt")
                val id = awaitItem()
                assertNotNull(id)
                cancelAndIgnoreRemainingEvents()
            }

            assertEquals(1, deckIO.importCalls)
            val saved = repo.snapshot().single()
            assertEquals("Imported Burn", saved.name)
            assertEquals(4, saved.mainCount)
            assertNotNull(vm.uiState.value.lastImport)
        }
}
