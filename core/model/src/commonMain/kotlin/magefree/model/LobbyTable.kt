package magefree.model

/**
 * One open/active table in the lobby, as the game browser shows it — the app-side domain projection of
 * the bridge's `TableSummary` (story 0028). Pure `:core:model`: it carries **no** `:protocol`/`mage.view.*`
 * wire type, so everything above `:core:network` speaks only this shape. `:core:network`'s mapper
 * boundary is the single place a wire summary is translated into a [LobbyTable].
 *
 * @property id the stable upstream table id (a UUID string) — the identity a later join/watch targets.
 * @property name the table's display name.
 * @property host the controller/host's name (any joined opponents are already appended upstream).
 * @property gameType the game/format label (e.g. `"Two Player Duel"`).
 * @property deckType the deck/construction label (e.g. `"Constructed - Standard"`).
 * @property state the table's lifecycle [TableState].
 * @property seatsFilled how many seats are occupied.
 * @property seatsTotal how many seats the table has.
 * @property isTournament whether this is a tournament (vs. a single match).
 * @property isRated whether the match/tournament is rated.
 * @property isPassworded whether joining requires a password.
 * @property isLimited whether this is a limited (draft/sealed) format.
 * @property skillLevel the advertised [SkillLevel].
 * @property createdAtEpochMs the table's create/start time in epoch milliseconds (0 if unavailable).
 */
data class LobbyTable(
    val id: String,
    val name: String,
    val host: String,
    val gameType: String,
    val deckType: String,
    val state: TableState,
    val seatsFilled: Int,
    val seatsTotal: Int,
    val isTournament: Boolean,
    val isRated: Boolean,
    val isPassworded: Boolean,
    val isLimited: Boolean,
    val skillLevel: SkillLevel,
    val createdAtEpochMs: Long,
)

/**
 * The lifecycle state of a lobby table. Mirrors the wire `TableStateCode` (itself mirroring
 * `mage.constants.TableState`); a wire state this build does not recognise maps to [Unknown] so the app
 * degrades gracefully rather than dropping the table.
 */
enum class TableState {
    /** Open and waiting for players to sit down. */
    Waiting,

    /** All seats filled; ready to start. */
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

    /** A wire state this build does not recognise (forward-compat fallback). */
    Unknown,
}

/**
 * The advertised skill level of a table. Mirrors the wire `SkillLevelCode` (`mage.constants.SkillLevel`);
 * an unrecognised level maps to [Unknown].
 */
enum class SkillLevel {
    /** Beginner-friendly. */
    Beginner,

    /** Casual play. */
    Casual,

    /** Serious/competitive play. */
    Serious,

    /** A wire level this build does not recognise (forward-compat fallback). */
    Unknown,
}
