package magefree.network.table

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import magefree.decks.model.Deck
import magefree.decks.model.DeckEntry
import magefree.decks.model.DeckId
import magefree.model.ConnectionState
import magefree.network.fake.FakeBridgeClient
import magefree.protocol.ClientMessage
import magefree.protocol.CreateTable
import magefree.protocol.GetTable
import magefree.protocol.JoinTable
import magefree.protocol.LeaveTable
import magefree.protocol.RemoveTable
import magefree.protocol.SeatPlayerTypeCode
import magefree.protocol.ServerMessage
import magefree.protocol.SkillLevelCode
import magefree.protocol.StartMatch
import magefree.protocol.SubmitDeck
import magefree.protocol.TableActionCode
import magefree.protocol.TableActionResult
import magefree.protocol.TableCreated
import magefree.protocol.TableDetail
import magefree.protocol.TableNotFound
import magefree.protocol.TableSeatSummary
import magefree.protocol.TableStateCode
import magefree.protocol.TableSummary
import magefree.protocol.UpdateDeck
import magefree.protocol.WatchTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hermetic coverage of the production [DefaultTableClient]: each verb mints a `requestId`, sends the
 * matching 0036 request over the [FakeBridgeClient]'s request/response seam, and maps the correlated
 * reply into a [Result] — a `TableCreated` → [TableRef], a `TableActionResult(ok = true)` → success, and
 * an `ok = false` → a typed [TableActionFailure] surfacing the reason. No socket, no `:protocol` on the
 * exercised ABI.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TableClientTest {
    private val deck =
        Deck(
            id = DeckId("d-1"),
            name = "Mono Blue",
            author = "pete",
            main = listOf(DeckEntry("Island", "XLN", "265", 20)),
        )

    private fun client(
        sent: MutableList<ClientMessage> = mutableListOf(),
        reply: (ClientMessage) -> ServerMessage,
    ): DefaultTableClient {
        val fake =
            FakeBridgeClient(
                responder = { message ->
                    sent += message
                    reply(message)
                },
            )
        return DefaultTableClient(
            bridgeClient = fake,
            pushSource = fake,
            connectionState = MutableStateFlow(ConnectionState.Connected),
            newRequestId = { "req-fixed" },
        )
    }

    private fun summary() =
        TableSummary(
            tableId = "t-1",
            name = "Pete's Table",
            controllerName = "pete",
            gameType = "Two Player Duel",
            deckType = "Constructed",
            state = TableStateCode.WAITING,
            seatsFilled = 1,
            seatsTotal = 2,
            isTournament = false,
            isRated = false,
            isPassworded = false,
            isLimited = false,
            skillLevel = SkillLevelCode.CASUAL,
            createdAtEpochMs = 0L,
        )

    @Test
    fun createTableSendsCreateTableWithMappedOptionsAndReturnsTheRef() =
        runTest {
            val sent = mutableListOf<ClientMessage>()
            val client = client(sent) { TableCreated(table = summary(), requestId = "req-fixed") }

            val result =
                client.createTable(
                    CreateTableOptions(
                        name = "Pete's Table",
                        gameType = "Two Player Duel",
                        deckType = "Constructed",
                        players = listOf(SeatPlayerType.Human, SeatPlayerType.ComputerMad),
                    ),
                )

            val request = sent.single() as CreateTable
            assertEquals("req-fixed", request.requestId)
            assertEquals(listOf(SeatPlayerTypeCode.HUMAN, SeatPlayerTypeCode.COMPUTER_MAD), request.options.players)
            assertTrue(result.isSuccess)
            assertEquals("t-1", result.getOrThrow().tableId)
            assertEquals(2, result.getOrThrow().seatsTotal)
        }

    @Test
    fun createTableDeclineSurfacesTheReasonAsATypedFailure() =
        runTest {
            val client = client { TableActionResult(action = TableActionCode.CREATE, ok = false, reason = "room full") }

            val result = client.createTable(CreateTableOptions("n", "g", "d"))

            assertTrue(result.isFailure)
            val failure = result.exceptionOrNull()
            assertTrue(failure is TableActionFailure)
            assertEquals("room full", (failure as TableActionFailure).reason)
        }

    @Test
    fun joinTableSendsJoinWithTheMappedDeckAndReturnsSuccess() =
        runTest {
            val sent = mutableListOf<ClientMessage>()
            val client = client(sent) { TableActionResult(action = TableActionCode.JOIN, ok = true) }

            val result = client.joinTable(tableId = "t-1", seatName = "pete", deck = deck, password = "pw")

            val request = sent.single() as JoinTable
            assertEquals("t-1", request.tableId)
            assertEquals("pete", request.seatName)
            assertEquals("pw", request.password)
            assertEquals("req-fixed", request.requestId)
            // The domain deck was mapped onto the wire DeckList.
            assertEquals("Mono Blue", request.deck.name)
            assertEquals(
                "Island",
                request.deck.cards
                    .single()
                    .cardName,
            )
            assertEquals(
                20,
                request.deck.cards
                    .single()
                    .amount,
            )
            assertTrue(result.isSuccess)
        }

    @Test
    fun submitAndUpdateDeckSendTheRightRequests() =
        runTest {
            val sent = mutableListOf<ClientMessage>()
            val submit = client(sent) { TableActionResult(action = TableActionCode.SUBMIT_DECK, ok = true) }
            assertTrue(submit.submitDeck("t-1", deck).isSuccess)
            assertTrue(sent.single() is SubmitDeck)

            val sent2 = mutableListOf<ClientMessage>()
            val update = client(sent2) { TableActionResult(action = TableActionCode.UPDATE_DECK, ok = true) }
            assertTrue(update.updateDeck("t-1", deck).isSuccess)
            assertTrue(sent2.single() is UpdateDeck)
        }

    @Test
    fun leaveRemoveStartWatchSendTheRightRequestsAndSucceed() =
        runTest {
            val sent = mutableListOf<ClientMessage>()
            val client = client(sent) { TableActionResult(action = TableActionCode.LEAVE, ok = true) }

            assertTrue(client.leaveTable("t-1").isSuccess)
            assertTrue(client.removeTable("t-1").isSuccess)
            assertTrue(client.startMatch("t-1").isSuccess)
            assertTrue(client.watchTable("t-1").isSuccess)

            assertTrue(sent[0] is LeaveTable)
            assertTrue(sent[1] is RemoveTable)
            assertTrue(sent[2] is StartMatch)
            assertTrue(sent[3] is WatchTable)
        }

    @Test
    fun aDeclinedActionSurfacesTheReasonNeverSilentlyDropped() =
        runTest {
            val client =
                client { TableActionResult(action = TableActionCode.START_MATCH, ok = false, reason = "seats empty") }

            val result = client.startMatch("t-1")

            assertFalse(result.isSuccess)
            assertEquals("seats empty", (result.exceptionOrNull() as TableActionFailure).reason)
        }

    @Test
    fun refreshTableSendsGetTableAndMapsTheDetailOntoAppSchemaSeats() =
        runTest {
            val sent = mutableListOf<ClientMessage>()
            val client =
                client(sent) {
                    TableDetail(
                        table = summary().copy(state = TableStateCode.READY_TO_START, seatsFilled = 2),
                        seats =
                            listOf(
                                TableSeatSummary(index = 0, playerName = "pete", playerType = SeatPlayerTypeCode.HUMAN, occupied = true),
                                TableSeatSummary(
                                    index = 1,
                                    playerName = "Computer",
                                    playerType = SeatPlayerTypeCode.COMPUTER_MAD,
                                    occupied = true,
                                ),
                            ),
                        requestId = "req-fixed",
                    )
                }

            val result = client.refreshTable("t-1")

            val request = sent.single() as GetTable
            assertEquals("t-1", request.tableId)
            assertEquals("req-fixed", request.requestId)

            val details = result.getOrThrow()
            assertEquals("t-1", details.tableId)
            assertEquals(TableServerState.ReadyToStart, details.serverState)
            assertEquals(
                listOf(
                    Seat(index = 0, name = "pete", playerType = SeatPlayerType.Human, isOccupied = true),
                    Seat(index = 1, name = "Computer", playerType = SeatPlayerType.ComputerMad, isOccupied = true),
                ),
                details.seats,
            )
        }

    @Test
    fun refreshTableSurfacesAMissingTableAsATypedNotFound() =
        runTest {
            val client = client { TableNotFound(tableId = "t-1", reason = "no such table in the room") }

            val result = client.refreshTable("t-1")

            assertFalse(result.isSuccess)
            val failure = result.exceptionOrNull()
            assertTrue("expected a TableNotFoundFailure, got $failure", failure is TableNotFoundFailure)
            assertEquals("t-1", (failure as TableNotFoundFailure).tableId)
            assertEquals("no such table in the room", failure.reason)
        }

    @Test
    fun aTransportThrowIsCapturedAsAFailedResult() =
        runTest {
            // No responder scripted → the fake throws; the client must capture it as a failed Result.
            val fake = FakeBridgeClient()
            val client =
                DefaultTableClient(
                    bridgeClient = fake,
                    pushSource = fake,
                    connectionState = MutableStateFlow(ConnectionState.Connected),
                )

            val result = client.leaveTable("t-1")

            assertTrue(result.isFailure)
            assertNull(result.getOrNull())
        }
}
