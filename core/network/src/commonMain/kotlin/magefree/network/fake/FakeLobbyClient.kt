package magefree.network.fake

import kotlinx.coroutines.CompletableDeferred
import magefree.model.GameType
import magefree.model.LobbyTable
import magefree.model.RoomUser
import magefree.network.LobbyClient

/**
 * A scriptable [LobbyClient] test double for hermetic `LobbyRepository` tests — no bridge,
 * no `:protocol`. Each fetch returns its configured list, or throws [error] when one is set (to drive
 * the repository's error-as-state path). The result lists and [error] are mutable so a single instance
 * can be reconfigured between a load and a refresh within one test.
 *
 * An optional [gate] lets a test hold every fetch suspended until it completes the deferred — so the
 * repository's intermediate `Loading`/`Refreshing` state is observable before the fetch resolves
 * (a conflated `StateFlow` would otherwise skip it). `null` (the default) means fetches return at once.
 */
class FakeLobbyClient(
    var tables: List<LobbyTable> = emptyList(),
    var roomUsers: List<RoomUser> = emptyList(),
    var gameTypes: List<GameType> = emptyList(),
    var error: Throwable? = null,
    var gate: CompletableDeferred<Unit>? = null,
) : LobbyClient {
    override suspend fun tables(): List<LobbyTable> {
        gate?.await()
        return error?.let { throw it } ?: tables
    }

    override suspend fun roomUsers(): List<RoomUser> {
        gate?.await()
        return error?.let { throw it } ?: roomUsers
    }

    override suspend fun gameTypes(): List<GameType> {
        gate?.await()
        return error?.let { throw it } ?: gameTypes
    }
}
