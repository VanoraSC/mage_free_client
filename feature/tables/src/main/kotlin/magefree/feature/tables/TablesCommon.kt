package magefree.feature.tables

import magefree.decks.model.DeckFormat

/**
 * The viewer's role at a table room — the lens the room UI is rendered through. A [Host] created the
 * table (start/remove); a [Player] took a seat (submit/update deck, leave); a [Spectator] is only
 * watching (read-only). It is decided at the point of entry (create / join / watch), not inferred, so
 * the action set is deterministic before the first server push arrives.
 */
enum class TableRole {
    /** Created the table: may start (when seats are ready) or remove it. */
    Host,

    /** Took a seat: may submit/update its deck and leave. */
    Player,

    /** Watching only: no seat, no actions. */
    Spectator,
}

/**
 * Best-effort resolution of a table's advertised format/deck-type label (e.g. `"Constructed - Standard"`
 * or a game-type name) to one of the bundled [DeckFormat]s, so the offline deck-legality gate (0033) can
 * run before a join. Returns `null` when no bundled format is recognised — the join then proceeds without
 * a legality gate (the XMage server stays the authority at submit-time).
 */
fun deckFormatFromLabel(vararg labels: String?): DeckFormat? {
    for (label in labels) {
        if (label.isNullOrBlank()) continue
        DeckFormat.entries.firstOrNull { label.contains(it.displayName, ignoreCase = true) }?.let { return it }
    }
    return null
}
