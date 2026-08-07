package magefree.bridge.mapping

import mage.constants.MatchBufferTime
import mage.constants.MatchTimeLimit
import mage.constants.RangeOfInfluence
import mage.constants.SkillLevel
import mage.game.match.MatchOptions
import mage.players.PlayerType
import magefree.protocol.CreateTableOptions
import magefree.protocol.RangeCode
import magefree.protocol.SeatPlayerTypeCode
import magefree.protocol.SkillLevelCode

/**
 * Maps the app-schema [CreateTableOptions] onto XMage's `mage.game.match.MatchOptions`. Part of the
 * **single coupling surface**: `mage.game.match.*`/`mage.players.*`/`mage.constants.*` are constructed
 * only here (and its sibling mappers), so `MatchOptions` never crosses the wire — `CreateTable` carries
 * the flat [CreateTableOptions] and the bridge builds the real options at this boundary. Pure and
 * deterministic — no I/O or hidden state.
 *
 * Mirrors the desktop client's `NewTableDialog` build: a non-multiplayer match seeded with one
 * `PlayerType` per seat, the deck/format labels, best-of wins, free mulligans, skill/range, the
 * priority + buffer time limits, spectators/rated/password, quit ratio, and minimum rating. The
 * `limited` flag is derived from a `"Limited"`-prefixed deck type, as the desktop does.
 */
public object MatchOptionsMapper {
    /** Builds a `MatchOptions` from [options] (a normal, non-multiplayer match). */
    public fun toMatchOptions(options: CreateTableOptions): MatchOptions =
        MatchOptions(options.name, options.gameType, false).apply {
            options.players.forEach { playerTypes.add(playerTypeOf(it)) }
            deckType = options.deckType
            isLimited = options.deckType.startsWith("Limited")
            matchTimeLimit = timeLimitOf(options.matchTimeLimitSeconds)
            matchBufferTime = bufferTimeOf(options.matchBufferTimeSeconds)
            skillLevel = skillOf(options.skillLevel)
            range = rangeOf(options.range)
            winsNeeded = options.winsNeeded
            freeMulligans = options.freeMulligans
            isSpectatorsAllowed = options.spectatorsAllowed
            isRated = options.rated
            password = options.password ?: ""
            quitRatio = options.quitRatio
            minimumRating = options.minimumRating
        }

    /** Maps a seat [code] to a `PlayerType`; an [SeatPlayerTypeCode.UNKNOWN]/unmapped code → HUMAN. */
    public fun playerTypeOf(code: SeatPlayerTypeCode): PlayerType =
        when (code) {
            SeatPlayerTypeCode.HUMAN -> PlayerType.HUMAN
            SeatPlayerTypeCode.COMPUTER_MONTE_CARLO -> PlayerType.COMPUTER_MONTE_CARLO
            SeatPlayerTypeCode.COMPUTER_MAD -> PlayerType.COMPUTER_MAD
            SeatPlayerTypeCode.COMPUTER_DRAFT_BOT -> PlayerType.COMPUTER_DRAFT_BOT
            SeatPlayerTypeCode.UNKNOWN -> PlayerType.HUMAN
        }

    /** Maps an app-schema skill [code] to a `SkillLevel`; [SkillLevelCode.UNKNOWN] → CASUAL. */
    public fun skillOf(code: SkillLevelCode): SkillLevel =
        when (code) {
            SkillLevelCode.BEGINNER -> SkillLevel.BEGINNER
            SkillLevelCode.CASUAL -> SkillLevel.CASUAL
            SkillLevelCode.SERIOUS -> SkillLevel.SERIOUS
            SkillLevelCode.UNKNOWN -> SkillLevel.CASUAL
        }

    /** Maps an app-schema [RangeCode] to a `RangeOfInfluence`. */
    public fun rangeOf(code: RangeCode): RangeOfInfluence =
        when (code) {
            RangeCode.ONE -> RangeOfInfluence.ONE
            RangeCode.TWO -> RangeOfInfluence.TWO
            RangeCode.ALL -> RangeOfInfluence.ALL
        }

    /** Resolves the [MatchTimeLimit] whose priority budget equals [seconds], or NONE if none matches. */
    public fun timeLimitOf(seconds: Int): MatchTimeLimit =
        MatchTimeLimit.entries.firstOrNull { it.prioritySecs == seconds } ?: MatchTimeLimit.NONE

    /** Resolves the [MatchBufferTime] whose buffer equals [seconds], or NONE if none matches. */
    public fun bufferTimeOf(seconds: Int): MatchBufferTime =
        MatchBufferTime.entries.firstOrNull { it.bufferSecs == seconds } ?: MatchBufferTime.NONE
}
