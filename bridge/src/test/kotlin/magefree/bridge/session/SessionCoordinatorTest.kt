package magefree.bridge.session

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.receiveDeserialized
import io.ktor.client.plugins.websocket.sendSerialized
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.serialization.kotlinx.KotlinxWebsocketSerializationConverter
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import kotlinx.coroutines.withTimeout
import magefree.bridge.testApplicationTimed
import magefree.bridge.ws.sessionWebSocket
import magefree.protocol.ClientHello
import magefree.protocol.ClientMessage
import magefree.protocol.GameTypeList
import magefree.protocol.GameTypeSummary
import magefree.protocol.GetGameTypes
import magefree.protocol.GetRoomUsers
import magefree.protocol.GetServerInfo
import magefree.protocol.GetTable
import magefree.protocol.GetTables
import magefree.protocol.Login
import magefree.protocol.Logout
import magefree.protocol.Ping
import magefree.protocol.Pong
import magefree.protocol.ProtocolJson
import magefree.protocol.ProtocolVersion
import magefree.protocol.RoomUserList
import magefree.protocol.RoomUserSummary
import magefree.protocol.ServerHello
import magefree.protocol.ServerInfo
import magefree.protocol.ServerMessage
import magefree.protocol.SessionResumable
import magefree.protocol.SessionStateCode
import magefree.protocol.SessionStatus
import magefree.protocol.SkillLevelCode
import magefree.protocol.TableDetail
import magefree.protocol.TableList
import magefree.protocol.TableNotFound
import magefree.protocol.TableSeatSummary
import magefree.protocol.TableStateCode
import magefree.protocol.TableSummary
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.server.websocket.WebSockets as ServerWebSockets

/**
 * Hermetic end-to-end coverage of the session bridge: a real Ktor WebSocket client drives the real
 * [sessionWebSocket] route + [SessionCoordinator], with a [FakeUpstreamSession] scripting each
 * `SessionStateCode` path. No XMage server involved. (Resume/park/evict scenarios live in
 * [SessionResumeTest].)
 */
class SessionCoordinatorTest {
    /** Boots the route with [fake] as the per-login upstream and a fresh [SessionRegistry]. */
    private fun scenario(
        fake: FakeUpstreamSession,
        config: ResumeConfig = ResumeConfig(),
        block: suspend (client: HttpClient) -> Unit,
    ) = testApplicationTimed {
        val registry = SessionRegistry(config)
        try {
            application { sessionModule(registry) { fake } }
            val wsClient =
                createClient {
                    install(ClientWebSockets) {
                        contentConverter = KotlinxWebsocketSerializationConverter(ProtocolJson.json)
                    }
                }
            block(wsClient)
        } finally {
            registry.shutdown()
        }
    }

    private fun Application.sessionModule(
        registry: SessionRegistry,
        newUpstream: () -> UpstreamSession,
    ) {
        install(ServerWebSockets) {
            contentConverter = KotlinxWebsocketSerializationConverter(ProtocolJson.json)
        }
        routing { sessionWebSocket(registry = registry, newUpstream = newUpstream) }
    }

    private suspend fun HttpClient.session(block: suspend DefaultClientWebSocketSession.() -> Unit) =
        webSocket("/${ProtocolVersion.PATH_SEGMENT}/session") { block() }

    private suspend fun DefaultClientWebSocketSession.handshake() {
        sendSerialized<ClientMessage>(
            ClientHello(protocolMajor = ProtocolVersion.MAJOR, protocolMinor = ProtocolVersion.MINOR),
        )
        assertInstanceOf(ServerHello::class.java, receiveDeserialized<ServerMessage>())
    }

    private suspend fun DefaultClientWebSocketSession.nextStatus(): SessionStatus =
        assertInstanceOf(SessionStatus::class.java, receiveDeserialized<ServerMessage>())

    private suspend fun DefaultClientWebSocketSession.expectResumable(): SessionResumable =
        assertInstanceOf(SessionResumable::class.java, receiveDeserialized<ServerMessage>())

