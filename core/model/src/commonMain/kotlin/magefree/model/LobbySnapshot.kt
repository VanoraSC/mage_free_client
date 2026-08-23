package magefree.model

/**
 * The whole observable state of the lobby data layer at one instant (story 0028): the last-known
 * [tables], [roomUsers], and [gameTypes], plus the [status] of the current load/refresh and any
 * [error] detail. The `LobbyRepository` exposes this as a `StateFlow<LobbySnapshot>`; the UI (0029)
 * renders it. A failure is modelled **as state** here ([LobbyLoadState.Error] + [error]) and never
 * thrown to the caller.
 *
 * The default is the idle/empty snapshot ([LobbyLoadState.Idle], all lists empty) — the state while
 * disconnected or before the first refresh. The lobby is meaningful only while connected; on
 * disconnect the repository returns to this idle snapshot.
 *
 * @property tables the last-loaded open tables (empty when idle/empty/errored-before-first-load).
 * @property roomUsers the last-loaded lobby room users.
 * @property gameTypes the last-loaded available game formats.
 * @property status where the current load/refresh cycle stands.
 * @property error a human-readable failure detail when [status] is [LobbyLoadState.Error], else `null`.
 */
data class LobbySnapshot(
    val tables: List<LobbyTable> = emptyList(),
    val roomUsers: List<RoomUser> = emptyList(),
    val gameTypes: List<GameType> = emptyList(),
    val status: LobbyLoadState = LobbyLoadState.Idle,
    val error: String? = null,
)

/**
 * Where a lobby load/refresh cycle stands. [Idle] is the disconnected/never-loaded state; [Loading] is
 * the first fetch (no prior data); [Refreshing] is a re-fetch over already-[Loaded] data (so the UI can
 * keep showing the old list under a spinner); [Loaded] is a completed fetch; [Error] means the fetch
 * failed and [LobbySnapshot.error] carries the reason (the previous data, if any, is retained).
 */
enum class LobbyLoadState {
    /** Disconnected or never loaded — the empty/idle snapshot. */
    Idle,

    /** A first fetch is in progress (no prior data to show). */
    Loading,

    /** A re-fetch is in progress over already-loaded data. */
    Refreshing,

    /** A fetch completed; [LobbySnapshot] holds the current lists. */
    Loaded,

    /** The last fetch failed; [LobbySnapshot.error] carries the reason. */
    Error,
}
