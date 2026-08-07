package magefree.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Table **action** request/response + server-pushed table-lifecycle event messages layered onto the
 * 0004 envelope (story 0036, Epic 7). Where 0027 gave the lobby **read** side (browse tables/users/
 * game types), this story gives the **act** side: create a table, join it (submitting a deck),
 * submit/update a deck during construction, leave/remove a table, start the match, and watch
 * (spectate); plus the inbound table/deck/game-start callbacks the server pushes so the app can follow
 * a table through to the moment a match begins.
 *
 * As with every message file, these extend the sealed ClientMessage/ServerMessage hierarchies — they
 * do not fork them — so the additive, forward-compatible versioning rules of ProtocolVersion continue
 * to hold: an older peer that does not know a `type` tolerates it via the polymorphic default
 * deserializer (see ProtocolJson). Everything here is pure Kotlin/kotlinx-serialization — the app-schema
 * projection of the upstream `mage.*` action contract, produced/consumed at the single mapper boundary
 * (`magefree.bridge.mapping`) so no `mage.*` shape (`MatchOptions`, `DeckCardLists`, `TableView`,
 * `PlayerType`) ever crosses the wire.
 *
 * **Room resolution (pinned-server posture).** Requests carry an optional [roomId]; under the pinned
 * server the app need not know it — a null [roomId] means "the pinned server's main room", which the
 * bridge resolves via `getMainRoomId()` (the same room `LobbyRelay` already reads).
 *
 * **Match-start boundary.** [MatchStarting] is the boundary to Epic 11 (in-game play): it carries the
 * game id and the ids needed to open the game, but no in-game state is modelled here.
 */

// ---------------------------------------------------------------------------------------------------
// Requests (app → bridge)
// ---------------------------------------------------------------------------------------------------

/**
 * App→bridge: create (host) a table in the pinned server's room. The bridge maps [options] onto a
 * `mage.game.match.MatchOptions`, calls `SessionImpl.createTable`, and replies with a [TableCreated]
 * carrying the new table's [TableSummary] (or a failed [TableActionResult] when the server declines).
 * A null [roomId] resolves to the main room.
 */
@Serializable
@SerialName("create_table")
public data class CreateTable(
    val options: CreateTableOptions,
    val roomId: String? = null,
    val requestId: String? = null,
) : ClientMessage

/**
 * App→bridge: join the constructed table [tableId] as [seatName], submitting [deck] at join. [password]
 * is required only for a passworded table; [playerType]/[skill] describe the seat (default a human of
 * casual skill). A null [roomId] resolves to the main room. The reply is a [TableActionResult] for the
 * `JOIN` action.
 */
@Serializable
@SerialName("join_table")
public data class JoinTable(
    val tableId: String,
    val seatName: String,
    val deck: DeckList,
    val roomId: String? = null,
    val password: String? = null,
    val playerType: SeatPlayerTypeCode = SeatPlayerTypeCode.HUMAN,
    val skill: SkillLevelCode = SkillLevelCode.CASUAL,
    val requestId: String? = null,
) : ClientMessage

/**
 * App→bridge: submit [deck] for the seat at [tableId] during the construction phase (the first, binding
 * submission). The reply is a [TableActionResult] for the `SUBMIT_DECK` action.
 */
@Serializable
@SerialName("submit_deck")
public data class SubmitDeck(
    val tableId: String,
    val deck: DeckList,
    val requestId: String? = null,
) : ClientMessage

/**
 * App→bridge: update the in-progress [deck] for the seat at [tableId] during construction (a save,
 * before the binding submit). The reply is a [TableActionResult] for the `UPDATE_DECK` action.
 */
@Serializable
@SerialName("update_deck")
public data class UpdateDeck(
    val tableId: String,
    val deck: DeckList,
    val requestId: String? = null,
) : ClientMessage

/**
 * App→bridge: leave the table [tableId] (give up your seat). A null [roomId] resolves to the main room.
 * The reply is a [TableActionResult] for the `LEAVE` action.
 */
@Serializable
@SerialName("leave_table")
public data class LeaveTable(
    val tableId: String,
    val roomId: String? = null,
    val requestId: String? = null,
) : ClientMessage

/**
 * App→bridge: remove the table [tableId] (host tears it down). A null [roomId] resolves to the main
 * room. The reply is a [TableActionResult] for the `REMOVE` action.
 */
@Serializable
@SerialName("remove_table")
public data class RemoveTable(
    val tableId: String,
    val roomId: String? = null,
    val requestId: String? = null,
) : ClientMessage

/**
 * App→bridge: start the match at table [tableId] (host readies/starts once seats are filled). A null
 * [roomId] resolves to the main room. The reply is a [TableActionResult] for the `START_MATCH` action;
 * the actual game-start signal arrives asynchronously as a pushed [MatchStarting] event.
 */
@Serializable
@SerialName("start_match")
public data class StartMatch(
    val tableId: String,
    val roomId: String? = null,
    val requestId: String? = null,
) : ClientMessage

/**
 * App→bridge: watch (spectate) the table [tableId]. A null [roomId] resolves to the main room. The
 * reply is a [TableActionResult] for the `WATCH` action.
 */
