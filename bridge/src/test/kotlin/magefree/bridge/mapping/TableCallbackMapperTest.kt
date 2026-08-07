package magefree.bridge.mapping

import mage.interfaces.callback.ClientCallback
import mage.interfaces.callback.ClientCallbackMethod
import mage.view.TableClientMessage
import magefree.protocol.ConstructPrompt
import magefree.protocol.MatchStarting
import magefree.protocol.SideboardPrompt
import magefree.protocol.TableUpdated
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Hermetic tests for the table-lifecycle branches of [CallbackMapper] (story 0036). `TableClientMessage`
 * is a plain `mage-common` builder type, so a real compressed callback is built in memory (as
 * [ClientCallback]'s 3-arg constructor compresses the payload), run through the mapper, and asserted —
 * proving each new event maps correctly and that the mapper **never throws** on a malformed/unknown
 * callback (returns `null` instead).
 */
class TableCallbackMapperTest {
    private val room = UUID.randomUUID()
    private val table = UUID.randomUUID()
    private val parent = UUID.randomUUID()
    private val game = UUID.randomUUID()
    private val player = UUID.randomUUID()

    private fun callback(
        method: ClientCallbackMethod,
        message: TableClientMessage,
    ): ClientCallback = ClientCallback(method, table, message)

    @Test
    fun `JOINED_TABLE maps to a TableUpdated carrying room, table, and owner flag`() {
        val message = TableClientMessage().withRoom(room).withTable(table, null).withFlag(true)

        val mapped = CallbackMapper.map(callback(ClientCallbackMethod.JOINED_TABLE, message))

        assertEquals(TableUpdated(tableId = table.toString(), roomId = room.toString(), isOwner = true), mapped)
    }

    @Test
    fun `CONSTRUCT maps to a ConstructPrompt with table, parent, and time`() {
        val message = TableClientMessage().withTable(table, parent).withTime(600)

        val mapped = CallbackMapper.map(callback(ClientCallbackMethod.CONSTRUCT, message))

        assertEquals(
            ConstructPrompt(tableId = table.toString(), parentTableId = parent.toString(), remainingSeconds = 600),
            mapped,
        )
    }

    @Test
    fun `SIDEBOARD maps to a SideboardPrompt carrying the construct flag`() {
        val message = TableClientMessage().withTable(table, parent).withTime(30).withFlag(true)

        val mapped = CallbackMapper.map(callback(ClientCallbackMethod.SIDEBOARD, message))

        assertEquals(
            SideboardPrompt(
                tableId = table.toString(),
                parentTableId = parent.toString(),
                remainingSeconds = 30,
                isConstruct = true,
            ),
            mapped,
        )
    }

    @Test
    fun `START_GAME maps to a MatchStarting with the game id and seat ids`() {
        val message = TableClientMessage().withTable(table, parent).withGame(game).withPlayer(player)

        val mapped = CallbackMapper.map(callback(ClientCallbackMethod.START_GAME, message))

        assertEquals(
            MatchStarting(
                gameId = game.toString(),
                tableId = table.toString(),
                parentTableId = parent.toString(),
                playerId = player.toString(),
            ),
            mapped,
        )
    }

    @Test
    fun `a table callback with a malformed payload maps to null instead of throwing`() {
        // A JOINED_TABLE whose decompressed payload is not a TableClientMessage (upstream drift) must be
        // logged and dropped (mapped to null), never thrown — a bad push cannot crash the session.
        val malformed = ClientCallback(ClientCallbackMethod.JOINED_TABLE, table, "definitely not a TableClientMessage")

        assertNull(CallbackMapper.map(malformed))
    }

    @Test
    fun `an unmapped table-adjacent callback method maps to null`() {
        // START_TOURNAMENT also carries a TableClientMessage but is out of scope (Epic 8) — unmapped.
        val message = TableClientMessage().withTable(table, null)

        assertNull(CallbackMapper.map(callback(ClientCallbackMethod.START_TOURNAMENT, message)))
    }
}
