package magefree.bridge.mapping

import kotlinx.serialization.encodeToString
import mage.constants.SkillLevel
import mage.constants.TableState
import mage.players.PlayerType
import magefree.protocol.ProtocolJson
import magefree.protocol.SeatPlayerTypeCode
import magefree.protocol.ServerMessage
import magefree.protocol.SkillLevelCode
import magefree.protocol.TableDetail
import magefree.protocol.TableList
import magefree.protocol.TableSeatSummary
import magefree.protocol.TableStateCode
import magefree.protocol.TableSummary
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Date

/**
 * Hermetic tests for [TableMapper].
 *
 * `mage.view.TableView`'s only constructor takes a `mage.game.Table` — a heavy game object that is
 * **not on the bridge classpath** (only `mage-common` is) and impractical to build in-memory — so the
 * raw view cannot be constructed here (unlike the field-constructor `ChatMessage`). All of the
 * mapping *logic* therefore lives in the pure [TableMapper.build] over already-extracted getter values,
 * and this test asserts that logic directly: the [TableState]→[TableStateCode] and
 * [SkillLevel]→[SkillLevelCode] translations (exhaustively), seat filled/total counting, and
 * create-time normalisation. The thin [TableMapper.map] getter-forwarding shim is exercised end-to-end
 * by the live `LobbyRelayIT`; because the reference server's table list is legitimately empty, the
 * shim's wiring is not covered hermetically — the documented, in-scope limit when a view is not
 * constructible and the live server yields no tables to map.
 */
