package magefree.bridge.mapping

import mage.constants.ManaType
import mage.constants.PlayerAction
import mage.remote.SessionImpl
import magefree.bridge.xmage.BridgeMageClient
import magefree.protocol.GameActionCode
import magefree.protocol.GameActionResult
import magefree.protocol.GameFailureCode
import magefree.protocol.ManaTypeCode
import magefree.protocol.PlayerActionCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Hermetic dispatch tests for [GameRelay]. The wrapped `SessionImpl` is subclassed by [FakeSession] (its
 * constructor takes only a `MageClient`, and the verbs are non-final) so each records the arguments it
 * was dispatched with — including the `mage.*` values built at the mapper boundary (`ManaType`,
 * `PlayerAction`) — and returns a scripted result.
 *
 * What this pins down is the thing a hermetic test *can* pin down for a relay: that each request reaches
 * the **right** upstream verb with correctly mapped arguments, and that a `false` becomes a typed
 * failure rather than a silent drop. Whether the real server accepts those arguments is the live IT's
 * job — that division is why both exist.
 */
class GameRelayTest {
    private val gameId = UUID.randomUUID()

    /** A [SessionImpl] whose game verbs record their arguments and return a scripted flag. */
    private class FakeSession(
        var verbReturn: Boolean = true,
    ) : SessionImpl(BridgeMageClient()) {
        var joinedGame: UUID? = null
        var watchedGame: UUID? = null
        var quitGame: UUID? = null
        var stoppedWatching: UUID? = null
        var uuidGame: UUID? = null
        var uuidValue: UUID? = null
        var booleanGame: UUID? = null
        var booleanValue: Boolean? = null
        var integerGame: UUID? = null
        var integerValue: Int? = null
        var stringGame: UUID? = null
        var stringValue: String? = null
        var manaGame: UUID? = null
        var manaPlayer: UUID? = null
        var manaType: ManaType? = null
        var actionGame: UUID? = null
        var action: PlayerAction? = null
        var actionData: Any? = null

        override fun joinGame(gameId: UUID): Boolean {
            joinedGame = gameId
            return verbReturn
        }

        override fun watchGame(gameId: UUID): Boolean {
            watchedGame = gameId
            return verbReturn
        }

        override fun quitMatch(gameId: UUID): Boolean {
            quitGame = gameId
            return verbReturn
        }

        override fun stopWatching(gameId: UUID): Boolean {
            stoppedWatching = gameId
            return verbReturn
        }

        override fun sendPlayerUUID(
            gameId: UUID,
            data: UUID,
        ): Boolean {
            uuidGame = gameId
            uuidValue = data
            return verbReturn
        }

        override fun sendPlayerBoolean(
            gameId: UUID,
            data: Boolean,
        ): Boolean {
            booleanGame = gameId
            booleanValue = data
            return verbReturn
        }

        override fun sendPlayerInteger(
            gameId: UUID,
            data: Int,
        ): Boolean {
            integerGame = gameId
            integerValue = data
            return verbReturn
        }

        override fun sendPlayerString(
            gameId: UUID,
            data: String,
        ): Boolean {
            stringGame = gameId
            stringValue = data
            return verbReturn
        }

        override fun sendPlayerManaType(
            gameId: UUID,
            playerId: UUID,
            data: ManaType,
        ): Boolean {
            manaGame = gameId
            manaPlayer = playerId
            manaType = data
            return verbReturn
        }

        override fun sendPlayerAction(
            playerAction: PlayerAction,
            gameId: UUID,
            data: Any?,
        ): Boolean {
            action = playerAction
            actionGame = gameId
            actionData = data
            return verbReturn
        }
    }

    @Test
    fun `the lifecycle verbs each reach their own SessionImpl method`() {
        val session = FakeSession()

        assertEquals(ok(GameActionCode.JOIN_GAME), GameRelay.joinGame(session, gameId))
        assertEquals(ok(GameActionCode.WATCH_GAME), GameRelay.watchGame(session, gameId))
        assertEquals(ok(GameActionCode.QUIT_MATCH), GameRelay.quitMatch(session, gameId))
        assertEquals(ok(GameActionCode.STOP_WATCHING), GameRelay.stopWatching(session, gameId))

        assertEquals(gameId, session.joinedGame)
        assertEquals(gameId, session.watchedGame)
        assertEquals(gameId, session.quitGame)
        assertEquals(gameId, session.stoppedWatching)
    }

