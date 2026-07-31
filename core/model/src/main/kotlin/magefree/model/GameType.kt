package magefree.model

/**
 * One game format (type) the server offers — the app-side domain projection of the bridge's
 * `GameTypeSummary` (story 0028). Pure `:core:model`, mapped from the wire shape at `:core:network`'s
 * boundary.
 *
 * @property name the format's display name (e.g. `"Two Player Duel"`).
 * @property minPlayers the minimum number of players.
 * @property maxPlayers the maximum number of players.
 */
data class GameType(
    val name: String,
    val minPlayers: Int,
    val maxPlayers: Int,
)
