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
 *
 * **The clocks are validated, not coerced.** XMage's `MatchTimeLimit`/`MatchBufferTime` are closed
 * enumerations of exact second budgets, so a requested value outside them has no honest mapping.
 * [toMatchOptions] therefore **rejects** it as an [UnsupportedMatchOption] failure rather than
 * substituting one — silently falling back to "no clock" would hand the host a table whose match
 * rules differ from the ones they asked for while still reporting success, and the create reply
 * ([magefree.protocol.TableCreated]) carries no field in which an adjustment could be reported back.
 * A rejection reaches the host as a typed failed `TableActionResult` naming the supported values.
 */
public object MatchOptionsMapper {
    /**
     * Builds a `MatchOptions` from [options] (a normal, non-multiplayer match), or an
     * [UnsupportedMatchOption] failure when a requested clock has no upstream equivalent.
     */
    public fun toMatchOptions(options: CreateTableOptions): Result<MatchOptions> {
        val timeLimit =
            timeLimitOf(options.matchTimeLimitSeconds)
                ?: return unsupported(TIME_LIMIT, options.matchTimeLimitSeconds, supportedTimeLimitSeconds())
        val bufferTime =
            bufferTimeOf(options.matchBufferTimeSeconds)
                ?: return unsupported(BUFFER_TIME, options.matchBufferTimeSeconds, supportedBufferSeconds())
        return Result.success(
            MatchOptions(options.name, options.gameType, false).apply {
                options.players.forEach { playerTypes.add(playerTypeOf(it)) }
                deckType = options.deckType
                isLimited = options.deckType.startsWith("Limited")
                matchTimeLimit = timeLimit
                matchBufferTime = bufferTime
                skillLevel = skillOf(options.skillLevel)
                range = rangeOf(options.range)
                winsNeeded = options.winsNeeded
                freeMulligans = options.freeMulligans
                isSpectatorsAllowed = options.spectatorsAllowed
                isRated = options.rated
                password = options.password ?: ""
                quitRatio = options.quitRatio
                minimumRating = options.minimumRating
            },
        )
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

    /**
     * Resolves the [MatchTimeLimit] whose priority budget is exactly [seconds], or `null` when the
     * value is not one XMage offers. `null` — never [MatchTimeLimit.NONE], which is the real "no clock"
     * value ([supportedTimeLimitSeconds] contains its `0`) and would be indistinguishable from it.
     */
    public fun timeLimitOf(seconds: Int): MatchTimeLimit? = MatchTimeLimit.entries.firstOrNull { it.prioritySecs == seconds }

    /**
     * Resolves the [MatchBufferTime] whose buffer is exactly [seconds], or `null` when the value is not
     * one XMage offers (see [timeLimitOf] on why this is not [MatchBufferTime.NONE]).
     */
    public fun bufferTimeOf(seconds: Int): MatchBufferTime? = MatchBufferTime.entries.firstOrNull { it.bufferSecs == seconds }

    /** Every priority budget XMage accepts, ascending — the `0` is [MatchTimeLimit.NONE] (no clock). */
    public fun supportedTimeLimitSeconds(): List<Int> = MatchTimeLimit.entries.map { it.prioritySecs }.sorted()

    /** Every buffer budget XMage accepts, ascending — the `0` is [MatchBufferTime.NONE] (no buffer). */
    public fun supportedBufferSeconds(): List<Int> = MatchBufferTime.entries.map { it.bufferSecs }.sorted()

    private fun <T> unsupported(
        field: String,
        seconds: Int,
        supported: List<Int>,
    ): Result<T> = Result.failure(UnsupportedMatchOption(field = field, seconds = seconds, supported = supported))

    /** The [UnsupportedMatchOption.field] name for the per-player priority clock. */
    public const val TIME_LIMIT: String = "match time limit"

    /** The [UnsupportedMatchOption.field] name for the per-priority buffer clock. */
    public const val BUFFER_TIME: String = "match buffer time"
}

/**
 * A requested clock XMage has no value for. Carried as the failure of
 * [MatchOptionsMapper.toMatchOptions] so an unsupported budget is reported to the host — as a failed
 * `TableActionResult` naming [supported] — instead of being silently downgraded to "no clock" under a
 * successful create.
 *
 * @property field which clock was rejected ([MatchOptionsMapper.TIME_LIMIT]/[MatchOptionsMapper.BUFFER_TIME]).
 * @property seconds the requested budget.
 * @property supported every budget XMage does accept for that clock, ascending.
 */
public class UnsupportedMatchOption(
    public val field: String,
    public val seconds: Int,
    public val supported: List<Int>,
) : IllegalArgumentException("unsupported $field: ${seconds}s (supported: ${supported.joinToString(", ")})")
