package magefree.model

/**
 * One user present in the lobby room — the app-side domain projection of the bridge's `RoomUserSummary`
 *. Pure `:core:model`, wire-agnostic: mapped from the wire shape at `:core:network`'s
 * boundary. The status strings ([matchHistory]/[games]/[ping]) are pre-formatted by the server and shown
 * verbatim; the app does not parse them.
 *
 * @property name the user's name.
 * @property flag the user's flag/country name (empty if none).
 * @property matchHistory the server-formatted match win/loss history string.
 * @property games the server-formatted "in games" info string.
 * @property ping the server-formatted ping/latency info string.
 * @property generalRating the user's general rating.
 */
data class RoomUser(
    val name: String,
    val flag: String,
    val matchHistory: String,
    val games: String,
    val ping: String,
    val generalRating: Int,
)
