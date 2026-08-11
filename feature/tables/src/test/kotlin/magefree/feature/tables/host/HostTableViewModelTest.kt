package magefree.feature.tables.host

import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import magefree.decks.model.Deck
import magefree.decks.model.DeckEntry
import magefree.decks.model.DeckFormat
import magefree.decks.model.DeckId
import magefree.feature.tables.FakeDeckLegality
import magefree.feature.tables.FakeDeckRepository
import magefree.feature.tables.illegal
import magefree.feature.tables.legal
import magefree.network.fake.FakeTableClient
import magefree.network.table.SeatPlayerType
import magefree.network.table.SessionGoneFailure
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
 * Hermetic coverage of [HostTableViewModel] over 0037's scriptable [FakeTableClient] and the in-memory
 * deck repository/legality doubles — no bridge, no `:protocol`.
 *
 * Beyond the create-table form's validation and its projection onto `CreateTableOptions`, this pins the
 * story-0041 contract: hosting is a **sequence** — create, then each configured AI seat, then the host's
 * own seat with the chosen deck — and every failure in it removes the table and reports why, instead of
 * leaving a half-seated orphan in the lobby and walking into an unstartable room.
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

    private val standardDeck =
        Deck(
            id = DeckId("deck-1"),
            name = "Mono-Red Aggro",
            format = DeckFormat.STANDARD,
            main = listOf(DeckEntry("Mountain", "XLN", "273", 60)),
        )

    private fun viewModel(
        client: FakeTableClient = FakeTableClient(),
        decks: List<Deck> = listOf(standardDeck),
        legalityResult: (DeckFormat) -> magefree.decks.legality.DeckLegalityResult = ::legal,
    ) = HostTableViewModel(client, FakeDeckRepository(decks), FakeDeckLegality(legalityResult))

    /** A started ViewModel with the deck picked — the ordinary "ready to create" starting point. */
    private fun readyViewModel(client: FakeTableClient = FakeTableClient()): HostTableViewModel =
        viewModel(client).apply {
            start()
            selectDeck(DeckId("deck-1"))
        }

    // ---- the form ------------------------------------------------------------------------------

    @Test
    fun defaultFormIsValidButCreateNeedsAPickedDeck() {
        val vm = viewModel()
        vm.start()
        assertTrue(vm.uiState.value.form.isValid)
        // Story 0041: hosting takes a seat, and a seat needs a deck — so a valid form alone is not enough.
        assertFalse(vm.uiState.value.canCreate)

        vm.selectDeck(DeckId("deck-1"))
        assertTrue(vm.uiState.value.canCreate)
    }

    @Test
    fun blankNameBlocksCreate() {
        val vm = readyViewModel()
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
    fun configuredSeatTypesReachTheCreateOptionsInSeatOrder() {
        val vm = viewModel()
        vm.setSeats(3)
        vm.setOpponentType(0, SeatPlayerType.ComputerMad)

        val options =
            vm.uiState.value.form
                .toOptions()
        // Seat 1 is always the host's own human seat; the configured opponents follow it in order.
        assertEquals(
            listOf(SeatPlayerType.Human, SeatPlayerType.ComputerMad, SeatPlayerType.Human),
            options.players,
        )
    }

    @Test
    fun resizingTheTableKeepsTheSeatKindsAlreadyConfigured() {
        val vm = viewModel()
        vm.setSeats(3)
        vm.setOpponentType(0, SeatPlayerType.ComputerMonteCarlo)
        vm.setSeats(4)

        assertEquals(
            listOf(SeatPlayerType.ComputerMonteCarlo, SeatPlayerType.Human, SeatPlayerType.Human),
            vm.uiState.value.form.opponents,
        )
    }

    // ---- the offline deck gate -----------------------------------------------------------------

    @Test
    fun anIllegalDeckCannotCreateTheTable() =
        runTest {
            val client = FakeTableClient()
            val vm = viewModel(client, legalityResult = ::illegal)
            vm.start()
            vm.selectDeck(DeckId("deck-1"))

            assertFalse(vm.uiState.value.isSelectedLegal)
            assertFalse(vm.uiState.value.canCreate)

            vm.create()
            // The gate is offline: nothing was sent at all.
            assertTrue(client.calls.isEmpty())
        }

    @Test
    fun anEmptyLibraryOffersTheBuildADeckPath() {
        val vm = viewModel(decks = emptyList())
        vm.start()

        val state = vm.uiState.value
        assertTrue(state.hasNoDecks)
        assertFalse(state.canCreate)
        // With nothing to pick, there is no legality verdict to render either.
        assertNull(state.selectedDeckId)
        assertNull(state.legality)
    }

    @Test
    fun theTablesFormatIsResolvedOfflineAndRecheckedWhenTheDeckTypeChanges() {
        val legality = FakeDeckLegality(::legal)
        val vm = HostTableViewModel(FakeTableClient(), FakeDeckRepository(listOf(standardDeck)), legality)
        vm.start()
        vm.selectDeck(DeckId("deck-1"))
        assertEquals(DeckFormat.STANDARD, vm.uiState.value.format)

        vm.setDeckType("Constructed - Modern")

        assertEquals(DeckFormat.MODERN, vm.uiState.value.format)
        assertEquals(listOf(DeckFormat.STANDARD, DeckFormat.MODERN), legality.checkedFormats)
    }

    // ---- the ordered create sequence -----------------------------------------------------------

    @Test
    fun createSeatsTheAiPlayersBeforeTheHostAndOnlyThenOpensTheRoom() =
        runTest {
            val ref = TableRef("t-99", "Friday duel", "Two Player Duel", "Constructed", 0, 3)
            val client = FakeTableClient(createResult = Result.success(ref))
            val vm = readyViewModel(client)
            vm.setSeats(3)
            vm.setOpponentType(0, SeatPlayerType.ComputerMad)
            vm.setOpponentType(1, SeatPlayerType.ComputerMonteCarlo)
            vm.setSeatName("Pete")
            vm.setPassword("hunter2")

            vm.created.test {
                vm.create()
                assertEquals(ref, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }

            // Upstream's order: create, then every non-human seat, then our own — nothing else.
            assertEquals(listOf("create", "join:t-99", "join:t-99", "join:t-99"), client.calls)

            val (firstAi, secondAi, self) = Triple(client.joins[0], client.joins[1], client.joins[2])
            // The AI seats are joined *first*, each carrying its own type — upstream matches a join to a
            // seat by player type, so a HUMAN join could never fill an AI seat.
            assertEquals(SeatPlayerType.ComputerMad, firstAi.playerType)
            assertEquals(SeatPlayerType.ComputerMonteCarlo, secondAi.playerType)
            // The host's own join comes last, as a human, with the picked deck and the table's password.
            assertEquals(SeatPlayerType.Human, self.playerType)
            assertEquals("Pete", self.seatName)
            assertEquals(standardDeck, self.deck)
            assertEquals("hunter2", self.password)
            assertFalse(vm.uiState.value.isSubmitting)
            assertNull(vm.uiState.value.errorMessage)
        }

    @Test
    fun aHumanSeatIsLeftOpenForSomeoneElseToJoin() =
        runTest {
            val client = FakeTableClient()
            val vm = readyViewModel(client)
            // The default two-seat table's opponent is a human, so only the host's own join is issued.
            vm.create()

            assertEquals(listOf("create", "join:t-1"), client.calls)
            assertEquals(SeatPlayerType.Human, client.joins.single().playerType)
        }

    @Test
    fun theAiSeatIsSeededWithTheHostsOwnDeckAndNoPassword() =
        runTest {
            val client = FakeTableClient()
            val vm = readyViewModel(client)
            vm.setOpponentType(0, SeatPlayerType.ComputerMad)
            vm.setPassword("hunter2")

            vm.create()

            val ai = client.joins.first()
            // The documented choice: an AI seat must submit *a* deck, this client has no deck generator,
            // and the host's pick is already legality-checked for the table's format.
            assertEquals(standardDeck, ai.deck)
            // Upstream sends no password for an AI seat — the password guard is on the human join.
            assertNull(ai.password)
        }

    @Test
    fun anOpenTableSendsNoPasswordOnTheHostsJoin() =
        runTest {
            val client = FakeTableClient()
            val vm = readyViewModel(client)

            vm.create()

            assertNull(client.joins.single().password)
        }

    // ---- the failure paths ---------------------------------------------------------------------

    /**
     * Story 0050 defect A, second half — the smoke's actual sequence: XMage expires the idle session
     * while the host is deck-building, the create is answered `SESSION_GONE`, and the host used to be
     * told *"the server declined to create the table"*. That reads as a problem with the table, so the
     * host retries the one thing that cannot work. Say what happened and name the fix.
     */
    @Test
    fun aCreateThatFailsBecauseTheSessionIsGoneSaysSoAndPointsAtSigningInAgain() =
        runTest {
            val client =
                FakeTableClient(
                    createResult =
                        Result.failure(SessionGoneFailure("the server declined to create the table")),
                )
            val vm = readyViewModel(client)

            vm.assertNoRoomOpened { create() }

            assertEquals(
                "a lost session must not be reported as a server decline",
                "You're no longer signed in to the server. Sign in again to host a table.",
                vm.uiState.value.errorMessage,
            )
            assertFalse(vm.uiState.value.isSubmitting)
        }

    @Test
    fun createDeclineSurfacesTheReasonAndHasNothingToRemove() =
        runTest {
            val client = FakeTableClient(createResult = Result.failure(TableActionFailure("table name taken")))
            val vm = readyViewModel(client)

            vm.assertNoRoomOpened { create() }

            assertEquals("table name taken", vm.uiState.value.errorMessage)
            assertFalse(vm.uiState.value.isSubmitting)
            // No table exists, so no removal is attempted — and no seat was taken.
            assertEquals(listOf("create"), client.calls)
        }

    @Test
    fun aFailedAiJoinRemovesTheTableExactlyOnceAndDoesNotOpenTheRoom() =
        runTest {
            val client = FakeTableClient()
            client.joinResultFor = { call ->
                if (call.playerType == SeatPlayerType.Human) {
                    Result.success(Unit)
                } else {
                    Result.failure(TableActionFailure("no free seat for that player type"))
                }
            }
            val vm = readyViewModel(client)
            vm.setSeats(3)
            vm.setOpponentType(0, SeatPlayerType.ComputerMad)
            vm.setOpponentType(1, SeatPlayerType.ComputerMad)

            vm.assertNoRoomOpened { create() }

            // Abort on the *first* failing AI seat: no second AI join, and no host join either.
            assertEquals(listOf("create", "join:t-1", "remove:t-1"), client.calls)
            assertEquals(1, client.calls.count { it == "remove:t-1" })
            assertEquals("no free seat for that player type", vm.uiState.value.errorMessage)
            assertFalse(vm.uiState.value.isSubmitting)
        }

    @Test
    fun aFailedHostJoinRemovesTheTableExactlyOnceAndDoesNotOpenTheRoom() =
        runTest {
            val client = FakeTableClient()
            client.joinResultFor = { call ->
                if (call.playerType == SeatPlayerType.Human) {
                    Result.failure(TableActionFailure("wrong password"))
                } else {
                    Result.success(Unit)
                }
            }
            val vm = readyViewModel(client)
            vm.setOpponentType(0, SeatPlayerType.ComputerMad)

            vm.assertNoRoomOpened { create() }

            // The AI seat was taken, then our own join failed — the whole table goes.
            assertEquals(listOf("create", "join:t-1", "join:t-1", "remove:t-1"), client.calls)
            assertEquals(1, client.calls.count { it == "remove:t-1" })
            assertEquals("wrong password", vm.uiState.value.errorMessage)
            assertFalse(vm.uiState.value.isSubmitting)
        }

    @Test
    fun aFailedCleanupStillSurfacesTheOriginalReason() =
        runTest {
            val client =
                FakeTableClient(
                    joinResult = Result.failure(TableActionFailure("wrong password")),
                    removeResult = Result.failure(TableActionFailure("not the table owner")),
                )
            val vm = readyViewModel(client)

            vm.assertNoRoomOpened { create() }

            // The cleanup is best-effort: its own failure must not replace the reason the host needs.
            assertEquals("wrong password", vm.uiState.value.errorMessage)
            assertEquals(listOf("create", "join:t-1", "remove:t-1"), client.calls)
        }

    /**
     * Run [block] while collecting `created` and assert it announced **nothing** — the room must open only
     * on a fully seated table, so this is the assertion every failure-path test shares. The ViewModel runs
     * on an unconfined dispatcher, so the whole sequence has completed by the time [block] returns and a
     * missing emission is a real absence, not a race.
     */
    private suspend fun HostTableViewModel.assertNoRoomOpened(block: HostTableViewModel.() -> Unit) {
        created.test {
            block()
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }
}
