package magefree.network.table

import magefree.model.SkillLevel
import magefree.protocol.RangeCode
import magefree.protocol.SeatPlayerTypeCode
import magefree.protocol.SkillLevelCode
import magefree.protocol.CreateTableOptions as ProtocolCreateTableOptions

/**
 * The create-options half of the table client's mapper boundary (story 0037): projects the app-schema
 * [CreateTableOptions] onto 0036's wire [ProtocolCreateTableOptions] so a caller passes a `:protocol`-free
 * options record to [TableClient.createTable]. The app enums mirror the wire codes one-to-one, so the
 * mapping is total and lossless.
 */
internal fun CreateTableOptions.toProtocol(): ProtocolCreateTableOptions =
    ProtocolCreateTableOptions(
        name = name,
        gameType = gameType,
        deckType = deckType,
        players = players.map { it.toCode() },
        rated = rated,
        winsNeeded = winsNeeded,
        freeMulligans = freeMulligans,
        skillLevel = skillLevel.toCode(),
        range = range.toCode(),
        matchTimeLimitSeconds = matchTimeLimitSeconds,
        matchBufferTimeSeconds = matchBufferTimeSeconds,
        spectatorsAllowed = spectatorsAllowed,
        quitRatio = quitRatio,
        minimumRating = minimumRating,
        password = password,
    )

private fun SeatPlayerType.toCode(): SeatPlayerTypeCode =
    when (this) {
        SeatPlayerType.Human -> SeatPlayerTypeCode.HUMAN
        SeatPlayerType.ComputerMonteCarlo -> SeatPlayerTypeCode.COMPUTER_MONTE_CARLO
        SeatPlayerType.ComputerMad -> SeatPlayerTypeCode.COMPUTER_MAD
        SeatPlayerType.ComputerDraftBot -> SeatPlayerTypeCode.COMPUTER_DRAFT_BOT
        SeatPlayerType.Unknown -> SeatPlayerTypeCode.UNKNOWN
    }

private fun RangeOfInfluence.toCode(): RangeCode =
    when (this) {
        RangeOfInfluence.One -> RangeCode.ONE
        RangeOfInfluence.Two -> RangeCode.TWO
        RangeOfInfluence.All -> RangeCode.ALL
    }

private fun SkillLevel.toCode(): SkillLevelCode =
    when (this) {
        SkillLevel.Beginner -> SkillLevelCode.BEGINNER
        SkillLevel.Casual -> SkillLevelCode.CASUAL
        SkillLevel.Serious -> SkillLevelCode.SERIOUS
        SkillLevel.Unknown -> SkillLevelCode.CASUAL
    }
