package magefree.bridge.mapping.table

import mage.view.TableClientMessage
import magefree.protocol.ConstructPrompt

/**
 * Maps an upstream `CONSTRUCT` callback's [mage.view.TableClientMessage] to the app-schema
 * [ConstructPrompt] event (the server asking a seat to build its deck). One of the per-callback
 * table-lifecycle mappers making up the **single coupling surface**. Pure and deterministic.
 *
 * The desktop handler is `construct(deck, currentTableId, parentTableId, time)`; the deck (current pool)
 * is not carried here — the app already holds the deck it is building, and the prompt only needs the
 * table + time budget. Field mapping:
 * - [ConstructPrompt.tableId] ← [TableClientMessage.getCurrentTableId].
 * - [ConstructPrompt.parentTableId] ← [TableClientMessage.getParentTableId].
 * - [ConstructPrompt.remainingSeconds] ← [TableClientMessage.getTime].
 */
public object ConstructMapper {
    /** Maps [message] to its app-schema [ConstructPrompt]. */
    public fun map(message: TableClientMessage): ConstructPrompt =
        ConstructPrompt(
            tableId = message.currentTableId?.toString() ?: "",
            parentTableId = message.parentTableId?.toString(),
            remainingSeconds = message.time,
        )
}
