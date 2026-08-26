package magefree.network

import magefree.model.GameType
import magefree.model.LobbyTable
import magefree.model.RoomUser

/**
 * The app's read-only lobby access seam. Each call performs one lobby request/response over
 * the live session (via [BridgeClient.request]) and returns the mapped `:core:model` result. The
 * interface speaks **only** `:core:model`: the wire `:protocol` summaries are confined to the
 * implementation's mapper boundary and never cross this seam.
 *
 * Browsing the XMage lobby is poll/request-response (there is no server push for the table list), so
 * these are plain `suspend` fetches the `LobbyRepository` calls on refresh. They are meaningful only
 * while connected; called with no live session the implementation throws, and the repository turns that
 * into error state.
 *
 * A [FakeLobbyClient] backs hermetic tests of the repository without a bridge.
 */
interface LobbyClient {
    /** Fetch the open/active tables in the server's main lobby room. */
    suspend fun tables(): List<LobbyTable>

    /** Fetch the users currently in the main lobby room. */
    suspend fun roomUsers(): List<RoomUser>

    /** Fetch the game formats (types) the server offers. */
    suspend fun gameTypes(): List<GameType>
}
