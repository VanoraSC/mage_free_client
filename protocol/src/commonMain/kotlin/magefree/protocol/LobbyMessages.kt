package magefree.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Lobby browse request/response messages layered onto the envelope. These
 * extend the sealed ClientMessage/ServerMessage hierarchies — they do not fork them — so the additive,
 * forward-compatible versioning rules of ProtocolVersion continue to hold: an older peer that does not
 * know a `type` tolerates it via the polymorphic default deserializer (see ProtocolJson).
 *
 * Browsing the XMage lobby is **poll/request-response**, not a server push: the app asks for the open
 * tables, the room's users, and the available game types, and the bridge replies with the correlated
 * list. Each request carries a `requestId` echoed onto its reply (the `GetServerInfo`→`ServerInfo`
 * shape). The carried summaries are the app-schema projection of the browse-relevant
 * `mage.view.*` fields, produced at the single mapper boundary (`magefree.bridge.mapping`) so no
 * upstream shape crosses the wire. this is **read-only**: join/create/watch are the table layer.
 */

/**
 * App→bridge: request the open/active tables in the pinned server's main lobby room. The reply is a
 * [TableList] carrying the same [requestId] for correlation.
 */
@Serializable
@SerialName("get_tables")
public data class GetTables(
    val requestId: String? = null,
) : ClientMessage

/**
 * App→bridge: request the users currently in the pinned server's main lobby room. The reply is a
 * [RoomUserList] carrying the same [requestId] for correlation.
 */
@Serializable
@SerialName("get_room_users")
public data class GetRoomUsers(
    val requestId: String? = null,
) : ClientMessage

/**
 * App→bridge: request the game formats (types) the pinned server offers. The reply is a
 * [GameTypeList] carrying the same [requestId] for correlation.
 */
@Serializable
@SerialName("get_game_types")
public data class GetGameTypes(
    val requestId: String? = null,
) : ClientMessage

/**
 * Bridge→app: the reply to a [GetTables]. [tables] is the mapped, browse-relevant projection of the
 * upstream open tables (empty when there are none, or when no session is connected/bound — never an
 * error). [requestId] echoes the request's id.
 */
@Serializable
@SerialName("table_list")
public data class TableList(
    val tables: List<TableSummary>,
    val requestId: String? = null,
) : ServerMessage

/**
 * Bridge→app: the reply to a [GetRoomUsers]. [users] is the mapped list of users in the main room
 * (empty when the room is empty or no session is bound). [requestId] echoes the request's id.
 */
@Serializable
@SerialName("room_user_list")
public data class RoomUserList(
    val users: List<RoomUserSummary>,
    val requestId: String? = null,
) : ServerMessage

/**
 * Bridge→app: the reply to a [GetGameTypes]. [gameTypes] is the mapped list of formats the server
 * offers (empty only when no session is bound). [requestId] echoes the request's id.
 */
@Serializable
@SerialName("game_type_list")
public data class GameTypeList(
    val gameTypes: List<GameTypeSummary>,
    val requestId: String? = null,
) : ServerMessage

/**
 * The app-schema summary of one open table — the "settings at a glance" the lobby browser shows.
 * Projected from `mage.view.TableView` at the mapper boundary; only the browse-relevant fields cross
 * the wire (the rich per-table `additionalInfo*` HTML the desktop client renders is not carried).
 *
 * @property tableId the upstream table id (a UUID string) — the stable identity a later join targets.
 * @property name the table's display name.
 * @property controllerName the host/controller's name (with any joined opponents appended upstream).
 * @property gameType the game/format label (e.g. `"Two Player Duel"`).
 * @property deckType the deck/construction type label (e.g. `"Constructed - Standard"`).
 * @property state the table's lifecycle state (see [TableStateCode]).
 * @property seatsFilled how many seats are occupied by a player.
 * @property seatsTotal how many seats the table has.
 * @property isTournament whether this is a tournament (vs. a match).
 * @property isRated whether the match/tournament is rated.
 * @property isPassworded whether joining requires a password.
 * @property isLimited whether this is a limited (draft/sealed) format.
 * @property skillLevel the advertised skill level (see [SkillLevelCode]).
 * @property createdAtEpochMs the table's create/start time in epoch milliseconds (0 if unavailable).
 */
@Serializable
public data class TableSummary(
    val tableId: String,
    val name: String,
    val controllerName: String,
    val gameType: String,
    val deckType: String,
    val state: TableStateCode,
    val seatsFilled: Int,
    val seatsTotal: Int,
    val isTournament: Boolean,
    val isRated: Boolean,
    val isPassworded: Boolean,
    val isLimited: Boolean,
    val skillLevel: SkillLevelCode,
    val createdAtEpochMs: Long,
)

/**
 * The closed set of table lifecycle states, mirroring `mage.constants.TableState`. New states may be
 * **added** within a protocol major (additive-only); the app must treat an unrecognised state
 * defensively (log and continue).
 */
public enum class TableStateCode {
    /** Open and waiting for players to sit down. */
    WAITING,

    /** All seats filled; ready to start. */
    READY_TO_START,

    /** In the process of starting. */
    STARTING,

    /** A draft is under way. */
    DRAFTING,

    /** Deck construction (post-draft / sealed) is under way. */
    CONSTRUCTING,

    /** A game/match is in progress. */
    DUELING,

    /** Sideboarding between games. */
    SIDEBOARDING,

    /** The table's match/tournament has finished. */
    FINISHED,

    /** An upstream state this build does not recognise (forward-compat fallback). */
    UNKNOWN,
}

/**
 * The advertised skill level of a table, mirroring `mage.constants.SkillLevel`. Additive-only; the app
 * treats an unrecognised level as [UNKNOWN].
 */
public enum class SkillLevelCode {
    /** Beginner-friendly. */
    BEGINNER,

    /** Casual play. */
    CASUAL,

    /** Serious/competitive play. */
    SERIOUS,

    /** An upstream level this build does not recognise (forward-compat fallback). */
    UNKNOWN,
}

/**
 * The app-schema summary of one user present in the lobby room. Projected from `mage.view.UsersView`
 * (carried inside a `mage.view.RoomUsersView`) at the mapper boundary.
 *
 * @property name the user's name.
 * @property flag the user's flag/country name (empty if none).
 * @property matchHistory the user's match win/loss history string as the server formats it.
 * @property games the server-formatted "in games" info string.
 * @property ping the server-formatted ping/latency info string.
 * @property generalRating the user's general rating.
 */
@Serializable
public data class RoomUserSummary(
    val name: String,
    val flag: String,
    val matchHistory: String,
    val games: String,
    val ping: String,
    val generalRating: Int,
)

/**
 * The app-schema summary of one game format (type) the server offers. Projected from
 * `mage.view.GameTypeView` at the mapper boundary.
 *
 * @property name the format's display name (e.g. `"Two Player Duel"`).
 * @property minPlayers the minimum number of players.
 * @property maxPlayers the maximum number of players.
 */
@Serializable
public data class GameTypeSummary(
    val name: String,
    val minPlayers: Int,
    val maxPlayers: Int,
)