    private fun status(
        state: SessionStateCode,
        message: String? = null,
    ) = SessionStatus(state = state, message = message)

    @Test
    fun `login yields CONNECTING then CONNECTED and a resume handle, requestId only on the first`() {
        val fake = FakeUpstreamSession(listOf(status(SessionStateCode.CONNECTING), status(SessionStateCode.CONNECTED)))
        scenario(fake) { client ->
            client.session {
                handshake()
                sendSerialized<ClientMessage>(Login(username = "alice", password = "pw", requestId = "req-1"))

                val connecting = nextStatus()
                assertEquals(SessionStateCode.CONNECTING, connecting.state)
                assertEquals("req-1", connecting.requestId)

                val connected = nextStatus()
                assertEquals(SessionStateCode.CONNECTED, connected.state)
                assertNull(connected.requestId)

                // Story 0023: on CONNECTED the app receives its bridge-issued resume handle.
                val resumable = expectResumable()
                assertNotNull(resumable.resumeId)
            }
            assertEquals(Credentials("alice", "pw"), fake.lastCredentials)
        }
    }

    @Test
    fun `bad credentials yield AUTH_FAILED`() {
        val fake = FakeUpstreamSession(listOf(status(SessionStateCode.CONNECTING), status(SessionStateCode.AUTH_FAILED)))
        scenario(fake) { client ->
            client.session {
                handshake()
                sendSerialized<ClientMessage>(Login(username = "bob"))
                assertEquals(SessionStateCode.CONNECTING, nextStatus().state)
                assertEquals(SessionStateCode.AUTH_FAILED, nextStatus().state)
            }
        }
    }

    @Test
    fun `a version gap yields VERSION_UNSUPPORTED carrying both versions`() {
        val fake =
            FakeUpstreamSession(
                listOf(
                    status(SessionStateCode.CONNECTING),
                    status(SessionStateCode.VERSION_UNSUPPORTED, message = "server=1.4.61 bridge=1.4.60"),
                ),
            )
        scenario(fake) { client ->
            client.session {
                handshake()
                sendSerialized<ClientMessage>(Login(username = "carol"))
                assertEquals(SessionStateCode.CONNECTING, nextStatus().state)

                val versionGap = nextStatus()
                assertEquals(SessionStateCode.VERSION_UNSUPPORTED, versionGap.state)
                assertNotNull(versionGap.message)
                assertEquals(true, versionGap.message!!.contains("server=1.4.61"))
                assertEquals(true, versionGap.message!!.contains("bridge=1.4.60"))
            }
        }
    }

    @Test
    fun `an upstream drop surfaces RECONNECTING then DISCONNECTED`() {
        val fake =
            FakeUpstreamSession(
                listOf(
                    status(SessionStateCode.CONNECTING),
                    status(SessionStateCode.CONNECTED),
                    status(SessionStateCode.RECONNECTING),
                    status(SessionStateCode.DISCONNECTED),
                ),
            )
        scenario(fake) { client ->
            client.session {
                handshake()
                sendSerialized<ClientMessage>(Login(username = "dave"))
                assertEquals(SessionStateCode.CONNECTING, nextStatus().state)
                assertEquals(SessionStateCode.CONNECTED, nextStatus().state)
                expectResumable()
                assertEquals(SessionStateCode.RECONNECTING, nextStatus().state)
                assertEquals(SessionStateCode.DISCONNECTED, nextStatus().state)
            }
        }
    }

    @Test
    fun `Logout disconnects the upstream`() {
        val fake = FakeUpstreamSession(listOf(status(SessionStateCode.CONNECTING), status(SessionStateCode.CONNECTED)))
        scenario(fake) { client ->
            client.session {
                handshake()
                sendSerialized<ClientMessage>(Login(username = "erin"))
                assertEquals(SessionStateCode.CONNECTING, nextStatus().state)
                assertEquals(SessionStateCode.CONNECTED, nextStatus().state)
                expectResumable()
                sendSerialized<ClientMessage>(Logout())
                withTimeout(5_000) { fake.awaitDisconnect() }
            }
        }
    }

