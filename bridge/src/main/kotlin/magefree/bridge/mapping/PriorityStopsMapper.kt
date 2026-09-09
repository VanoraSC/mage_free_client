package magefree.bridge.mapping

import mage.players.net.SkipPrioritySteps
import mage.players.net.UserData
import magefree.protocol.PriorityStops
import magefree.protocol.SetPriorityStops

/**
 * The app's stops, as upstream's own `UserSkipPrioritySteps`.
 *
 * **This is the whole feature.** `HumanPlayer.priority()` calls `checkPassStep`, which reads the
 * player's `SkipPrioritySteps` for the side whose turn it is and — when the step is not set and the
 * stack is empty — passes on the server, without ever sending the client a prompt. A stop the server
 * has not been told about is a stop that cannot happen, whatever the client does with the prompts it
 * does receive.
 *
 * The mapping is field for field, because the app-schema type was written to mirror the upstream one.
 * Nothing here decides anything: which steps a player wants is the app's question, and what the server
 * does with them is upstream's.
 *
 * **Only seven steps exist to be set.** `SkipPrioritySteps.isPhaseStepSet` answers `default: return
 * true` for every other step — declare attackers, declare blockers, combat damage, untap, cleanup — so
 * the server always gives priority there and there is nothing to map.
 */
internal object PriorityStopsMapper {
    /**
     * Writes [request]'s stops onto [base] and returns it, leaving every other preference as it was.
     *
     * **[base] is the session's own profile and is mutated in place**, which is upstream's shape
     * rather than a shortcut: `UserSkipPrioritySteps` holds its two `SkipPrioritySteps` in final
     * fields with no setters, so the only way to change one is through it. Carrying the rest of the
     * profile across matters because the server merges rather than replaces
     * (`Session.setUserData` → `UserData.update`) — anything left out would be merged as a default.
     */
    fun apply(
        request: SetPriorityStops,
        base: UserData,
    ): UserData =
        base.also { data ->
            data.userSkipPrioritySteps.yourTurn.applyFrom(request.yourTurn)
            data.userSkipPrioritySteps.opponentTurn.applyFrom(request.opponentTurn)
            // **How much the server may answer for the player.** `TargetImpl.tryToAutoChoose` picks a
            // single-candidate choice on their behalf — and never fires the event, so the client is
            // never asked — whenever this is above zero. Upstream defaults it to 1, which is what
            // took a card off a turn-1 Inquisition of Kozilek without showing the hand it revealed.
            data.autoTargetLevel = request.autoTargetLevel
        }

    private fun SkipPrioritySteps.applyFrom(stops: PriorityStops) {
        isUpkeep = stops.upkeep
        isDraw = stops.draw
        isMain1 = stops.main1
        isBeforeCombat = stops.beforeCombat
        isEndOfCombat = stops.endOfCombat
        isMain2 = stops.main2
        isEndOfTurn = stops.endOfTurn
    }
}
