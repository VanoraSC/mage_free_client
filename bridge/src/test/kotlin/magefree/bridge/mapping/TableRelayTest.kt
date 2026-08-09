package magefree.bridge.mapping

import mage.cards.decks.DeckCardLists
import mage.game.match.MatchOptions
import mage.players.PlayerType
import mage.remote.SessionImpl
import mage.view.TableView
import magefree.bridge.xmage.BridgeMageClient
import magefree.protocol.CreateTableOptions
import magefree.protocol.DeckList
import magefree.protocol.DeckListCard
import magefree.protocol.SeatPlayerTypeCode
import magefree.protocol.SkillLevelCode
import magefree.protocol.TableActionCode
import magefree.protocol.TableActionResult
import magefree.protocol.TableCreated
import magefree.protocol.TableDetail
import magefree.protocol.TableNotFound
import magefree.protocol.TableSeatSummary
import magefree.protocol.TableStateCode
import magefree.protocol.TableSummary
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Hermetic dispatch tests for [TableRelay]. The wrapped `SessionImpl` is subclassed by [FakeSession]
 * (its constructor takes only a `MageClient`, and the action verbs are non-final) so each verb records
 * the arguments it was dispatched with — including the `mage.*` types built at the mapper boundary
 * (`MatchOptions`, `DeckCardLists`, `PlayerType`) — and returns a canned result. This proves each
 * relay method calls the right verb with correctly mapped args and maps the result back, including a
 * `false`→failure and the `createTable`→`TableCreated` decision.
 *
 * `createTable`'s success mapping wraps a `mage.view.TableView`, which cannot be constructed in-memory
 * (its only constructor needs a `mage.game.Table`, off the bridge classpath); its `TableView →
 * TableSummary` getter-forwarding ([TableMapper.map]) is covered by the live path, and the create
 * *decision* (view → TableCreated / null → failure) is asserted here via the pure [TableRelay.createdMessage].
 */
class TableRelayTest {
    private val room = UUID.randomUUID()
    private val table = UUID.randomUUID()

    private val deck =
        DeckList(
            name = "Mono-U",
            author = "alice",
            cards = listOf(DeckListCard(cardName = "Island", setCode = "M21", collectorNumber = "123", amount = 20)),
            sideboard = listOf(DeckListCard(cardName = "Negate", setCode = "M21", collectorNumber = "58", amount = 3)),
        )

    /** A [SessionImpl] whose action verbs record their arguments and return a scripted result. */
    private class FakeSession(
        var boolReturn: Boolean = true,
        var createReturn: TableView? = null,
    ) : SessionImpl(BridgeMageClient()) {
        var createRoom: UUID? = null
        var createOptions: MatchOptions? = null
        var joinRoom: UUID? = null
        var joinTableId: UUID? = null
        var joinName: String? = null
        var joinPlayerType: PlayerType? = null
        var joinSkill: Int? = null
        var joinDeck: DeckCardLists? = null
        var joinPassword: String? = null
        var submitTableId: UUID? = null
        var submitDeck: DeckCardLists? = null
        var updateTableId: UUID? = null
        var updateDeck: DeckCardLists? = null
        var leaveRoom: UUID? = null
        var leaveTableId: UUID? = null
        var removeRoom: UUID? = null
        var removeTableId: UUID? = null
        var startRoom: UUID? = null
        var startTableId: UUID? = null
        var watchRoom: UUID? = null
        var watchTableId: UUID? = null

        override fun createTable(
            roomId: UUID,
            matchOptions: MatchOptions,
        ): TableView? {
            createRoom = roomId
            createOptions = matchOptions
            return createReturn
        }

        override fun joinTable(
            roomId: UUID,
            tableId: UUID,
            playerName: String,
            playerType: PlayerType,
            skill: Int,
            deckList: DeckCardLists,
            password: String,
        ): Boolean {
            joinRoom = roomId
            joinTableId = tableId
            joinName = playerName
            joinPlayerType = playerType
            joinSkill = skill
            joinDeck = deckList
            joinPassword = password
            return boolReturn
        }

        override fun submitDeck(
            tableId: UUID,
            deck: DeckCardLists,
        ): Boolean {
            submitTableId = tableId
            submitDeck = deck
            return boolReturn
        }

        override fun updateDeck(
            tableId: UUID,
            deck: DeckCardLists,
        ): Boolean {
            updateTableId = tableId
            updateDeck = deck
            return boolReturn
        }

        override fun leaveTable(
            roomId: UUID,
            tableId: UUID,
        ): Boolean {
            leaveRoom = roomId
            leaveTableId = tableId
            return boolReturn
        }

        override fun removeTable(
            roomId: UUID,
            tableId: UUID,
        ): Boolean {
            removeRoom = roomId
            removeTableId = tableId
            return boolReturn
        }

        override fun startMatch(
            roomId: UUID,
            tableId: UUID,
        ): Boolean {
            startRoom = roomId
            startTableId = tableId
            return boolReturn
        }

        override fun watchTable(
            roomId: UUID,
            tableId: UUID,
        ): Boolean {
            watchRoom = roomId
            watchTableId = tableId
            return boolReturn
        }
    }

