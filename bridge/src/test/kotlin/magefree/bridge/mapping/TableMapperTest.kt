package magefree.bridge.mapping

import kotlinx.serialization.encodeToString
import mage.constants.SkillLevel
import mage.constants.TableState
import magefree.protocol.ProtocolJson
import magefree.protocol.ServerMessage
import magefree.protocol.SkillLevelCode
import magefree.protocol.TableList
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
 * raw view cannot be constructed here (unlike 0006's field-constructor `ChatMessage`). All of the
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