@Serializable
@SerialName("watch_table")
public data class WatchTable(
    val tableId: String,
    val roomId: String? = null,
    val requestId: String? = null,
) : ClientMessage

// ---------------------------------------------------------------------------------------------------
// Results (bridge → app, correlated by requestId)
// ---------------------------------------------------------------------------------------------------

/**
 * Bridge→app: the reply to a successful [CreateTable]. [table] is the mapped [TableSummary] of the
 * newly created table (its id + seats), projected from the upstream `mage.view.TableView` at the mapper
 * boundary. [requestId] echoes the request's id. A create that the server declines replies a failed
 * [TableActionResult] with [TableActionCode.CREATE] instead.
 */
@Serializable
@SerialName("table_created")
public data class TableCreated(
    val table: TableSummary,
    val requestId: String? = null,
) : ServerMessage

/**
 * Bridge→app: the structured result of a table action. XMage's action verbs return a bare `boolean`;
 * the bridge maps a `false` (or a null [TableView] from create) to `ok = false` with an optional
 * [reason] — a failure is a **typed result**, never a silent drop. [action] identifies which action
 * this answers; [requestId] echoes the request's id.
 */
@Serializable
@SerialName("table_action_result")
public data class TableActionResult(
    val action: TableActionCode,
    val ok: Boolean,
    val reason: String? = null,
    val requestId: String? = null,
) : ServerMessage

/**
 * The table action a [TableActionResult] answers. Additive-only within a protocol major; an app that
 * does not recognise a code must treat it defensively (log and continue).
 */
public enum class TableActionCode {
    /** A [CreateTable] the server declined (a create that succeeds replies [TableCreated] instead). */
    CREATE,

    /** A [JoinTable]. */
    JOIN,

    /** A [SubmitDeck]. */
    SUBMIT_DECK,

    /** An [UpdateDeck]. */
    UPDATE_DECK,

    /** A [LeaveTable]. */
    LEAVE,

    /** A [RemoveTable]. */
    REMOVE,

    /** A [StartMatch]. */
    START_MATCH,

    /** A [WatchTable]. */
    WATCH,
}

// ---------------------------------------------------------------------------------------------------
// Events (bridge → app, server-pushed — no requestId correlation)
// ---------------------------------------------------------------------------------------------------

/**
 * Bridge→app: the table [tableId] you are seated at changed — mapped from the upstream `JOINED_TABLE`
 * callback (you joined/created the table). [roomId] is the room the table lives in; [isOwner] is true
 * when you host it. [requestId] is unused for spontaneous pushes and left null.
 */
@Serializable
@SerialName("table_updated")
public data class TableUpdated(
    val tableId: String,
    val roomId: String? = null,
    val isOwner: Boolean = false,
    val requestId: String? = null,
) : ServerMessage

/**
 * Bridge→app: a seat's occupancy changed at a table. Reserved for a per-seat server push; the current
 * XMage build refreshes seats via the polled room table list (story 0027) and has no seat-level
 * callback before match-start, so this carries the seat's [tableId], [playerId], and [isOwner] for
 * forward use by 0037/0038. [requestId] is unused for spontaneous pushes and left null.
 */
@Serializable
@SerialName("seat_updated")
public data class SeatUpdated(
    val tableId: String,
    val playerId: String? = null,
    val isOwner: Boolean = false,
    val requestId: String? = null,
) : ServerMessage

/**
 * Bridge→app: the server asks the seat at [tableId] to **construct** its deck (post-draft / sealed) —
 * mapped from the upstream `CONSTRUCT` callback. [parentTableId] is the parent (tourney sub-table)
 * table, if any; [remainingSeconds] is the construction time budget. [requestId] is left null.
 */
@Serializable
@SerialName("construct_prompt")
public data class ConstructPrompt(
    val tableId: String,
    val parentTableId: String? = null,
    val remainingSeconds: Int = 0,
    val requestId: String? = null,
) : ServerMessage

/**
 * Bridge→app: the server asks the seat at [tableId] to **sideboard** between games — mapped from the
 * upstream `SIDEBOARD` callback. [isConstruct] mirrors the callback's flag (a construct-style sideboard
 * with unlimited basics vs. a plain sideboard). [parentTableId]/[remainingSeconds] as for
 * [ConstructPrompt]. [requestId] is left null.
 */
@Serializable
@SerialName("sideboard_prompt")
public data class SideboardPrompt(
    val tableId: String,
    val parentTableId: String? = null,
    val remainingSeconds: Int = 0,
    val isConstruct: Boolean = false,
    val requestId: String? = null,
) : ServerMessage

/**
 * Bridge→app: the game is starting — mapped from the upstream `START_GAME` callback. [gameId] is the
 * new game's id (the anchor Epic 11 will open the game view with); [tableId]/[parentTableId] identify
 * the table, and [playerId] the recipient's seat. This is the **boundary to Epic 11**: no in-game state
 * is carried. [requestId] is left null.
 */
