package magefree.feature.cards

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import magefree.cards.model.CardColor
import magefree.cards.model.Rarity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Hermetic Turbine/coroutines-test coverage of [CardSearchViewModel] over a [FakeCardCatalog] — no
 * bridge, no device. Pins the debounced search, filter application, the idle/empty/results surfaces,
 * and the offline-art (null art request) mapping.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CardSearchViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val bolt = testCard(1, "Lightning Bolt", manaCost = "{R}", colors = setOf(CardColor.RED), cardTypes = listOf("Instant"))
    private val bear = testCard(2, "Grizzly Bears", manaCost = "{1}{G}", colors = setOf(CardColor.GREEN))

    @Test
    fun startsIdleWithNoQueryOrFilters() =
        runTest {
            val catalog = FakeCardCatalog()
            val vm = CardSearchViewModel(catalog)
            advanceUntilIdle()

            assertEquals(CardSearchPhase.Idle, vm.uiState.value.phase)
            assertTrue(catalog.searchQueries.isEmpty())
            assertTrue(catalog.filterCriteria.isEmpty())
        }

    @Test
    fun debouncesRapidTypingIntoASingleSearch() =
        runTest {
            val catalog = FakeCardCatalog(searchResult = { listOf(bolt) })
            val vm = CardSearchViewModel(catalog, debounceMillis = 300L)
            advanceUntilIdle()

            vm.onQueryChange("l")
            vm.onQueryChange("li")
            vm.onQueryChange("light")
            advanceTimeBy(150L)
            runCurrent()
            // Still inside the debounce window: no catalog query issued yet.
            assertTrue("no search before debounce elapses", catalog.searchQueries.isEmpty())

            advanceUntilIdle()
            // Exactly one search, for the final text only.
            assertEquals(listOf("light"), catalog.searchQueries)
            val state = vm.uiState.value
            assertEquals(CardSearchPhase.Results, state.phase)
            assertEquals(listOf("Lightning Bolt"), state.results.map { it.display.name })
            assertEquals("Showing 1", state.countSummary)
        }

    @Test
    fun blankQueryWithNoFiltersReturnsToIdleInstantly() =
        runTest {
            val catalog = FakeCardCatalog(searchResult = { listOf(bolt) })
            val vm = CardSearchViewModel(catalog, debounceMillis = 300L)
            advanceUntilIdle()

            vm.onQueryChange("light")
            advanceUntilIdle()
            assertEquals(CardSearchPhase.Results, vm.uiState.value.phase)

            vm.clearQuery()
            advanceUntilIdle()
            assertEquals(CardSearchPhase.Idle, vm.uiState.value.phase)
        }

    @Test
    fun emptyResultMapsToEmptyPhase() =
        runTest {
            val catalog = FakeCardCatalog(searchResult = { emptyList() })
            val vm = CardSearchViewModel(catalog, debounceMillis = 0L)
            advanceUntilIdle()

            vm.onQueryChange("zzzz")
            advanceUntilIdle()
            assertEquals(CardSearchPhase.Empty, vm.uiState.value.phase)
            assertEquals("Showing 0", vm.uiState.value.countSummary)
        }

    @Test
    fun activeFilterUsesCatalogFilterWithMappedCriteria() =
        runTest {
            val catalog = FakeCardCatalog(filterResult = { listOf(bolt) })
            val vm = CardSearchViewModel(catalog, debounceMillis = 0L)
            advanceUntilIdle()

            vm.toggleColor(CardColor.RED)
            vm.setCardType("Instant")
            vm.setManaValue(6)
            vm.setRarity(Rarity.RARE)
            advanceUntilIdle()

            assertTrue(vm.uiState.value.hasActiveFilters)
            assertEquals(CardSearchPhase.Results, vm.uiState.value.phase)
            val criteria = catalog.filterCriteria.last()
            assertEquals(setOf(CardColor.RED), criteria.colors)
            assertEquals("Instant", criteria.cardType)
            assertEquals(6..Int.MAX_VALUE, criteria.manaValue)
            assertEquals(Rarity.RARE, criteria.rarity)
            // Name search is not issued while filtering.
            assertTrue(catalog.searchQueries.isEmpty())
        }

    @Test
    fun resetFiltersReturnsToIdleWhenQueryBlank() =
        runTest {
            val catalog = FakeCardCatalog(filterResult = { listOf(bear) })
            val vm = CardSearchViewModel(catalog, debounceMillis = 0L)
            advanceUntilIdle()

            vm.toggleColor(CardColor.GREEN)
            advanceUntilIdle()
            assertEquals(CardSearchPhase.Results, vm.uiState.value.phase)

            vm.resetFilters()
            advanceUntilIdle()
            assertEquals(CardSearchPhase.Idle, vm.uiState.value.phase)
            assertTrue(!vm.uiState.value.hasActiveFilters)
        }

    @Test
    fun cardWithoutPrintingsHasNullArtRequestForOfflinePlaceholder() =
        runTest {
            val artless = testCard(9, "Artless Token", printings = emptyList())
            val catalog = FakeCardCatalog(searchResult = { listOf(artless) })
            val vm = CardSearchViewModel(catalog, debounceMillis = 0L)
            advanceUntilIdle()

            vm.onQueryChange("artless")
            advanceUntilIdle()

            val results = vm.uiState.value
            assertEquals(CardSearchPhase.Results, results.phase)
            assertEquals(1, results.results.size)
            assertNull("no printing -> null art request -> design-system placeholder", results.results.first().artRequest)
        }
}
