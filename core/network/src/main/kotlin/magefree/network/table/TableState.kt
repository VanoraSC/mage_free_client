package magefree.network.table

import magefree.model.SkillLevel

/**
 * The app-schema, UI-free projection of a single table's evolving state (story 0037). It is the
 * fold target of 0036's server-pushed table-lifecycle events ([magefree.protocol.TableUpdated],
 * [magefree.protocol.SeatUpdated], [magefree.protocol.ConstructPrompt],
 * [magefree.protocol.SideboardPrompt], [magefree.protocol.MatchStarting]) — see [TableEventFold] —
 * seeded from a create/join [TableRef].
 *
 * It carries **no** `:protocol`/`mage.*` type: [seats]/[phase]/[matchStarting] are all app-schema, so a
 * `:feature`/`:app` consumer (0038) renders it without seeing a wire shape. It deliberately stops at
 * [TablePhase.Starting] + a one-shot [matchStarting]; in-game state is Epic 11's.
 *
 * @property tableId the table this state describes (the fold filters pushes by it).
 * @property optionsSummary a short human summary of the table's format/options (e.g. the game type),
 *   when known from the seed; `null` until seeded with one.
 * @property isOwner whether this device hosts the table (set from [magefree.protocol.TableUpdated]).
 * @property seats the ordered seats known so far, keyed by [Seat.playerId] as the server reveals them.
 * @property phase where the table is in the join→construct→start lifecycle.
 * @property matchStarting the one-shot game-start signal once the server pushes it (the boundary to
 *   Epic 11), else `null`.
 */
data class TableState(
    val tableId: String,
    val optionsSummary: String? = null,
    val isOwner: Boolean = false,
    val seats: List<Seat> = emptyList(),
    val phase: TablePhase = TablePhase.Waiting,
    val matchStarting: MatchStarting? = null,
)

/**
 * One seat at a [TableState]. 0036's pushes reveal only what the current XMage build emits per seat
 * (an id + owner flag before match-start — see [magefree.protocol.SeatUpdated]'s note), so the richer
 * fields default and fill in as/if the server provides them.
 *
 * @property playerId the seat's server player id (the fold's upsert key), or `null` for an empty seat.
 * @property name the player's display name when known (falls back to the id).
 * @property playerType the kind of occupant (human vs. an AI), defaulting to [SeatPlayerType.Human].
 * @property isReady whether the seat has readied.
 * @property isDeckSubmitted whether the seat has submitted its (binding) deck.
 * @property isOwner whether this seat hosts the table.
 */
data class Seat(
    val playerId: String? = null,
    val name: String? = null,
    val playerType: SeatPlayerType = SeatPlayerType.Human,
    val isReady: Boolean = false,
    val isDeckSubmitted: Boolean = false,
    val isOwner: Boolean = false,
)

/** Where a [TableState] is in the join→construct→start lifecycle (app-schema; stops at the match). */
enum class TablePhase {
    /** Seats are filling; no construction or start yet. */
    Waiting,

    /** The server has asked a seat to construct/sideboard its deck. */
    Constructing,

    /** The match is starting (a [MatchStarting] signal has arrived). */
    Starting,

    /** The match has started (reserved; the in-game view is Epic 11's, past this client's boundary). */
    Started,
}

/**
 * The kind of occupant in a [Seat] (and the seat type chosen in [CreateTableOptions]) — the app-schema
 * mirror of 0036's `SeatPlayerTypeCode`, one-to-one so a create round-trips without fidelity loss.
 */
enum class SeatPlayerType {
    /** A human player. */
    Human,

    /** The Monte-Carlo AI. */
    ComputerMonteCarlo,

    /** The "mad" AI. */
    ComputerMad,

    /** The draft-bot AI. */
    ComputerDraftBot,

    /** An occupant this build does not recognise. */
    Unknown,
}

/** The multiplayer range of influence for [CreateTableOptions] — the app-schema mirror of `RangeCode`. */
enum class RangeOfInfluence {
    /** Range one. */
    One,

