package magefree.bridge.mapping

import mage.view.RoomUsersView
import mage.view.UsersView
import magefree.protocol.RoomUserSummary

/**
 * Maps an upstream [mage.view.RoomUsersView] to the app-schema per-user [RoomUserSummary] list. Part
 * of the **single coupling surface**: `mage.view.*` is read only here (and its sibling mappers). Pure
 * and deterministic.
 *
 * A `RoomUsersView` is the room-level snapshot: it wraps a `List<UsersView>` (the occupants) plus
 * room-wide game counts. The browse feature wants "who's in the room", so this flattens the wrapped
 * `UsersView` list to one [RoomUserSummary] per user; the room-wide counts are not carried.
 *
 * **Testability.** Both `RoomUsersView(List<UsersView>, int, int, int)` and the 11-arg `UsersView`
 * constructor are public and on the bridge classpath (`mage-common`), so this mapper is exercised with
 * **real in-memory fixtures** by `RoomUserMapperTest` (like the `ChatMessageMapperTest`).
 *
 * Per-user field mapping (see [summaryOf]):
 * - `name` ← [UsersView.getUserName]; `flag` ← [UsersView.getFlagName].
 * - `matchHistory` ← [UsersView.getMatchHistory]; `games` ← [UsersView.getInfoGames]; `ping` ← [UsersView.getInfoPing].
 * - `generalRating` ← [UsersView.getGeneralRating].
 */
public object RoomUserMapper {
    /** Maps [view]'s wrapped users to app-schema summaries (empty if the room has no users). */
    public fun map(view: RoomUsersView): List<RoomUserSummary> = view.usersView.orEmpty().map { summaryOf(it) }

    /** Projects one upstream [UsersView] to its browse-relevant [RoomUserSummary]. */
    private fun summaryOf(user: UsersView): RoomUserSummary =
        RoomUserSummary(
            name = user.userName ?: "",
            flag = user.flagName ?: "",
            matchHistory = user.matchHistory ?: "",
            games = user.infoGames ?: "",
            ping = user.infoPing ?: "",
            generalRating = user.generalRating,
        )
}
