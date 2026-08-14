package magefree.feature.game.board

import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import magefree.network.fake.FakeGameClient
import magefree.network.game.GameActionFailure
import magefree.network.game.GameCard
import magefree.network.game.GamePlayer
import magefree.network.game.GameState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Hermetic coverage of [GameBoardViewModel] over 0052's [FakeGameClient] — no bridge, no `:protocol`.
 *
 * The two properties that are about *production behaviour* rather than about projection are:
 *
 * 1. **the subscription is opened before the join** — the push side-channel is replay-less, so a client
 *    that joins first can miss `GAME_INIT` entirely (0052's live test records the same ordering);
 * 2. **nothing but `joinGame` is ever called** — this is a read-only board, and the surest way to keep
 *    it read-only is for the ViewModel to have no other verb in it at all. The assertion is on the
 *    fake's recorded call list, so a stray action would have to show up there to ship.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GameBoardViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `opens the subscription before joining, so the first snapshot cannot be missed`() =
        runTest {
            val client = FakeGameClient()

            viewModel(client).observe(GAME_ID)

            // `observeGame` is not itself recorded, so the proof is the *seed*: the fake emits it as the
            // flow opens, exactly as production does. If it has been seen, the collection was already
            // running when the join went out.
            assertEquals(listOf("join:$GAME_ID"), client.calls)
        }

    @Test
    fun `renders the seed first, as an honest nothing-has-arrived-yet board`() =
        runTest {
            val viewModel = viewModel(FakeGameClient())

            viewModel.uiState.test {
                viewModel.observe(GAME_ID)
                // The starting value, then the seed-derived board.
                skipItems(1)
                val seeded = awaitItem()

                assertEquals(GAME_ID, seeded.board.gameId)
                assertFalse("the seed is not a snapshot", seeded.board.hasSnapshot)
                assertTrue(seeded.board.hand.isEmpty)
                assertTrue(seeded.board.stack.isEmpty)
                assertNull(seeded.board.viewerSeat)
                assertEquals(PriorityUi.NotStarted, seeded.board.priority)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `projects each pushed snapshot onto the board`() =
        runTest {
            val client = FakeGameClient()
            val viewModel = viewModel(client)
            viewModel.observe(GAME_ID)

            client.emitGameState(dealtState())

            val board = viewModel.uiState.value.board
            assertTrue(board.hasSnapshot)
            assertEquals(listOf("Forest", "Forest"), board.hand.cards.map { it.card.name })
            assertEquals("you", board.viewerSeat?.name)
            assertEquals(listOf("Computer"), board.opponentSeats.map { it.name })
        }

    @Test
    fun `a later snapshot replaces the previous one rather than merging with it`() =
        runTest {
            // State arrives as whole snapshots, never deltas (0052), so a card the server stops sending
            // must disappear. A board that merged would keep showing a permanent that has been destroyed.
            val client = FakeGameClient()
            val viewModel = viewModel(client)
            viewModel.observe(GAME_ID)

            client.emitGameState(dealtState())
            client.emitGameState(dealtState().copy(hand = emptyList()))

            assertTrue("the hand the earlier snapshot carried must be gone", viewModel.uiState.value.board.hand.isEmpty)
        }

    @Test
    fun `surfaces the server's own reason when the join is declined`() =
        runTest {
            val client = FakeGameClient(actionResult = Result.failure(GameActionFailure("you are not in this match")))

            val viewModel = viewModel(client)
            viewModel.observe(GAME_ID)

            assertEquals("you are not in this match", viewModel.uiState.value.joinError)
            assertFalse(viewModel.uiState.value.isJoining)
        }

    @Test
    fun `clears the joining flag once the server accepts`() =
        runTest {
            val viewModel = viewModel(FakeGameClient())

            viewModel.observe(GAME_ID)

            assertFalse(viewModel.uiState.value.isJoining)
            assertNull(viewModel.uiState.value.joinError)
        }

    @Test
    fun `observing twice does not open a second subscription or re-join`() =
        runTest {
            val client = FakeGameClient()
            val viewModel = viewModel(client)

            viewModel.observe(GAME_ID)
            viewModel.observe(GAME_ID)

            assertEquals("a recomposition must not re-join the game", listOf("join:$GAME_ID"), client.calls)
        }

    @Test
    fun `expanding the hand is a view change and sends the server nothing`() =
        runTest {
            val client = FakeGameClient()
            val viewModel = viewModel(client)
            viewModel.observe(GAME_ID)

            viewModel.setHandExpanded(true)
            assertTrue(viewModel.uiState.value.isHandExpanded)
            viewModel.setHandExpanded(false)
            assertFalse(viewModel.uiState.value.isHandExpanded)

            assertEquals("looking at your own hand is not a game action", listOf("join:$GAME_ID"), client.calls)
        }

    @Test
    fun `never calls a game verb other than join, however the game moves`() =
        runTest {
            // The read-only guarantee, pinned where it is cheapest to keep: the ViewModel is the only
            // thing on the board holding a GameClient, so if it never calls an action, nothing can.
            val client = FakeGameClient()
            val viewModel = viewModel(client)
            viewModel.observe(GAME_ID)

            client.emitGameState(dealtState())
            client.emitGameState(dealtState().copy(viewerHasPriority = true))
            viewModel.setHandExpanded(true)

            assertEquals(listOf("join:$GAME_ID"), client.calls)
        }

    // ---- fixtures ----------------------------------------------------------------------------------

    private fun viewModel(client: FakeGameClient) = GameBoardViewModel(client)

    private fun forest(id: String) = GameCard(id = id, name = "Forest", setCode = "M21", collectorNumber = "272")

    private fun dealtState() =
        GameState(
            gameId = GAME_ID,
            turn = 1,
            hasSnapshot = true,
            viewerPlayerId = "p-you",
            activePlayerId = "p-you",
            players =
                listOf(
                    GamePlayer(playerId = "p-opp", name = "Computer", life = 20, isHuman = false),
                    GamePlayer(playerId = "p-you", name = "you", life = 20, isViewer = true),
                ),
            hand = listOf(forest("h-1"), forest("h-2")),
        )

    private companion object {
        const val GAME_ID = "g-1"
    }
}
