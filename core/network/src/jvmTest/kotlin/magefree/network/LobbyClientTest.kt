package magefree.network

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import magefree.model.SkillLevel
import magefree.model.TableState
import magefree.network.fake.FakeBridgeClient
import magefree.protocol.ClientMessage
import magefree.protocol.GameTypeList
import magefree.protocol.GameTypeSummary
import magefree.protocol.GetGameTypes
import magefree.protocol.GetRoomUsers
import magefree.protocol.GetTables
import magefree.protocol.RoomUserList
import magefree.protocol.RoomUserSummary
import magefree.protocol.ServerMessage
import magefree.protocol.SkillLevelCode
import magefree.protocol.TableList
import magefree.protocol.TableStateCode
import magefree.protocol.TableSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hermetic coverage of the production [LobbyClientImpl]: it sends the right lobby `GetX` request over
 * the transport's request/response primitive and maps the correlated `XList` reply's `:protocol`
 * summaries into `:core:model`. A [FakeBridgeClient] with a scripted responder stands in for the live
 * socket (fakes are recordings — real `:protocol` replies through the production mapper).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LobbyClientTest {
    @Test
    fun tablesSendsGetTablesAndMapsTheReply() =
        runTest {
            var sent: ClientMessage? = null
            val client =
                LobbyClientImpl(
                    FakeBridgeClient(
                        responder = { request ->
                            sent = request
                            TableList(
                                tables =
                                    listOf(
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
                                            isRated = true,
                                            isPassworded = false,
                                            isLimited = false,
                                            skillLevel = SkillLevelCode.CASUAL,
                                            createdAtEpochMs = 123L,
                                        ),
                                    ),
                            )
                        },
                    ),
                )

            val tables = client.tables()

            assertTrue("must send GetTables", sent is GetTables)
            assertEquals(1, tables.size)
            assertEquals("t-1", tables.first().id)
            assertEquals("pete", tables.first().host)
            assertEquals(TableState.Waiting, tables.first().state)
            assertEquals(SkillLevel.Casual, tables.first().skillLevel)
        }

    @Test
    fun roomUsersSendsGetRoomUsersAndMapsTheReply() =
        runTest {
            var sent: ClientMessage? = null
            val client =
                LobbyClientImpl(
                    FakeBridgeClient(
                        responder = { request ->
                            sent = request
                            RoomUserList(
                                users = listOf(RoomUserSummary("pete", "US", "W:1", "idle", "10ms", 1500)),
                            )
                        },
                    ),
                )

            val users = client.roomUsers()

            assertTrue("must send GetRoomUsers", sent is GetRoomUsers)
            assertEquals(listOf("pete"), users.map { it.name })
            assertEquals(1500, users.first().generalRating)
        }

    @Test
    fun gameTypesSendsGetGameTypesAndMapsTheReply() =
        runTest {
            var sent: ClientMessage? = null
            val client =
                LobbyClientImpl(
                    FakeBridgeClient(
                        responder = { request ->
                            sent = request
                            GameTypeList(gameTypes = listOf(GameTypeSummary("Two Player Duel", 2, 2)))
                        },
                    ),
                )

            val types = client.gameTypes()

            assertTrue("must send GetGameTypes", sent is GetGameTypes)
            assertEquals(listOf("Two Player Duel"), types.map { it.name })
            assertEquals(2, types.first().maxPlayers)
        }

    @Test
    fun emptyTableReplyMapsToEmptyList() =
        runTest {
            val client = LobbyClientImpl(FakeBridgeClient(responder = { TableList(tables = emptyList()) }))
            assertTrue(client.tables().isEmpty())
        }

    @Test(expected = IllegalStateException::class)
    fun aWrongReplyTypeThrows() =
        runTest {
            // A correlated reply of the wrong shape is a protocol fault the repository turns into error state.
            val wrong: ServerMessage = GameTypeList(gameTypes = emptyList())
            val client = LobbyClientImpl(FakeBridgeClient(responder = { wrong }))
            client.tables()
        }
}
