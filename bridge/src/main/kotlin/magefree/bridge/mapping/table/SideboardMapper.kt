package magefree.bridge.mapping.table

import mage.view.TableClientMessage
import magefree.protocol.SideboardPrompt

/**
 * Maps an upstream `SIDEBOARD` callback's [mage.view.TableClientMessage] to the app-schema
 * [SideboardPrompt] event (the server asking a seat to sideboard between games). One of the
 * per-callback table-lifecycle mappers making up the **single coupling surface**. Pure and
 * deterministic.
 *
 * The desktop handler branches on the flag: a set flag is a construct-style sideboard (unlimited
 * basics), a clear flag a plain sideboard — carried through as [SideboardPrompt.isConstruct]. Field
 * mapping:
 * - [SideboardPrompt.tableId] ← [TableClientMessage.getCurrentTableId].
 * - [SideboardPrompt.parentTableId] ← [TableClientMessage.getParentTableId].
 * - [SideboardPrompt.remainingSeconds] ← [TableClientMessage.getTime].
 * - [SideboardPrompt.isConstruct] ← [TableClientMessage.getFlag].
 */
public object SideboardMapper {
    /** Maps [message] to its app-schema [SideboardPrompt]. */
    public fun map(message: TableClientMessage): SideboardPrompt =
        SideboardPrompt(
            tableId = message.currentTableId?.toString() ?: "",
            parentTableId = message.parentTableId?.toString(),
            remainingSeconds = message.time,
            isConstruct = message.flag,
        )
}
