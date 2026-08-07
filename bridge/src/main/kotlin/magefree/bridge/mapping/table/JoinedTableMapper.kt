package magefree.bridge.mapping.table

import mage.view.TableClientMessage
import magefree.protocol.TableUpdated

/**
 * Maps an upstream `JOINED_TABLE` callback's [mage.view.TableClientMessage] to the app-schema
 * [TableUpdated] event. One of the per-callback table-lifecycle mappers (siblings of the 0006
 * `ChatMessageMapper`) that make up the **single coupling surface**: `mage.view.TableClientMessage` is
 * read only here (and its sibling table mappers). Pure and deterministic.
 *
 * The desktop client's `JOINED_TABLE` handler is `joinedTable(roomId, currentTableId, flag)`; the flag
 * is the owner flag. Field mapping:
 * - [TableUpdated.tableId] ← [TableClientMessage.getCurrentTableId].
 * - [TableUpdated.roomId] ← [TableClientMessage.getRoomId].
 * - [TableUpdated.isOwner] ← [TableClientMessage.getFlag].
 */
public object JoinedTableMapper {
    /** Maps [message] to its app-schema [TableUpdated]. */
    public fun map(message: TableClientMessage): TableUpdated =
        TableUpdated(
            tableId = message.currentTableId?.toString() ?: "",
            roomId = message.roomId?.toString(),
            isOwner = message.flag,
        )
}
