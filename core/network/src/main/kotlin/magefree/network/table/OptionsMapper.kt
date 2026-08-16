package magefree.network.table

import magefree.model.SkillLevel
import magefree.protocol.RangeCode
import magefree.protocol.SeatPlayerTypeCode
import magefree.protocol.SkillLevelCode
import magefree.protocol.TableStateCode
import magefree.protocol.CreateTableOptions as ProtocolCreateTableOptions
import magefree.protocol.TableDetail as ProtocolTableDetail

/**
 * The create-options half of the table client's mapper boundary (story 0037): projects the app-schema
 * [CreateTableOptions] onto 0036's wire [ProtocolCreateTableOptions] so a caller passes a `:protocol`-free
 * options record to [TableClient.createTable].
 *
 * The mapping is **total** — every app value has a wire code — but it is *not* lossless.
 * [SkillLevel.Unknown] (what the read direction produces for a skill the server did not name) is
 * written as `CASUAL`, not the wire's own `UNKNOWN`: the bridge resolves `UNKNOWN` to `CASUAL` when it
 * builds the upstream `MatchOptions` anyway, so this pre-resolves it. A table read back as `Unknown`
 * and re-created therefore lands as `Casual`; every other value round-trips one-to-one.
 *
 * The numeric fields (clocks, ratios, ratings) pass through unvalidated — the bridge is where a clock
 * XMage has no value for is rejected (`MatchOptionsMapper`, story 0044).
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

/**
 * The read half of the boundary (story 0040): projects 0040's wire [ProtocolTableDetail] — the reply to
 * a `GetTable` — onto the app-schema [TableDetails] the `TableClient` ABI exposes, so no wire shape
 * reaches a `:feature` consumer. Seat order is preserved (it is the server's slot order).
 */
internal fun ProtocolTableDetail.toDetails(): TableDetails =
    TableDetails(
        tableId = table.tableId,
        name = table.name,
        gameType = table.gameType,
        deckType = table.deckType,
        serverState = table.state.toServerState(),
        seats =
            seats.map { seat ->
                Seat(
                    index = seat.index,
                    name = seat.playerName,
                    playerType = seat.playerType.toSeatType(),
                    isOccupied = seat.occupied,
                )
            },
        activeGameId = activeGameId,
    )

/** Wire [TableStateCode] → app-schema [TableServerState]; total, so a new code cannot go unhandled. */
private fun TableStateCode.toServerState(): TableServerState =
    when (this) {
        TableStateCode.WAITING -> TableServerState.Waiting
        TableStateCode.READY_TO_START -> TableServerState.ReadyToStart
        TableStateCode.STARTING -> TableServerState.Starting
        TableStateCode.DRAFTING -> TableServerState.Drafting
        TableStateCode.CONSTRUCTING -> TableServerState.Constructing
        TableStateCode.DUELING -> TableServerState.Dueling
        TableStateCode.SIDEBOARDING -> TableServerState.Sideboarding
        TableStateCode.FINISHED -> TableServerState.Finished
        TableStateCode.UNKNOWN -> TableServerState.Unknown
    }

/** Wire [SeatPlayerTypeCode] → app-schema [SeatPlayerType] (the read direction of [toCode]). */
private fun SeatPlayerTypeCode.toSeatType(): SeatPlayerType =
    when (this) {
        SeatPlayerTypeCode.HUMAN -> SeatPlayerType.Human
        SeatPlayerTypeCode.COMPUTER_MONTE_CARLO -> SeatPlayerType.ComputerMonteCarlo
        SeatPlayerTypeCode.COMPUTER_MAD -> SeatPlayerType.ComputerMad
        SeatPlayerTypeCode.COMPUTER_DRAFT_BOT -> SeatPlayerType.ComputerDraftBot
        SeatPlayerTypeCode.UNKNOWN -> SeatPlayerType.Unknown
    }

/**
 * App-schema [SeatPlayerType] → wire [SeatPlayerTypeCode] (the write direction of [toSeatType]). Used by
 * the create mapping above and — since story 0041 — by `joinTable`, which carries the seat's kind so a
 * host can fill its configured AI seats with the same verb.
 */
internal fun SeatPlayerType.toCode(): SeatPlayerTypeCode =
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

/** App-schema [SkillLevel] → wire [SkillLevelCode]; `Unknown` is written as `CASUAL` (see the file KDoc). */
private fun SkillLevel.toCode(): SkillLevelCode =
    when (this) {
        SkillLevel.Beginner -> SkillLevelCode.BEGINNER
        SkillLevel.Casual -> SkillLevelCode.CASUAL
        SkillLevel.Serious -> SkillLevelCode.SERIOUS
        SkillLevel.Unknown -> SkillLevelCode.CASUAL
    }