    /** Range two. */
    Two,

    /** Unlimited range (all players). */
    All,
}

/**
 * The app-schema description of a table to create ([TableClient.createTable]) — a `:protocol`-free mirror
 * of 0036's wire `CreateTableOptions`, mapped onto it internally so no wire type crosses the client ABI.
 * The fields mirror the create-table settings XMage's desktop client exposes.
 *
 * @property name the table's display name.
 * @property gameType the game/format label (a lobby game-type name, e.g. `"Two Player Duel"`).
 * @property deckType the deck/construction type label (e.g. `"Constructed - Standard"`).
 * @property players the full seat list including the host (its size is the seat count).
 * @property rated whether the match is rated.
 * @property winsNeeded wins to take the match (best-of-N ⇒ ⌈N/2⌉).
 * @property freeMulligans the number of free mulligans allowed.
 * @property skillLevel the advertised skill level.
 * @property range the multiplayer range of influence.
 * @property matchTimeLimitSeconds the per-player priority time budget, in seconds (0 = none).
 * @property matchBufferTimeSeconds the per-priority buffer time, in seconds (0 = none).
 * @property spectatorsAllowed whether spectators may watch.
 * @property quitRatio the maximum allowed quit ratio (percent) for joiners.
 * @property minimumRating the minimum rating required to join (0 = no minimum).
 * @property password the join password, or `null` for an open table.
 */
data class CreateTableOptions(
    val name: String,
    val gameType: String,
    val deckType: String,
    val players: List<SeatPlayerType> = listOf(SeatPlayerType.Human, SeatPlayerType.Human),
    val rated: Boolean = false,
    val winsNeeded: Int = 1,
    val freeMulligans: Int = 0,
    val skillLevel: SkillLevel = SkillLevel.Casual,
    val range: RangeOfInfluence = RangeOfInfluence.All,
    val matchTimeLimitSeconds: Int = 0,
    val matchBufferTimeSeconds: Int = 0,
    val spectatorsAllowed: Boolean = true,
    val quitRatio: Int = 100,
    val minimumRating: Int = 0,
    val password: String? = null,
)

/**
 * The one-shot game-start signal folded into a [TableState] from [magefree.protocol.MatchStarting] —
 * the **boundary to Epic 11**. It carries only the ids needed to open the game view; no in-game state.
 *
 * @property gameId the new game's id (the anchor Epic 11 opens the game with).
 * @property tableId the table the game belongs to, when the push carries it.
 * @property playerId the recipient seat's id, when the push carries it.
 */
data class MatchStarting(
    val gameId: String,
    val tableId: String? = null,
    val playerId: String? = null,
)

/**
 * The handle to a table returned by [TableClient.createTable] (and usable to seed [TableClient.observeTable]):
 * the new table's id plus the summary fields 0036's [magefree.protocol.TableCreated] carries. App-schema —
 * a projection of the wire `TableSummary`, no `:protocol` type surfaces.
 *
 * @property tableId the created table's id.
 * @property name the table's display name.
 * @property gameType the game/format label.
 * @property deckType the deck/construction type label.
 * @property seatsFilled how many seats are occupied.
 * @property seatsTotal the total seat count.
 */
data class TableRef(
    val tableId: String,
    val name: String,
    val gameType: String,
    val deckType: String,
    val seatsFilled: Int,
    val seatsTotal: Int,
) {
    /** Seed a [TableState] from this reference (the create/join starting point [TableClient.observeTable] folds onto). */
    fun toSeed(): TableState =
        TableState(
            tableId = tableId,
            optionsSummary = gameType,
            phase = TablePhase.Waiting,
        )
}

/**
 * The typed failure a [TableClient] verb surfaces when the server declines a table action — 0036's
 * [magefree.protocol.TableActionResult] with `ok = false` (or an unexpected reply). The [reason] is the
 * server's optional human-readable detail: a decline is a **typed result**, never a silent drop.
 */
class TableActionFailure(
    val reason: String?,
) : Exception(reason ?: "table action declined")