class TableMapperTest {
    @Test
    fun `build maps the browse-relevant fields, counts seats, and normalises the create time`() {
        val summary =
            TableMapper.build(
                tableId = "table-1",
                name = "Duel Night",
                controllerName = "alice, bob",
                gameType = "Two Player Duel",
                deckType = "Constructed - Standard",
                state = TableState.WAITING,
                // One occupied seat, one empty → filled 1 / total 2.
                seatsOccupied = listOf(true, false),
                isTournament = false,
                isRated = true,
                isPassworded = false,
                isLimited = false,
                skillLevel = SkillLevel.CASUAL,
                createTime = Date(1_700_000_000_000L),
            )

        assertEquals(
            TableSummary(
                tableId = "table-1",
                name = "Duel Night",
                controllerName = "alice, bob",
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
            summary,
        )
    }

    @Test
    fun `every upstream TableState maps to its app-schema code`() {
        fun stateFor(state: TableState?): TableStateCode =
            TableMapper
                .build(
                    tableId = "t",
                    name = "n",
                    controllerName = "c",
                    gameType = "g",
                    deckType = "d",
                    state = state,
                    seatsOccupied = emptyList(),
                    isTournament = false,
                    isRated = false,
                    isPassworded = false,
                    isLimited = false,
                    skillLevel = null,
                    createTime = null,
                ).state

        assertEquals(TableStateCode.WAITING, stateFor(TableState.WAITING))
        assertEquals(TableStateCode.READY_TO_START, stateFor(TableState.READY_TO_START))
        assertEquals(TableStateCode.STARTING, stateFor(TableState.STARTING))
        assertEquals(TableStateCode.DRAFTING, stateFor(TableState.DRAFTING))
        assertEquals(TableStateCode.CONSTRUCTING, stateFor(TableState.CONSTRUCTING))
        assertEquals(TableStateCode.DUELING, stateFor(TableState.DUELING))
        assertEquals(TableStateCode.SIDEBOARDING, stateFor(TableState.SIDEBOARDING))
        assertEquals(TableStateCode.FINISHED, stateFor(TableState.FINISHED))
        // Defensive: a null (upstream drift) maps to UNKNOWN rather than throwing.
        assertEquals(TableStateCode.UNKNOWN, stateFor(null))
        // Confirm exhaustiveness against the upstream enum (fails loudly if XMage adds a state).
        assertEquals(TableState.entries.size, TableStateCode.entries.size - 1)
    }

    @Test
    fun `every upstream SkillLevel maps to its app-schema code`() {
        fun skillFor(skill: SkillLevel?): SkillLevelCode =
            TableMapper
                .build(
                    tableId = "t",
                    name = "n",
                    controllerName = "c",
                    gameType = "g",
                    deckType = "d",
                    state = TableState.WAITING,
                    seatsOccupied = emptyList(),
                    isTournament = false,
                    isRated = false,
                    isPassworded = false,
                    isLimited = false,
                    skillLevel = skill,
                    createTime = null,
                ).skillLevel

        assertEquals(SkillLevelCode.BEGINNER, skillFor(SkillLevel.BEGINNER))
        assertEquals(SkillLevelCode.CASUAL, skillFor(SkillLevel.CASUAL))
        assertEquals(SkillLevelCode.SERIOUS, skillFor(SkillLevel.SERIOUS))
        assertEquals(SkillLevelCode.UNKNOWN, skillFor(null))
        assertEquals(SkillLevel.entries.size, SkillLevelCode.entries.size - 1)
    }

    @Test
    fun `seat counting handles all-empty, all-filled, and no-seats`() {
        fun seats(occupied: List<Boolean>): Pair<Int, Int> =
            TableMapper
                .build(
                    tableId = "t",
                    name = "n",
                    controllerName = "c",
                    gameType = "g",
                    deckType = "d",
                    state = TableState.WAITING,
                    seatsOccupied = occupied,
                    isTournament = false,
                    isRated = false,
                    isPassworded = false,
                    isLimited = false,
                    skillLevel = null,
                    createTime = null,
                ).let { it.seatsFilled to it.seatsTotal }

        assertEquals(0 to 2, seats(listOf(false, false)))
        assertEquals(2 to 2, seats(listOf(true, true)))
        assertEquals(0 to 0, seats(emptyList()))
    }

    @Test
    fun `buildSeat maps an occupied seat, and normalises an empty seat's blank name to null`() {
        // Occupied: upstream reports a non-null playerId and the player's name.
        assertEquals(
            TableSeatSummary(index = 0, playerName = "alice", playerType = SeatPlayerTypeCode.HUMAN, occupied = true),
            TableMapper.buildSeat(
                index = 0,
                playerId = "0e2f1a2b-0000-0000-0000-000000000001",
                playerName = "alice",
                playerType = PlayerType.HUMAN,
            ),
        )

        // Empty: `SeatView` sets the name to "" (not null) and leaves playerId null. The seat keeps its
        // player *type* — that is what upstream matches a later join against.
        assertEquals(
            TableSeatSummary(index = 1, playerName = null, playerType = SeatPlayerTypeCode.COMPUTER_MAD, occupied = false),
            TableMapper.buildSeat(index = 1, playerId = null, playerName = "", playerType = PlayerType.COMPUTER_MAD),
        )
    }

    @Test
    fun `every upstream PlayerType maps to its app-schema seat code`() {
        fun typeFor(type: PlayerType?): SeatPlayerTypeCode =
            TableMapper.buildSeat(index = 0, playerId = null, playerName = null, playerType = type).playerType

        assertEquals(SeatPlayerTypeCode.HUMAN, typeFor(PlayerType.HUMAN))
        assertEquals(SeatPlayerTypeCode.COMPUTER_MONTE_CARLO, typeFor(PlayerType.COMPUTER_MONTE_CARLO))
        assertEquals(SeatPlayerTypeCode.COMPUTER_MAD, typeFor(PlayerType.COMPUTER_MAD))
        assertEquals(SeatPlayerTypeCode.COMPUTER_DRAFT_BOT, typeFor(PlayerType.COMPUTER_DRAFT_BOT))
        // Defensive: a null (upstream drift) maps to UNKNOWN rather than throwing.
        assertEquals(SeatPlayerTypeCode.UNKNOWN, typeFor(null))
        // Confirm exhaustiveness against the upstream enum (fails loudly if XMage adds a player type).
        assertEquals(PlayerType.entries.size, SeatPlayerTypeCode.entries.size - 1)
    }

    @Test
    fun `a mapped seat list round-trips inside a TableDetail`() {
        val detail =
            TableDetail(
                table =
                    TableMapper.build(
                        tableId = "table-1",
                        name = "Duel Night",
                        controllerName = "alice",
                        gameType = "Two Player Duel",
                        deckType = "Constructed - Freeform Unlimited",
                        state = TableState.READY_TO_START,
                        seatsOccupied = listOf(true, true),
                        isTournament = false,
                        isRated = false,
                        isPassworded = false,
                        isLimited = false,
                        skillLevel = SkillLevel.CASUAL,
                        createTime = Date(0),
                    ),
                seats =
                    listOf(
                        TableMapper.buildSeat(index = 0, playerId = "p-1", playerName = "alice", playerType = PlayerType.HUMAN),
                        TableMapper.buildSeat(
                            index = 1,
                            playerId = "p-2",
                            playerName = "Computer",
                            playerType = PlayerType.COMPUTER_MAD,
                        ),
                    ),
            )

        val encoded = ProtocolJson.json.encodeToString<ServerMessage>(detail)
        assertEquals(detail, ProtocolJson.json.decodeFromString<ServerMessage>(encoded))
    }

    @Test
    fun `a mapped table round-trips through the wire envelope`() {
        val summary =
            TableMapper.build(
                tableId = "table-1",
                name = "Duel Night",
                controllerName = "alice",
                gameType = "Two Player Duel",
                deckType = "Constructed - Standard",
                state = TableState.DUELING,
                seatsOccupied = listOf(true, true),
                isTournament = false,
                isRated = true,
                isPassworded = true,
                isLimited = false,
                skillLevel = SkillLevel.SERIOUS,
                createTime = Date(0),
            )

        val encoded = ProtocolJson.json.encodeToString<ServerMessage>(TableList(tables = listOf(summary)))
        val decoded = ProtocolJson.json.decodeFromString<ServerMessage>(encoded)
        assertEquals(TableList(tables = listOf(summary)), decoded)
    }
}