    @Test
    fun `each sendPlayerX answer reaches its own typed SessionImpl method`() {
        val session = FakeSession()
        val objectId = UUID.randomUUID()
        val playerId = UUID.randomUUID()

        assertEquals(ok(GameActionCode.SEND_UUID), GameRelay.sendPlayerUuid(session, gameId, objectId))
        assertEquals(ok(GameActionCode.SEND_BOOLEAN), GameRelay.sendPlayerBoolean(session, gameId, false))
        assertEquals(ok(GameActionCode.SEND_INTEGER), GameRelay.sendPlayerInteger(session, gameId, 3))
        assertEquals(ok(GameActionCode.SEND_STRING), GameRelay.sendPlayerString(session, gameId, "1,2,0"))
        assertEquals(
            ok(GameActionCode.SEND_MANA_TYPE),
            GameRelay.sendPlayerManaType(session, gameId, playerId, ManaTypeCode.GREEN),
        )

        assertEquals(objectId, session.uuidValue)
        assertEquals(false, session.booleanValue, "a pass is sendPlayerBoolean(false), so `false` must survive")
        assertEquals(3, session.integerValue)
        assertEquals("1,2,0", session.stringValue)
        assertEquals(playerId, session.manaPlayer, "the mana pool's owner is a separate argument upstream")
        assertEquals(ManaType.GREEN, session.manaType)
        listOf(session.uuidGame, session.booleanGame, session.integerGame, session.stringGame, session.manaGame)
            .forEach { assertEquals(gameId, it, "every answer must be addressed to the game it answers") }
    }

    @Test
    fun `a player action reaches sendPlayerAction with the mapped PlayerAction and its data`() {
        val session = FakeSession()

        val result = GameRelay.sendPlayerAction(session, gameId, PlayerActionCode.ROLLBACK_TURNS, data = 2)

        assertEquals(ok(GameActionCode.PLAYER_ACTION), result)
        assertEquals(PlayerAction.ROLLBACK_TURNS, session.action)
        assertEquals(gameId, session.actionGame)
        assertEquals(2, session.actionData, "ROLLBACK_TURNS carries its turn count in upstream's opaque data")
    }

    @Test
    fun `a player action with no data passes null through`() {
        val session = FakeSession()

        GameRelay.sendPlayerAction(session, gameId, PlayerActionCode.CONCEDE)

        assertEquals(PlayerAction.CONCEDE, session.action)
        assertNull(session.actionData)
    }

    @Test
    fun `a declined verb becomes a typed REFUSED failure, never a silent drop`() {
        val session = FakeSession(verbReturn = false)

        val result = GameRelay.joinGame(session, gameId)

        assertEquals(GameActionCode.JOIN_GAME, result.action, "the failure must say which request it answers")
        assertFalse(result.ok)
        assertEquals(GameFailureCode.REFUSED, result.failure)
        assertNotNull(result.reason, "a declined request carries a reason for the human")
    }

    @Test
    fun `every app-schema mana type maps to a distinct upstream ManaType`() {
        val mapped = ManaTypeCode.entries.associateWith { GameRelay.manaTypeOf(it) }

        assertEquals(ManaTypeCode.entries.size, mapped.values.toSet().size, "the mapping must be injective, got $mapped")
        mapped.forEach { (code, manaType) ->
            assertEquals(code.name, manaType.name, "ManaTypeCode.$code should map to the same-named upstream type")
        }
    }

    @Test
    fun `every app-schema player action maps to a distinct upstream PlayerAction`() {
        val mapped = PlayerActionCode.entries.associateWith { GameRelay.playerActionOf(it) }

        assertEquals(
            PlayerActionCode.entries.size,
            mapped.values.toSet().size,
            "two codes mapping to one upstream action would silently misroute one of them, got $mapped",
        )
        mapped.forEach { (code, action) ->
            assertEquals(code.name, action.name, "PlayerActionCode.$code should map to the same-named upstream action")
        }
    }

    @Test
    fun `the player action set stays inside upstream's in-game verbs`() {
        // Tournament/draft/client-lifecycle PlayerActions are out of scope; if one ever leaks into the
        // protocol enum this catches it before it reaches the wire.
        val excluded = listOf("CLIENT_", "TOGGLE_RECORD_MACRO")

        assertTrue(
            PlayerActionCode.entries.none { code -> excluded.any { code.name.startsWith(it) } },
            "the in-game player-action set must not include client/tournament verbs: ${PlayerActionCode.entries}",
        )
    }

    private fun ok(action: GameActionCode): GameActionResult = GameActionResult(action = action, ok = true)
}