    @Test
    fun `GetServerInfo yields ServerInfo correlated by requestId`() {
        val fake =
            FakeUpstreamSession(
                listOf(status(SessionStateCode.CONNECTING), status(SessionStateCode.CONNECTED)),
                scriptedServerInfo = ServerInfo(serverVersion = "1.4.60", mainRoomId = "room-42"),
            )
        scenario(fake) { client ->
            client.session {
                handshake()
                sendSerialized<ClientMessage>(Login(username = "frank"))
                assertEquals(SessionStateCode.CONNECTING, nextStatus().state)
                assertEquals(SessionStateCode.CONNECTED, nextStatus().state)
                expectResumable()

                sendSerialized<ClientMessage>(GetServerInfo(requestId = "gsi-1"))
                val info = assertInstanceOf(ServerInfo::class.java, receiveDeserialized<ServerMessage>())
                assertEquals("1.4.60", info.serverVersion)
                assertEquals("room-42", info.mainRoomId)
                assertEquals("gsi-1", info.requestId)
            }
        }
    }

    @Test
    fun `lobby browse requests reply correlated list messages sourced from the bound session`() {
        val table =
            TableSummary(
                tableId = "table-1",
                name = "Duel Night",
                controllerName = "grace",
                gameType = "Two Player Duel",
                deckType = "Constructed - Standard",
                state = TableStateCode.WAITING,
                seatsFilled = 1,
                seatsTotal = 2,
                isTournament = false,
                isRated = true,
                isPassworded = false,
                isLimited = false,
                skillLevel = SkillLevelCode.CASUAL,
                createdAtEpochMs = 1_700_000_000_000L,
            )
        val user =
            RoomUserSummary(
                name = "grace",
                flag = "United States",
                matchHistory = "3-1",
                games = "1 games",
                ping = "42 ms",
                generalRating = 1500,
            )
        val gameType = GameTypeSummary(name = "Two Player Duel", minPlayers = 2, maxPlayers = 2)

        val fake =
            FakeUpstreamSession(
                listOf(status(SessionStateCode.CONNECTING), status(SessionStateCode.CONNECTED)),
                scriptedTables = listOf(table),
                scriptedRoomUsers = listOf(user),
                scriptedGameTypes = listOf(gameType),
            )
        scenario(fake) { client ->
            client.session {
                handshake()
                sendSerialized<ClientMessage>(Login(username = "grace"))
                assertEquals(SessionStateCode.CONNECTING, nextStatus().state)
                assertEquals(SessionStateCode.CONNECTED, nextStatus().state)
                expectResumable()

                sendSerialized<ClientMessage>(GetTables(requestId = "gt-1"))
                val tables = assertInstanceOf(TableList::class.java, receiveDeserialized<ServerMessage>())
                assertEquals(listOf(table), tables.tables)
                assertEquals("gt-1", tables.requestId)

                sendSerialized<ClientMessage>(GetRoomUsers(requestId = "gru-1"))
                val users = assertInstanceOf(RoomUserList::class.java, receiveDeserialized<ServerMessage>())
                assertEquals(listOf(user), users.users)
                assertEquals("gru-1", users.requestId)

                sendSerialized<ClientMessage>(GetGameTypes(requestId = "ggt-1"))
                val gameTypes = assertInstanceOf(GameTypeList::class.java, receiveDeserialized<ServerMessage>())
                assertEquals(listOf(gameType), gameTypes.gameTypes)
                assertEquals("ggt-1", gameTypes.requestId)
            }
        }
    }

