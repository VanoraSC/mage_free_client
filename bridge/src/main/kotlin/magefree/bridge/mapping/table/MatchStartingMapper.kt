package magefree.bridge.mapping.table

import mage.view.TableClientMessage
import magefree.protocol.MatchStarting

/**
 * Maps an upstream `START_GAME` callback's [mage.view.TableClientMessage] to the app-schema
 * [MatchStarting] event — the game-start signal and the **boundary to the game layer** (no in-game state is
 * carried). One of the per-callback table-lifecycle mappers making up the **single coupling surface**.
 * Pure and deterministic.
 *
 * The desktop handler is `gameStarted(messageId, currentTableId, parentTableId, gameId, playerId)`.
 * Field mapping:
 * - [MatchStarting.gameId] ← [TableClientMessage.getGameId].
 * - [MatchStarting.tableId] ← [TableClientMessage.getCurrentTableId].
 * - [MatchStarting.parentTableId] ← [TableClientMessage.getParentTableId].
 * - [MatchStarting.playerId] ← [TableClientMessage.getPlayerId].
 */
public object MatchStartingMapper {
    /** Maps [message] to its app-schema [MatchStarting]. */
    public fun map(message: TableClientMessage): MatchStarting =
        MatchStarting(
            gameId = message.gameId?.toString() ?: "",
            tableId = message.currentTableId?.toString(),
            parentTableId = message.parentTableId?.toString(),
            playerId = message.playerId?.toString(),
        )
}
