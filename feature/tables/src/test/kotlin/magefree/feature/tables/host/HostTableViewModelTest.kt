package magefree.feature.tables.host

import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import magefree.network.fake.FakeTableClient
import magefree.network.table.SeatPlayerType
import magefree.network.table.TableActionFailure
import magefree.network.table.TableRef
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Hermetic coverage of [HostTableViewModel] over 0037's scriptable [FakeTableClient] — no bridge, no
 * `:protocol`. Pins the create-table form's validation, its projection onto `CreateTableOptions`, and the
 * success-seeds-the-room / decline-surfaces contract.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HostTableViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(client: FakeTableClient = FakeTableClient()) = HostTableViewModel(client)

    @Test
    fun defaultFormIsValidAndCanCreate() {
        val vm = viewModel()
        assertTrue(vm.uiState.value.canCreate)
        assertTrue(vm.uiState.value.form.isValid)
    }

    @Test
    fun blankNameBlocksCreate() {
        val vm = viewModel()
        vm.setName("   ")
        assertFalse(vm.uiState.value.canCreate)
    }

    @Test
    fun formProjectsOntoCreateTableOptions() {
        val vm = viewModel()
        vm.setName("  Friday duel  ")
        vm.setGameType("Commander Free For All")
        vm.setSeats(4)
        vm.setRated(true)
        vm.setFreeMulligans(2)
        vm.setPassword("secret")

        val options =
            vm.uiState.value.form
                .toOptions()
        assertEquals("Friday duel", options.name)
        assertEquals("Commander Free For All", options.gameType)
        assertEquals(4, options.players.size)
        assertTrue(options.players.all { it == SeatPlayerType.Human })
        assertTrue(options.rated)
        assertEquals(2, options.freeMulligans)
        assertEquals("secret", options.password)
    }

    @Test
    fun blankPasswordMapsToNull() {
        val options =
            viewModel()
                .uiState.value.form
                .toOptions()
        assertNull(options.password)
    }

    @Test
    fun seatsAreClampedToAtLeastTwo() {
        val vm = viewModel()
        vm.setSeats(1)
        assertEquals(2, vm.uiState.value.form.seats)
    }

    @Test
    fun createSuccessCallsClientAndSeedsTheRoom() =
        runTest {
            val ref = TableRef("t-99", "Friday duel", "Two Player Duel", "Constructed", 1, 2)
            val client = FakeTableClient(createResult = Result.success(ref))
            val vm = viewModel(client)

            vm.created.test {
                vm.create()
                assertEquals(ref, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
            assertEquals(listOf("create"), client.calls)
            assertFalse(vm.uiState.value.isSubmitting)
        }

    @Test
    fun createDeclineSurfacesTheReason() {
        val client = FakeTableClient(createResult = Result.failure(TableActionFailure("table name taken")))
        val vm = viewModel(client)

        vm.create()

        assertEquals("table name taken", vm.uiState.value.errorMessage)
        assertFalse(vm.uiState.value.isSubmitting)
        assertEquals(listOf("create"), client.calls)
    }
}