    @Test
    fun `lobby browse before login replies well-formed empty lists`() {
        val fake = FakeUpstreamSession(emptyList())
        scenario(fake) { client ->
            client.session {
                handshake()

                sendSerialized<ClientMessage>(GetTables(requestId = "gt-2"))
                val tables = assertInstanceOf(TableList::class.java, receiveDeserialized<ServerMessage>())
                assertEquals(emptyList<TableSummary>(), tables.tables)
                assertEquals("gt-2", tables.requestId)

                sendSerialized<ClientMessage>(GetRoomUsers(requestId = "gru-2"))
                val users = assertInstanceOf(RoomUserList::class.java, receiveDeserialized<ServerMessage>())
                assertEquals(emptyList<RoomUserSummary>(), users.users)
                assertEquals("gru-2", users.requestId)

                sendSerialized<ClientMessage>(GetGameTypes(requestId = "ggt-2"))
                val gameTypes = assertInstanceOf(GameTypeList::class.java, receiveDeserialized<ServerMessage>())
                assertEquals(emptyList<GameTypeSummary>(), gameTypes.gameTypes)
                assertEquals("ggt-2", gameTypes.requestId)
            }
        }
    }

    @Test
    fun `GetTable replies the correlated table detail, and a typed not-found on an unbound socket`() {
        // Story 0040: the room's seats travel this exchange, so the coordinator must dispatch it to the
        // bound session and stamp the requestId — otherwise the app's read never correlates and the
        // room never learns its seats.
        val detail =
            TableDetail(
                table =
                    TableSummary(
                        tableId = "table-1",
                        name = "Duel Night",
                        controllerName = "grace",
                        gameType = "Two Player Duel",
                        deckType = "Constructed - Standard",
                        state = TableStateCode.READY_TO_START,
                        seatsFilled = 2,
                        seatsTotal = 2,
                        isTournament = false,
                        isRated = false,
                        isPassworded = false,
                        isLimited = false,
                        skillLevel = SkillLevelCode.CASUAL,
                        createdAtEpochMs = 0L,
                    ),
                seats =
                    listOf(
                        TableSeatSummary(index = 0, playerName = "grace", occupied = true),
                        TableSeatSummary(index = 1, playerName = "Computer", occupied = true),
                    ),
            )

        val bound =
            FakeUpstreamSession(
                listOf(status(SessionStateCode.CONNECTING), status(SessionStateCode.CONNECTED)),
                scriptedTableDetail = detail,
            )
        scenario(bound) { client ->
            client.session {
                handshake()
                sendSerialized<ClientMessage>(Login(username = "grace"))
                assertEquals(SessionStateCode.CONNECTING, nextStatus().state)
                assertEquals(SessionStateCode.CONNECTED, nextStatus().state)
                expectResumable()

                sendSerialized<ClientMessage>(GetTable(tableId = "table-1", requestId = "gt-d1"))
                val reply = assertInstanceOf(TableDetail::class.java, receiveDeserialized<ServerMessage>())
                assertEquals(detail.seats, reply.seats)
                assertEquals(TableStateCode.READY_TO_START, reply.table.state)
                assertEquals("gt-d1", reply.requestId)
            }
            assertEquals("table-1", (bound.lastTableRequest as GetTable).tableId)
        }

        val unbound = FakeUpstreamSession(emptyList())
        scenario(unbound) { client ->
            client.session {
                handshake()
                sendSerialized<ClientMessage>(GetTable(tableId = "table-9", requestId = "gt-d2"))
                val miss = assertInstanceOf(TableNotFound::class.java, receiveDeserialized<ServerMessage>())
                assertEquals("table-9", miss.tableId)
                assertNotNull(miss.reason)
                assertEquals("gt-d2", miss.requestId)
            }
        }
    }

    @Test
    fun `Ping still yields Pong after the handshake`() {
        val fake = FakeUpstreamSession(emptyList())
        scenario(fake) { client ->
            client.session {
                handshake()
                sendSerialized<ClientMessage>(Ping(nonce = "n", requestId = "p-1"))
                val reply = assertInstanceOf(Pong::class.java, receiveDeserialized<ServerMessage>())
                assertEquals("n", reply.nonce)
                assertEquals("p-1", reply.requestId)
            }
        }
    }
}
