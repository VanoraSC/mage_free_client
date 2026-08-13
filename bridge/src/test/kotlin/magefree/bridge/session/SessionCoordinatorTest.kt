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
import magefree.protocol.CreateTable
import magefree.protocol.CreateTableOptions
import magefree.protocol.GameActionCode
import magefree.protocol.GameActionResult
import magefree.protocol.GameCardView
import magefree.protocol.GameFailureCode
import magefree.protocol.GamePlayerView
import magefree.protocol.GameStarted
import magefree.protocol.GameStateSnapshot
import magefree.protocol.GameStateUnavailable
import magefree.protocol.GameStateUnavailableCode
import magefree.protocol.GameStateView
import magefree.protocol.GameTypeList
import magefree.protocol.GameTypeSummary
import magefree.protocol.GetGameState
import magefree.protocol.GetGameTypes
import magefree.protocol.GetRoomUsers
import magefree.protocol.GetServerInfo
import magefree.protocol.GetTable
import magefree.protocol.GetTables
import magefree.protocol.JoinGame
import magefree.protocol.Login
import magefree.protocol.Logout
import magefree.protocol.ManaTypeCode
import magefree.protocol.PhaseStepCode
import magefree.protocol.Ping
import magefree.protocol.PlayerActionCode
import magefree.protocol.Pong
import magefree.protocol.ProtocolJson
import magefree.protocol.ProtocolVersion
import magefree.protocol.QuitMatch
import magefree.protocol.RoomUserList
import magefree.protocol.RoomUserSummary
import magefree.protocol.SendPlayerAction
import magefree.protocol.SendPlayerBoolean
import magefree.protocol.SendPlayerInteger
import magefree.protocol.SendPlayerManaType
import magefree.protocol.SendPlayerString
import magefree.protocol.SendPlayerUuid
import magefree.protocol.ServerHello
import magefree.protocol.ServerInfo
import magefree.protocol.ServerMessage
import magefree.protocol.SessionResumable
import magefree.protocol.SessionStateCode
import magefree.protocol.SessionStatus
import magefree.protocol.SkillLevelCode
import magefree.protocol.StopWatching
import magefree.protocol.TableActionResult
import magefree.protocol.TableDetail
import magefree.protocol.TableFailureCode
import magefree.protocol.TableList
import magefree.protocol.TableNotFound
import magefree.protocol.TableSeatSummary
import magefree.protocol.TableStateCode
import magefree.protocol.TableSummary
import magefree.protocol.TurnPhaseCode
import magefree.protocol.WatchGame
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
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

    /**
     * Story 0050 defect A, second half: a table action on a socket with no session must be answered with
     * the **kind** of failure, not just prose. The app branches on
     * [magefree.protocol.TableFailureCode.SESSION_GONE] to say "you are signed out" instead of repeating
     * a server refusal that never happened — the server was never asked.
     */
    @Test
    fun `a table action on an unbound socket fails as SESSION_GONE, not as a server refusal`() {
        val unbound = FakeUpstreamSession(emptyList())
        scenario(unbound) { client ->
            client.session {
                handshake()
                sendSerialized<ClientMessage>(
                    CreateTable(options = CreateTableOptions("t", "Two Player Duel", "Constructed"), requestId = "c-1"),
                )
                val reply = assertInstanceOf(TableActionResult::class.java, receiveDeserialized<ServerMessage>())
                assertFalse(reply.ok)
                assertEquals(
                    TableFailureCode.SESSION_GONE,
                    reply.failure,
                    "an action with no session behind it is not a decline the user can act on by retrying",
                )
                assertEquals("c-1", reply.requestId)
            }
        }
    }

    /**
     * Story 0051: every in-game request must reach the upstream seam and come back **correlated**. The
     * risk this guards is specific — the game verbs share one dispatch arm in the coordinator, so a
     * mis-wired reply would answer with the wrong [magefree.protocol.GameActionCode] (or lose the
     * `requestId`) and the app would match a prompt answer to the wrong outstanding request.
     */
    @Test
    fun `every in-game request is dispatched upstream and answered with its own action code and requestId`() {
        val fake =
            FakeUpstreamSession(listOf(status(SessionStateCode.CONNECTING), status(SessionStateCode.CONNECTED)))
        val gameId = "11111111-2222-3333-4444-555555555555"
        val requests: List<Pair<ClientMessage, GameActionCode>> =
            listOf(
                JoinGame(gameId = gameId, requestId = "g-1") to GameActionCode.JOIN_GAME,
                WatchGame(gameId = gameId, requestId = "g-2") to GameActionCode.WATCH_GAME,
                QuitMatch(gameId = gameId, requestId = "g-3") to GameActionCode.QUIT_MATCH,
                StopWatching(gameId = gameId, requestId = "g-4") to GameActionCode.STOP_WATCHING,
                SendPlayerUuid(gameId = gameId, value = gameId, requestId = "g-5") to GameActionCode.SEND_UUID,
                SendPlayerBoolean(gameId = gameId, value = false, requestId = "g-6") to GameActionCode.SEND_BOOLEAN,
                SendPlayerInteger(gameId = gameId, value = 2, requestId = "g-7") to GameActionCode.SEND_INTEGER,
                SendPlayerString(gameId = gameId, value = "1,2", requestId = "g-8") to GameActionCode.SEND_STRING,
                SendPlayerManaType(gameId = gameId, playerId = gameId, manaType = ManaTypeCode.GREEN, requestId = "g-9")
                    to GameActionCode.SEND_MANA_TYPE,
                SendPlayerAction(gameId = gameId, action = PlayerActionCode.CONCEDE, requestId = "g-10")
                    to GameActionCode.PLAYER_ACTION,
            )

        scenario(fake) { client ->
            client.session {
                handshake()
                sendSerialized<ClientMessage>(Login(username = "grace"))
                assertEquals(SessionStateCode.CONNECTING, nextStatus().state)
                assertEquals(SessionStateCode.CONNECTED, nextStatus().state)
                expectResumable()

                requests.forEach { (request, expectedAction) ->
                    sendSerialized(request)
                    val reply = assertInstanceOf(GameActionResult::class.java, receiveDeserialized<ServerMessage>())
                    assertTrue(reply.ok, "the fake upstream accepts, so $request should succeed")
                    assertEquals(expectedAction, reply.action, "$request must be answered with its own action code")
                    assertEquals(gameRequestIdOf(request), reply.requestId, "$request must be correlated")
                    assertEquals(request, fake.lastGameRequest, "$request must reach the upstream seam unchanged")
                }
            }
        }
    }

    /**
     * Story 0051 + 0050: a game request on a socket with no session is answered with the **kind** of
     * failure. A player who has been signed out must be told that, not told the server declined their
     * pass — the server was never asked, and retrying will never work.
     */
    @Test
    fun `a game request on an unbound socket fails as SESSION_GONE`() {
        val unbound = FakeUpstreamSession(emptyList())
        scenario(unbound) { client ->
            client.session {
                handshake()
                sendSerialized<ClientMessage>(SendPlayerBoolean(gameId = "g", value = false, requestId = "gb-1"))
                val reply = assertInstanceOf(GameActionResult::class.java, receiveDeserialized<ServerMessage>())
                assertFalse(reply.ok)
                assertEquals(GameActionCode.SEND_BOOLEAN, reply.action)
                assertEquals(GameFailureCode.SESSION_GONE, reply.failure)
                assertEquals("gb-1", reply.requestId)
            }
            assertNull(unbound.lastGameRequest, "an unbound socket must not reach the upstream at all")
        }
    }

    @Test
    fun `GetGameState replies the correlated snapshot the session was pushed, and a typed no-state before it`() {
        // Story 0054, over the socket: the read is answered from *this session's* cache, which the
        // outbound pump fills as the snapshot goes past. Before the first push the reply is a typed
        // no-state — never an empty GameStateView, which the app could not tell from a real board.
        val state =
            GameStateView(
                turn = 2,
                phase = TurnPhaseCode.PRECOMBAT_MAIN,
                step = PhaseStepCode.PRECOMBAT_MAIN,
                activePlayerId = "p-1",
                activePlayerName = "heidi",
                viewerPlayerId = "p-1",
                viewerHasPriority = true,
                players = listOf(GamePlayerView(playerId = "p-1", name = "heidi", life = 20, viewer = true)),
                hand = listOf(GameCardView(id = "c-1", name = "Forest", setCode = "M21", collectorNumber = "272")),
            )

        val fake = FakeUpstreamSession(listOf(status(SessionStateCode.CONNECTING), status(SessionStateCode.CONNECTED)))
        scenario(fake) { client ->
            client.session {
                handshake()
                sendSerialized<ClientMessage>(Login(username = "heidi"))
                assertEquals(SessionStateCode.CONNECTING, nextStatus().state)
                assertEquals(SessionStateCode.CONNECTED, nextStatus().state)
                expectResumable()

                sendSerialized<ClientMessage>(GetGameState(gameId = "g-1", requestId = "gs-1"))
                val miss = assertInstanceOf(GameStateUnavailable::class.java, receiveDeserialized<ServerMessage>())
                assertEquals("g-1", miss.gameId)
                assertEquals(GameStateUnavailableCode.NO_STATE_YET, miss.reason)
                assertEquals("gs-1", miss.requestId, "a miss must correlate too, or the app's waiter times out")

                // The producer: one server push, relayed to this socket and cached on the way past.
                fake.emit(GameStarted(gameId = "g-1", state = state))
                val pushed = assertInstanceOf(GameStarted::class.java, receiveDeserialized<ServerMessage>())
                assertEquals(2, pushed.state.turn)

                sendSerialized<ClientMessage>(GetGameState(gameId = "g-1", requestId = "gs-2"))
                val hit = assertInstanceOf(GameStateSnapshot::class.java, receiveDeserialized<ServerMessage>())
                assertEquals("gs-2", hit.requestId)
                assertEquals(state, hit.state, "the reply is the server's own snapshot, verbatim")
                assertNotNull(hit.capturedAtEpochMs, "the capture time makes staleness knowable rather than guessed")

                // A game this session was never pushed is still a typed miss, not a blank board.
                sendSerialized<ClientMessage>(GetGameState(gameId = "g-other", requestId = "gs-3"))
                val other = assertInstanceOf(GameStateUnavailable::class.java, receiveDeserialized<ServerMessage>())
                assertEquals(GameStateUnavailableCode.NO_STATE_YET, other.reason)
                assertEquals("gs-3", other.requestId)
            }
        }
    }

    @Test
    fun `GetGameState on an unbound socket is a typed SESSION_GONE, never an empty board`() {
        // The 0050 convention on the read side: there is no session, so there is not even a cache to
        // look in. The app must be told to sign in again rather than shown a board with nothing on it.
        val unbound = FakeUpstreamSession(emptyList())
        scenario(unbound) { client ->
            client.session {
                handshake()
                sendSerialized<ClientMessage>(GetGameState(gameId = "g-1", requestId = "gs-9"))
                val reply = assertInstanceOf(GameStateUnavailable::class.java, receiveDeserialized<ServerMessage>())
                assertEquals("g-1", reply.gameId)
                assertEquals(GameStateUnavailableCode.SESSION_GONE, reply.reason)
                assertNotNull(reply.detail)
                assertEquals("gs-9", reply.requestId)
            }
        }
    }

    /** The `requestId` a game request carries — the correlation the coordinator must echo. */
    private fun gameRequestIdOf(request: ClientMessage): String? =
        when (request) {
            is JoinGame -> request.requestId
            is WatchGame -> request.requestId
            is QuitMatch -> request.requestId
            is StopWatching -> request.requestId
            is SendPlayerUuid -> request.requestId
            is SendPlayerBoolean -> request.requestId
            is SendPlayerInteger -> request.requestId
            is SendPlayerString -> request.requestId
            is SendPlayerManaType -> request.requestId
            is SendPlayerAction -> request.requestId
            else -> null
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