    @Test
    fun `createTable dispatches mapped MatchOptions and maps a null view to a CREATE failure`() {
        val session = FakeSession(createReturn = null)
        val options =
            CreateTableOptions(name = "Duel Night", gameType = "Two Player Duel", deckType = "Constructed - Standard")

        val result = TableRelay.createTable(session, room, options)

        assertEquals(room, session.createRoom)
        assertEquals("Duel Night", session.createOptions?.name)
        assertEquals("Two Player Duel", session.createOptions?.gameType)
        // A null TableView (server declined) maps to a typed CREATE failure, never a silent drop.
        assertTrue(result is TableActionResult)
        assertEquals(TableActionCode.CREATE, (result as TableActionResult).action)
        assertFalse(result.ok)
        assertTrue(result.reason != null)
    }

    @Test
    fun `createdMessage wraps a mapped table as TableCreated and a null as a CREATE failure`() {
        val summary =
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
                isRated = false,
                isPassworded = false,
                isLimited = false,
                skillLevel = SkillLevelCode.CASUAL,
                createdAtEpochMs = 0L,
            )

        assertEquals(TableCreated(table = summary), TableRelay.createdMessage(summary))

        val failure = TableRelay.createdMessage(null)
        assertTrue(failure is TableActionResult)
        assertEquals(TableActionCode.CREATE, (failure as TableActionResult).action)
        assertFalse(failure.ok)
    }

    @Test
    fun `joinTable dispatches every mapped argument and maps true to an ok JOIN result`() {
        val session = FakeSession(boolReturn = true)

        val result =
            TableRelay.joinTable(
                session,
                room,
                table,
                seatName = "alice",
                deck = deck,
                playerType = SeatPlayerTypeCode.COMPUTER_MONTE_CARLO,
                skill = SkillLevelCode.SERIOUS,
                password = "hunter2",
            )

        assertEquals(room, session.joinRoom)
        assertEquals(table, session.joinTableId)
        assertEquals("alice", session.joinName)
        assertEquals(PlayerType.COMPUTER_MONTE_CARLO, session.joinPlayerType)
        assertEquals(3, session.joinSkill)
        assertEquals("hunter2", session.joinPassword)
        // The deck was mapped to a DeckCardLists at the boundary.
        assertEquals("Mono-U", session.joinDeck?.name)
        assertEquals(1, session.joinDeck?.cards?.size)
        assertEquals(
            "123",
            session.joinDeck
                ?.cards
                ?.get(0)
                ?.cardNumber,
        )
        assertEquals(TableActionResult(action = TableActionCode.JOIN, ok = true), result)
    }

    @Test
    fun `a false from a boolean verb maps to a typed failure result`() {
        val session = FakeSession(boolReturn = false)

        val result = TableRelay.startMatch(session, room, table)

        assertEquals(room, session.startRoom)
        assertEquals(table, session.startTableId)
        assertFalse(result.ok)
        assertEquals(TableActionCode.START_MATCH, result.action)
        assertTrue(result.reason != null)
    }

    @Test
    fun `submit, update, leave, remove and watch each dispatch to the matching verb and map ok`() {
        val session = FakeSession(boolReturn = true)

        assertEquals(
            TableActionResult(TableActionCode.SUBMIT_DECK, ok = true),
            TableRelay.submitDeck(session, table, deck),
        )
        assertEquals(table, session.submitTableId)
        assertEquals("Mono-U", session.submitDeck?.name)

        assertEquals(
            TableActionResult(TableActionCode.UPDATE_DECK, ok = true),
            TableRelay.updateDeck(session, table, deck),
        )
        assertEquals(table, session.updateTableId)

        assertEquals(TableActionResult(TableActionCode.LEAVE, ok = true), TableRelay.leaveTable(session, room, table))
        assertEquals(room, session.leaveRoom)
        assertEquals(table, session.leaveTableId)

        assertEquals(TableActionResult(TableActionCode.REMOVE, ok = true), TableRelay.removeTable(session, room, table))
        assertEquals(room, session.removeRoom)
        assertEquals(table, session.removeTableId)

        assertEquals(TableActionResult(TableActionCode.WATCH, ok = true), TableRelay.watchTable(session, room, table))
        assertEquals(room, session.watchRoom)
        assertEquals(table, session.watchTableId)
    }

    @Test
    fun `detailMessage replies the mapped detail on a hit and a typed not-found on a miss`() {
        val detail =
            TableDetail(
                table =
                    TableSummary(
                        tableId = table.toString(),
                        name = "Duel Night",
                        controllerName = "alice",
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
                        TableSeatSummary(index = 0, playerName = "alice", playerType = SeatPlayerTypeCode.HUMAN, occupied = true),
                        TableSeatSummary(
                            index = 1,
                            playerName = "Computer",
                            playerType = SeatPlayerTypeCode.COMPUTER_MAD,
                            occupied = true,
                        ),
                    ),
            )

        // A hit is the detail itself — seats included, not reduced to counts.
        assertEquals(detail, TableRelay.detailMessage(table.toString(), detail))

        // A miss is a *typed* not-found, never an empty TableDetail (which would be indistinguishable
        // from a real table with no seats — exactly the ambiguity story 0040 exists to remove).
        val miss = TableRelay.detailMessage(table.toString(), null)
        assertTrue(miss is TableNotFound, "a missing table must reply TableNotFound, got $miss")
        assertEquals(table.toString(), (miss as TableNotFound).tableId)
        assertEquals(TableRelay.TABLE_NOT_FOUND, miss.reason)
    }

    @Test
    fun `firstWithId picks the room's matching table and yields null when it is absent`() {
        // The `getTables(roomId)` filter, exercised over stand-ins because a TableView cannot be built.
        val other = UUID.randomUUID()
        val candidates = listOf(other to "other table", table to "our table")

        assertEquals("our table", TableRelay.firstWithId(candidates, table) { it.first }?.second)
        assertNull(TableRelay.firstWithId(candidates, UUID.randomUUID()) { it.first })
        assertNull(TableRelay.firstWithId(emptyList<Pair<UUID, String>>(), table) { it.first })
    }

    @Test
    fun `resultOf yields an ok result with no reason and a failure with a reason`() {
        val ok = TableRelay.resultOf(TableActionCode.JOIN, ok = true)
        assertTrue(ok.ok)
        assertNull(ok.reason)

        val failed = TableRelay.resultOf(TableActionCode.JOIN, ok = false)
        assertFalse(failed.ok)
        assertTrue(failed.reason != null)
    }
}
