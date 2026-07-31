package magefree.protocol

import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SerializationTest {
    private val json = ProtocolJson.json

    @Test
    fun `client messages round-trip through the polymorphic envelope`() {
        val messages: List<ClientMessage> =
            listOf(
                ClientHello(protocolMajor = ProtocolVersion.MAJOR, protocolMinor = ProtocolVersion.MINOR),
                Ping(nonce = "n-1", requestId = "r-1"),
                Ping(),
                Login(username = "tester", password = "secret", requestId = "r-3"),
                Login(username = "tester"),
                Logout(requestId = "r-4"),
                Logout(),
                GetServerInfo(requestId = "r-5"),
                GetServerInfo(),
                Resume(resumeId = "rsm-1"),
                Resume(resumeId = "rsm-2", requestId = "r-6"),
                GetTables(requestId = "r-7"),
                GetTables(),
                GetRoomUsers(requestId = "r-8"),
                GetRoomUsers(),
                GetGameTypes(requestId = "r-9"),
                GetGameTypes(),
            )

        for (message in messages) {
            val encoded = json.encodeToString<ClientMessage>(message)
            val decoded = json.decodeFromString<ClientMessage>(encoded)
            assertEquals(message, decoded)
        }
    }

    @Test
    fun `server messages round-trip through the polymorphic envelope`() {
        val messages: List<ServerMessage> =
            listOf(
                ServerHello(
                    protocolMajor = ProtocolVersion.MAJOR,
                    protocolMinor = ProtocolVersion.MINOR,
                    bridgeVersion = "1.2.3",
                ),
                Pong(nonce = "n-1", requestId = "r-1"),
                ProtocolError(
                    code = ProtocolErrorCode.PROTOCOL_VERSION_UNSUPPORTED,
                    message = "unsupported",
                    requestId = "r-2",
                ),
                SessionStatus(state = SessionStateCode.CONNECTING, requestId = "r-3"),
                SessionStatus(state = SessionStateCode.CONNECTED),
                SessionStatus(state = SessionStateCode.AUTH_FAILED, message = "bad credentials"),
                SessionStatus(
                    state = SessionStateCode.VERSION_UNSUPPORTED,
                    message = "server=1.4.61 bridge=1.4.60",
                ),
                SessionStatus(state = SessionStateCode.RECONNECTING),
                SessionStatus(state = SessionStateCode.DISCONNECTED),
                ChatEvent(
                    text = "hello",
                    username = "alice",
                    timestampEpochMs = 1_700_000_000_000L,
                    kind = ChatKind.TALK,
                ),
                ChatEvent(text = "sys", username = null, timestampEpochMs = 0L, kind = ChatKind.STATUS),
                ChatEvent(
                    text = "psst",
                    username = "bob",
                    timestampEpochMs = 42L,
                    kind = ChatKind.WHISPER_IN,
                    requestId = "r-6",
                ),
                ServerInfo(serverVersion = "1.4.60", mainRoomId = "room-1", requestId = "r-7"),
                ServerInfo(serverVersion = "1.4.60", mainRoomId = null),
                SessionResumable(resumeId = "rsm-1"),
                SessionResumable(resumeId = "rsm-2", requestId = "r-8"),
                ResumeRejected(reason = "unknown or expired resume handle"),
                ResumeRejected(reason = "expired", requestId = "r-9"),
                TableList(tables = emptyList()),
                TableList(
                    tables =
                        listOf(
                            TableSummary(
                                tableId = "t-1",
                                name = "Duel Night",
                                controllerName = "alice",
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
                            ),
                        ),
                    requestId = "r-10",
                ),
                RoomUserList(users = emptyList()),
                RoomUserList(
                    users =
                        listOf(
                            RoomUserSummary(
                                name = "alice",
                                flag = "United States",
                                matchHistory = "3-1",
                                games = "1 games",
                                ping = "42 ms",
                                generalRating = 1500,
                            ),
                        ),
                    requestId = "r-11",
                ),
                GameTypeList(gameTypes = emptyList()),
                GameTypeList(
                    gameTypes =
                        listOf(
                            GameTypeSummary(name = "Two Player Duel", minPlayers = 2, maxPlayers = 2),
                            GameTypeSummary(name = "Commander Free For All", minPlayers = 2, maxPlayers = 8),
                        ),
                    requestId = "r-12",
                ),
            )

        for (message in messages) {
            val encoded = json.encodeToString<ServerMessage>(message)
            val decoded = json.decodeFromString<ServerMessage>(encoded)
            assertEquals(message, decoded)
        }
    }

    @Test
    fun `each message serializes with its type discriminator`() {
        assertTrue(
            json.encodeToString<ClientMessage>(ClientHello(1, 0)).contains("\"type\":\"client_hello\""),
        )
        assertTrue(json.encodeToString<ClientMessage>(Ping()).contains("\"type\":\"ping\""))
        assertTrue(
            json.encodeToString<ClientMessage>(Login("tester")).contains("\"type\":\"login\""),
        )
        assertTrue(json.encodeToString<ClientMessage>(Logout()).contains("\"type\":\"logout\""))
        assertTrue(
            json.encodeToString<ClientMessage>(GetServerInfo()).contains("\"type\":\"get_server_info\""),
        )
        assertTrue(
            json.encodeToString<ClientMessage>(Resume(resumeId = "rsm")).contains("\"type\":\"resume\""),
        )
        assertTrue(
            json
                .encodeToString<ServerMessage>(SessionResumable(resumeId = "rsm"))
                .contains("\"type\":\"session_resumable\""),
        )
        assertTrue(
            json
                .encodeToString<ServerMessage>(ResumeRejected(reason = "x"))
                .contains("\"type\":\"resume_rejected\""),
        )
        assertTrue(
            json
                .encodeToString<ServerMessage>(
                    ChatEvent(text = "hi", username = "a", timestampEpochMs = 1L, kind = ChatKind.TALK),
                ).contains("\"type\":\"chat_event\""),
        )
        assertTrue(
            json
                .encodeToString<ServerMessage>(ServerInfo(serverVersion = "1", mainRoomId = null))
                .contains("\"type\":\"server_info\""),
        )
        assertTrue(
            json
                .encodeToString<ServerMessage>(SessionStatus(SessionStateCode.CONNECTED))
                .contains("\"type\":\"session_status\""),
        )
        assertTrue(
            json.encodeToString<ServerMessage>(ServerHello(1, 0, "1.0.0")).contains("\"type\":\"server_hello\""),
        )
        assertTrue(json.encodeToString<ClientMessage>(GetTables()).contains("\"type\":\"get_tables\""))
        assertTrue(json.encodeToString<ClientMessage>(GetRoomUsers()).contains("\"type\":\"get_room_users\""))
        assertTrue(json.encodeToString<ClientMessage>(GetGameTypes()).contains("\"type\":\"get_game_types\""))
        assertTrue(
            json.encodeToString<ServerMessage>(TableList(tables = emptyList())).contains("\"type\":\"table_list\""),
        )
        assertTrue(
            json
                .encodeToString<ServerMessage>(RoomUserList(users = emptyList()))
                .contains("\"type\":\"room_user_list\""),
        )
        assertTrue(
            json
                .encodeToString<ServerMessage>(GameTypeList(gameTypes = emptyList()))
                .contains("\"type\":\"game_type_list\""),
        )
        assertTrue(json.encodeToString<ServerMessage>(Pong()).contains("\"type\":\"pong\""))
        assertTrue(
            json
                .encodeToString<ServerMessage>(ProtocolError(ProtocolErrorCode.INTERNAL, "x"))
                .contains("\"type\":\"protocol_error\""),
        )
    }

    @Test
    fun `null defaults are omitted from the wire form`() {
        // encodeDefaults = false: an absent nonce/requestId must not appear in the JSON.
        val encoded = json.encodeToString<ClientMessage>(Ping())
        assertEquals("""{"type":"ping"}""", encoded)
    }

    @Test
    fun `an unknown extra field is tolerated on decode (forward-compat)`() {
        // A newer peer may add fields this build does not know; ignoreUnknownKeys must skip them.
        val futureFrame =
            """{"type":"client_hello","protocolMajor":1,"protocolMinor":0,"futureField":"ignored"}"""

        val decoded = json.decodeFromString<ClientMessage>(futureFrame)

        assertEquals(ClientHello(protocolMajor = 1, protocolMinor = 0), decoded)
    }

    @Test
    fun `an unknown ClientMessage type decodes to the sentinel instead of throwing (F1)`() {
        // A newer peer may add an entirely new message `type`; the decoder must tolerate it (story 0026).
        val futureFrame = """{"type":"future_client_message","someField":42}"""

        val decoded = json.decodeFromString<ClientMessage>(futureFrame)

        assertTrue(decoded is UnknownClientMessage, "expected an UnknownClientMessage sentinel, got $decoded")
        assertEquals("future_client_message", (decoded as UnknownClientMessage).type)
    }

    @Test
    fun `an unknown ServerMessage type decodes to the sentinel instead of throwing (F1)`() {
        val futureFrame = """{"type":"future_server_message","payload":{"nested":true}}"""

        val decoded = json.decodeFromString<ServerMessage>(futureFrame)

        assertTrue(decoded is UnknownServerMessage, "expected an UnknownServerMessage sentinel, got $decoded")
        assertEquals("future_server_message", (decoded as UnknownServerMessage).type)
    }

    @Test
    fun `the unknown-type sentinels are deserialize-only and never encoded (F1)`() {
        // The sentinels must never be put back on the wire; encoding one fails loudly.
        assertThrows(SerializationException::class.java) {
            json.encodeToString<ClientMessage>(UnknownClientMessage("future_client_message"))
        }
        assertThrows(SerializationException::class.java) {
            json.encodeToString<ServerMessage>(UnknownServerMessage("future_server_message"))
        }
    }
}
