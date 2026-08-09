package magefree.network.table

import magefree.model.SkillLevel

/**
 * The app-schema, UI-free projection of a single table's evolving state (story 0037, corrected by
 * 0040). Two sources feed it:
 *
 * - **Server-pushed lifecycle events** ([magefree.protocol.TableUpdated],
 *   [magefree.protocol.ConstructPrompt], [magefree.protocol.SideboardPrompt],
 *   [magefree.protocol.MatchStarting]) folded by [TableEventFold] — they move [phase] and set
 *   [matchStarting].
 * - **A targeted read** of the table ([TableClient.refreshTable] → [TableDetails]) — it fills [seats]
 *   and [serverState]. XMage pushes **no** per-seat event before match-start (see
 *   [magefree.protocol.SeatUpdated]'s note), so seats are *read*, never invented from a phantom push.
 *
 * It carries **no** `:protocol`/`mage.*` type, so a `:feature`/`:app` consumer (0038) renders it
 * without seeing a wire shape. It deliberately stops at [TablePhase.Starting] + a one-shot
 * [matchStarting]; in-game state is Epic 11's.
 *
 * @property tableId the table this state describes (the fold filters pushes by it).
 * @property optionsSummary a short human summary of the table's format/options (e.g. the game type),
 *   when known from the seed; `null` until seeded with one.
 * @property isOwner whether this device hosts the table (set from [magefree.protocol.TableUpdated]).
 * @property seats the table's seats in server order, from the last [TableDetails] read (empty until the
 *   first read lands).
 * @property serverState the **server's own** view of the table's lifecycle — the single source of truth
 *   for readiness (see [isReadyToStart]); [TableServerState.Unknown] until the first read lands.
 * @property phase where the table is in the client-observed join→construct→start lifecycle.
 * @property matchStarting the one-shot game-start signal once the server pushes it (the boundary to
 *   Epic 11), else `null`.
 */
data class TableState(
    val tableId: String,
    val optionsSummary: String? = null,
    val isOwner: Boolean = false,
    val seats: List<Seat> = emptyList(),
    val serverState: TableServerState = TableServerState.Unknown,
    val phase: TablePhase = TablePhase.Waiting,
    val matchStarting: MatchStarting? = null,
) {
    /**
     * Whether the **server** says the table is ready to start — every seat filled
     * (`mage.constants.TableState.READY_TO_START`, which is exactly the gate `startMatch` enforces
     * upstream). This is the only readiness rule the client honours: there is no per-seat "ready" flag
     * anywhere upstream, so inventing one client-side produced a control that could never enable
     * (the defect story 0040 fixes).
     */
    val isReadyToStart: Boolean get() = serverState == TableServerState.ReadyToStart

    /** Apply a [TableDetails] read: refresh the seats and the server's lifecycle state. */
    fun withDetails(details: TableDetails): TableState = copy(seats = details.seats, serverState = details.serverState)
}

/**
 * One seat at a [TableState] — the app-schema projection of `mage.view.SeatView` (story 0040).
 *
 * It carries only what a seat *is* on the server: its slot, who sits there, of what kind. There is no
 * per-seat "ready" or "deck submitted" flag because upstream exposes neither before match-start;
 * readiness is table-level ([TableState.isReadyToStart]).
 *
 * @property index the seat's slot in the table's seat list (0-based, server order) — the stable key.
 * @property playerId the seat's server player id when a push reveals it (see
 *   [magefree.protocol.SeatUpdated]); `null` for a seat known only from a [TableDetails] read, which
 *   does not carry ids.
 * @property name the occupant's display name, or `null` for an empty seat.
 * @property playerType the kind of occupant the seat holds (or is reserved for): human vs. an AI.
 * @property isOccupied whether a player currently sits here.
 * @property isOwner whether this seat hosts the table (only a [magefree.protocol.SeatUpdated] push
 *   carries this; `false` for a seat known from a read).
 */
data class Seat(
    val index: Int = 0,
    val playerId: String? = null,
    val name: String? = null,
    val playerType: SeatPlayerType = SeatPlayerType.Human,
    val isOccupied: Boolean = false,
    val isOwner: Boolean = false,
)

/**
 * The **server's** lifecycle state for a table — the app-schema mirror of 0027's wire `TableStateCode`
 * (itself `mage.constants.TableState`). Distinct from [TablePhase]: [TablePhase] is what *this client*
 * has observed via pushes, whereas this is what the server reports when the table is read.
 * [ReadyToStart] is the readiness truth the host's start control is gated on.
 */
enum class TableServerState {
    /** Open and waiting for players to sit down. */
    Waiting,

    /** Every seat is filled — the server will accept a start. */
    ReadyToStart,

    /** In the process of starting. */
    Starting,

    /** A draft is under way. */
    Drafting,

    /** Deck construction (post-draft / sealed) is under way. */
    Constructing,

    /** A game/match is in progress. */
    Dueling,

    /** Sideboarding between games. */
    Sideboarding,

    /** The table's match/tournament has finished. */
    Finished,

    /** Not read yet, or a server state this build does not recognise. */
    Unknown,
}

/**
 * A one-shot snapshot of a table as the **server** currently reports it — the app-schema result of
 * [TableClient.refreshTable] (story 0040), projected from the wire `TableDetail`. This is where seats
 * come from: upstream has no pre-match seat push, so the room reads the table instead.
 *
 * @property tableId the table read.
 * @property name the table's display name.
 * @property gameType the game/format label.
 * @property deckType the deck/construction type label.
 * @property serverState the server's lifecycle state (readiness lives here, not per seat).
 * @property seats the table's seats in server order — filled and empty alike.
 */
data class TableDetails(
    val tableId: String,
    val name: String = "",
    val gameType: String = "",
    val deckType: String = "",
    val serverState: TableServerState = TableServerState.Unknown,
    val seats: List<Seat> = emptyList(),
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

/**
 * The typed failure [TableClient.refreshTable] surfaces when the server does not list [tableId] any
 * more — it was removed, or the match moved past the lobby's table list (story 0040). Distinct from a
 * [TableActionFailure] so a caller can tell "the table is gone" from "the action was declined", and
 * distinct from an *empty* success, which would look like a real table with no seats.
 */
class TableNotFoundFailure(
    val tableId: String,
    val reason: String? = null,
) : Exception(reason ?: "table $tableId not found")
