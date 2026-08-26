package magefree.network.mapper

import magefree.model.GameType
import magefree.model.LobbyTable
import magefree.model.RoomUser
import magefree.model.SkillLevel
import magefree.model.TableState
import magefree.protocol.GameTypeSummary
import magefree.protocol.RoomUserSummary
import magefree.protocol.SkillLevelCode
import magefree.protocol.TableStateCode
import magefree.protocol.TableSummary

/**
 * The lobby half of `:core:network`'s mapper boundary: the single place the wire
 * `:protocol` lobby summaries ([TableSummary]/[RoomUserSummary]/[GameTypeSummary]) are translated into
 * pure `:core:model` types. Nothing above `:core:network` sees a `:protocol` shape — the `LobbyClient`
 * interface and the repository speak only `:core:model`. Pure and deterministic, so it is unit-testable
 * without a bridge (fakes are recordings: the tests feed real `:protocol` summaries through here).
 *
 * Unknown wire enum values map to the model's `Unknown` fallback rather than throwing, honouring the
 * additive-only forward-compat contract of the `:protocol` codes.
 */
internal object LobbyMapper {
    fun TableSummary.toModel(): LobbyTable =
        LobbyTable(
            id = tableId,
            name = name,
            host = controllerName,
            gameType = gameType,
            deckType = deckType,
            state = state.toModel(),
            seatsFilled = seatsFilled,
            seatsTotal = seatsTotal,
            isTournament = isTournament,
            isRated = isRated,
            isPassworded = isPassworded,
            isLimited = isLimited,
            skillLevel = skillLevel.toModel(),
            createdAtEpochMs = createdAtEpochMs,
        )

    fun RoomUserSummary.toModel(): RoomUser =
        RoomUser(
            name = name,
            flag = flag,
            matchHistory = matchHistory,
            games = games,
            ping = ping,
            generalRating = generalRating,
        )

    fun GameTypeSummary.toModel(): GameType =
        GameType(
            name = name,
            minPlayers = minPlayers,
            maxPlayers = maxPlayers,
        )

    private fun TableStateCode.toModel(): TableState =
        when (this) {
            TableStateCode.WAITING -> TableState.Waiting
            TableStateCode.READY_TO_START -> TableState.ReadyToStart
            TableStateCode.STARTING -> TableState.Starting
            TableStateCode.DRAFTING -> TableState.Drafting
            TableStateCode.CONSTRUCTING -> TableState.Constructing
            TableStateCode.DUELING -> TableState.Dueling
            TableStateCode.SIDEBOARDING -> TableState.Sideboarding
            TableStateCode.FINISHED -> TableState.Finished
            TableStateCode.UNKNOWN -> TableState.Unknown
        }

    private fun SkillLevelCode.toModel(): SkillLevel =
        when (this) {
            SkillLevelCode.BEGINNER -> SkillLevel.Beginner
            SkillLevelCode.CASUAL -> SkillLevel.Casual
            SkillLevelCode.SERIOUS -> SkillLevel.Serious
            SkillLevelCode.UNKNOWN -> SkillLevel.Unknown
        }
}