@Serializable
@SerialName("match_starting")
public data class MatchStarting(
    val gameId: String,
    val tableId: String? = null,
    val parentTableId: String? = null,
    val playerId: String? = null,
    val requestId: String? = null,
) : ServerMessage

// ---------------------------------------------------------------------------------------------------
// App-schema payloads
// ---------------------------------------------------------------------------------------------------

/**
 * The flat, app-schema description of a table to create — the bridge maps this onto a
 * `mage.game.match.MatchOptions` (the `mage.*` construction stays server-side of the boundary). Fields
 * mirror the create-table settings XMage's desktop client exposes.
 *
 * @property name the table's display name.
 * @property gameType the game/format label (a [GameTypeSummary.name], e.g. `"Two Player Duel"`).
 * @property deckType the deck/construction type label (e.g. `"Constructed - Standard"`); a
 *   `"Limited"`-prefixed value marks the table limited.
 * @property players the full seat list including the host — one [SeatPlayerTypeCode] per seat (the host
 *   is typically the first, `HUMAN`); its size is the seat count.
 * @property rated whether the match is rated.
 * @property winsNeeded wins to take the match (best-of-N ⇒ ⌈N/2⌉).
 * @property freeMulligans the number of free mulligans allowed.
 * @property skillLevel the advertised skill level (see [SkillLevelCode]).
 * @property range the multiplayer range of influence (see [RangeCode]).
 * @property matchTimeLimitSeconds the per-player priority time budget, in seconds (0 = none).
 * @property matchBufferTimeSeconds the per-priority buffer time, in seconds (0 = none).
 * @property spectatorsAllowed whether spectators may watch.
 * @property quitRatio the maximum allowed quit ratio (percent) for joiners.
 * @property minimumRating the minimum rating required to join (0 = no minimum).
 * @property password the join password, or null for an open table.
 */
@Serializable
public data class CreateTableOptions(
    val name: String,
    val gameType: String,
    val deckType: String,
    val players: List<SeatPlayerTypeCode> = listOf(SeatPlayerTypeCode.HUMAN, SeatPlayerTypeCode.HUMAN),
    val rated: Boolean = false,
    val winsNeeded: Int = 1,
    val freeMulligans: Int = 0,
    val skillLevel: SkillLevelCode = SkillLevelCode.CASUAL,
    val range: RangeCode = RangeCode.ALL,
    val matchTimeLimitSeconds: Int = 0,
    val matchBufferTimeSeconds: Int = 0,
    val spectatorsAllowed: Boolean = true,
    val quitRatio: Int = 100,
    val minimumRating: Int = 0,
    val password: String? = null,
)

/**
 * The kind of player occupying a seat, mirroring `mage.players.PlayerType`. Additive-only; the bridge
 * maps an [UNKNOWN] (or unmapped) code defensively to a human seat.
 */
public enum class SeatPlayerTypeCode {
    /** A human player. */
    HUMAN,

    /** The Monte-Carlo AI. */
    COMPUTER_MONTE_CARLO,

    /** The "mad" AI. */
    COMPUTER_MAD,

    /** The draft-bot AI. */
    COMPUTER_DRAFT_BOT,

    /** An upstream player type this build does not recognise (forward-compat fallback). */
    UNKNOWN,
}

/**
 * The multiplayer range of influence, mirroring `mage.constants.RangeOfInfluence`. Additive-only.
 */
public enum class RangeCode {
    /** Range one. */
    ONE,

    /** Range two. */
    TWO,

    /** Unlimited range (all players). */
    ALL,
}

/**
 * The `DeckCardLists`-equivalent carried by [JoinTable]/[SubmitDeck]/[UpdateDeck]: a plain, app-schema
 * mirror of XMage's `mage.cards.decks.DeckCardLists` (`name`, `author`, `cards`, `sideboard`). It is
 * intentionally field-aligned with story 0033's device-side `magefree.decks.model.DeckList` so 0037 can
 * map a device deck straight onto a join/submit without any `mage.*` on the device; the bridge maps
 * this onto a real `DeckCardLists` at the mapper boundary (`DeckListMapper`).
 *
 * @property name the deck's display name (nullable, as XMage's is).
 * @property author the deck's author (nullable).
 * @property cards the main-deck card records.
 * @property sideboard the sideboard card records.
 */
@Serializable
public data class DeckList(
    val name: String? = null,
    val author: String? = null,
    val cards: List<DeckListCard> = emptyList(),
    val sideboard: List<DeckListCard> = emptyList(),
)

/**
 * One card record in a [DeckList], mirroring XMage's `mage.cards.decks.DeckCardInfo`. [collectorNumber]
 * corresponds to `DeckCardInfo.cardNumber`; [amount] to `DeckCardInfo.amount`. Field-aligned with story
 * 0033's `magefree.decks.model.DeckListCard`.
 *
 * @property cardName the card's name.
 * @property setCode the printing's set code.
 * @property collectorNumber the printing's collector number.
 * @property amount how many copies.
 */
@Serializable
public data class DeckListCard(
    val cardName: String,
    val setCode: String,
    val collectorNumber: String,
    val amount: Int,
)
