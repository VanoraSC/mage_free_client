package magefree.feature.tables.join

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
import magefree.decks.model.Deck
import magefree.decks.model.DeckFormat
import magefree.decks.model.DeckId
import magefree.decks.model.DeckSummary
import magefree.feature.tables.FakeDeckLegality
import magefree.feature.tables.FakeDeckRepository
import magefree.feature.tables.legal
import magefree.network.fake.FakeTableClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * The join deck-pick must fail **soft** when the offline legality check fails.
 *
 * `selectDeck` runs inside a bare `viewModelScope.launch` and `DeckLegality.check` reads the bundled
 * card catalog, whose first use copies a ~14 MB asset into `filesDir`. `viewModelScope`'s
 * `SupervisorJob` has no `CoroutineExceptionHandler`, so before the fix an `IOException` from that
 * copy crashed the process from the deck picker.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class JoinTableCatalogFailureTest {
    private val dispatcher = UnconfinedTestDispatcher()

    private val modernDeck = Deck(id = DeckId("deck-1"), name = "Mono-Red", format = DeckFormat.MODERN)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun storageFailure(): () -> Throwable = { IOException("No space left on device") }

    private fun target() =
        JoinTarget(
            tableId = "t-1",
            tableName = "Friday duel",
            gameType = "Two Player Duel",
            deckType = "Constructed - Modern",
            passworded = false,
        )

    @Test
    fun aFailingLegalityCheckSurfacesAsStateAndDoesNotEnableJoin() =
        runTest {
            val legality = FakeDeckLegality { legal(it) }.apply { failWith = storageFailure() }
            val vm = JoinTableViewModel(FakeTableClient(), FakeDeckRepository(listOf(modernDeck)), legality)
            vm.start(target())

            vm.selectDeck(DeckId("deck-1"))
            advanceUntilIdle()

            val state = vm.uiState.value
            val detail = state.errorMessage
            assertNotNull(detail)
            assertTrue(detail!!, detail.contains("No space left on device"))
            assertNull("an unverified deck must not carry a legality verdict", state.legality)
            assertFalse("an unverified deck must not be joinable", state.canJoin)
        }

    @Test
    fun aLaterPickStillWorksAfterAFailedOne() =
        runTest {
            val legality = FakeDeckLegality { legal(it) }.apply { failWith = storageFailure() }
            val vm = JoinTableViewModel(FakeTableClient(), FakeDeckRepository(listOf(modernDeck)), legality)
            vm.start(target())

            vm.selectDeck(DeckId("deck-1"))
            advanceUntilIdle()
            assertNotNull(vm.uiState.value.errorMessage)

            // Storage freed: the ViewModel is still alive and the next pick is checked normally.
            legality.failWith = null
            vm.selectDeck(DeckId("deck-1"))
            advanceUntilIdle()

            val state = vm.uiState.value
            assertNull(state.errorMessage)
            assertEquals(true, state.legality?.isLegal)
            assertTrue(state.canJoin)
            assertEquals(listOf(DeckFormat.MODERN), legality.checkedFormats)
        }

    @Test
    fun aFailingDeckLibraryFlowSurfacesAsStateNotACrash() =
        runTest {
            val vm = JoinTableViewModel(FakeTableClient(), ThrowingLibraryRepository(), FakeDeckLegality { legal(it) })
            vm.start(target())
            advanceUntilIdle()

            val state = vm.uiState.value
            assertNotNull(state.errorMessage)
            assertTrue(state.hasNoDecks)
            assertFalse(state.canJoin)
        }

    /** A repository whose library flow always fails. */
    private class ThrowingLibraryRepository : DeckRepository {
        override fun observeLibrary(): Flow<List<DeckSummary>> = flow { throw IOException("database is locked") }

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
