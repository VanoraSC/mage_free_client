package magefree.bridge.mapping

import mage.constants.SkillLevel
import mage.constants.TableState
import mage.players.PlayerType
import mage.view.TableView
import magefree.protocol.SeatPlayerTypeCode
import magefree.protocol.SkillLevelCode
import magefree.protocol.TableDetail
import magefree.protocol.TableSeatSummary
import magefree.protocol.TableStateCode
import magefree.protocol.TableSummary
import java.util.Date

/**
 * Maps an upstream [mage.view.TableView] to the app-schema [TableSummary]. Part of
 * the **single coupling surface**: `mage.view.*` is read only here (and its sibling mappers). Pure and
 * deterministic — same input always yields the same output, with no I/O or hidden state.
 *
 * **Testability.** `TableView`'s only constructor takes a `mage.game.Table`, which is not on the
 * bridge classpath (only `mage-common` is) and is impractical to build in-memory — so the raw view
 * cannot be constructed in a hermetic test. To keep the mapping **logic** genuinely verifiable, all of
 * it lives in [build], a pure function over already-extracted values (the browse-relevant getters):
 * enum translation, seat filled/total counting, and create-time normalisation. [build] is asserted
 * directly by `TableMapperTest`; [map] is the thin getter-forwarding shim, exercised end-to-end by the
 * live `LobbyRelayIT` (the reference server's table list is legitimately empty, so the shim's getter
 * wiring is covered only when real tables exist — see the story's testability note).
 *
 * Field mapping (see [build]):
 * - `tableId` ← [TableView.getTableId]`.toString()`.
 * - `name`/`controllerName`/`gameType`/`deckType` ← the matching getters.
 * - `state` ← [TableView.getTableState] via [stateOf]; `skillLevel` ← [TableView.getSkillLevel] via [skillOf].
 * - `seatsFilled` ← count of [mage.view.SeatView] with a non-null player id; `seatsTotal` ← seat count.
 * - `isTournament`/`isRated`/`isPassworded`/`isLimited` ← the matching getters.
 * - `createdAtEpochMs` ← [TableView.getCreateTime]`.time` (0 if the date is null).
 *
 * **Per-seat detail (story 0040).** [mapDetail] adds the seat list the summary form reduces to counts:
 * each [mage.view.SeatView] becomes a [TableSeatSummary] via the pure [buildSeat]. The counting
 * behaviour of [map]/[build] is unchanged — [mapDetail] is purely additive.
 */
public object TableMapper {
    /** Maps [view] to its app-schema summary by extracting the browse-relevant getters into [build]. */
    public fun map(view: TableView): TableSummary =
        build(
            tableId = view.tableId?.toString() ?: "",
            name = view.tableName ?: "",
            controllerName = view.controllerName ?: "",
            gameType = view.gameType ?: "",
            deckType = view.deckType ?: "",
            state = view.tableState,
            // An empty seat has a null player id (see mage.view.SeatView); a filled seat has one.
            seatsOccupied = view.seats.map { it.playerId != null },
            isTournament = view.isTournament,
            isRated = view.isRated,
            isPassworded = view.isPassworded,
            isLimited = view.isLimited,
            skillLevel = view.skillLevel,
            createTime = view.createTime,
        )

    /**
     * The pure mapping logic over already-extracted values. Kept separate from [map] so it is fully
     * unit-testable without constructing a [TableView] (which requires a `mage.game.Table` not on the
     * bridge classpath). [seatsOccupied] is one flag per seat (true = occupied); its size is the seat
     * total and its true-count the filled count.
     */
    public fun build(
        tableId: String,
        name: String,
        controllerName: String,
        gameType: String,
        deckType: String,
        state: TableState?,
        seatsOccupied: List<Boolean>,
        isTournament: Boolean,
        isRated: Boolean,
        isPassworded: Boolean,
        isLimited: Boolean,
        skillLevel: SkillLevel?,
        createTime: Date?,
    ): TableSummary =
        TableSummary(
            tableId = tableId,
            name = name,
            controllerName = controllerName,
            gameType = gameType,
            deckType = deckType,
            state = stateOf(state),
            seatsFilled = seatsOccupied.count { it },
            seatsTotal = seatsOccupied.size,
            isTournament = isTournament,
            isRated = isRated,
            isPassworded = isPassworded,
            isLimited = isLimited,
            skillLevel = skillOf(skillLevel),
            createdAtEpochMs = createTime?.time ?: 0L,
        )

    /**
     * Maps [view] to a [TableDetail]: the same [TableSummary] [map] produces, plus one
     * [TableSeatSummary] per `mage.view.SeatView` in seat order (story 0040). This is the reply to a
     * `GetTable` — the room's real seat state, which the summary form reduces to filled/total counts.
     */
    public fun mapDetail(view: TableView): TableDetail =
        TableDetail(
            table = map(view),
            seats =
                view.seats.mapIndexed { index, seat ->
                    buildSeat(
                        index = index,
                        playerId = seat.playerId?.toString(),
                        playerName = seat.playerName,
                        playerType = seat.playerType,
                    )
                },
        )

    /**
     * The pure seat-mapping logic over already-extracted `SeatView` values (kept separate from
     * [mapDetail] for the same reason [build] is separate from [map]: a `SeatView` needs a
     * `mage.game.Seat`, which is not on the bridge classpath).
     *
     * - [occupied][TableSeatSummary.occupied] ← a non-null [playerId] — the same rule
     *   [map] already uses for `seatsFilled`, so the two can never disagree.
     * - [playerName][TableSeatSummary.playerName] ← the seat's name, normalised to `null` when blank
     *   (upstream reports an *empty* seat's name as `""`, not null).
     * - the seat's [playerType] is carried whether or not the seat is filled: upstream matches a join
     *   to a seat **by player type**, so an empty seat's type is what may still sit there.
     */
    public fun buildSeat(
        index: Int,
        playerId: String?,
        playerName: String?,
        playerType: PlayerType?,
    ): TableSeatSummary =
        TableSeatSummary(
            index = index,
            playerName = playerName?.takeIf { it.isNotBlank() },
            playerType = seatTypeOf(playerType),
            occupied = playerId != null,
        )

    /**
     * Translates an upstream [PlayerType] to its app-schema [SeatPlayerTypeCode] — the read direction of
     * [MatchOptionsMapper.playerTypeOf]. A null (upstream drift) maps defensively to
     * [SeatPlayerTypeCode.UNKNOWN] rather than throwing.
     */
    private fun seatTypeOf(type: PlayerType?): SeatPlayerTypeCode =
        when (type) {
            PlayerType.HUMAN -> SeatPlayerTypeCode.HUMAN
            PlayerType.COMPUTER_MONTE_CARLO -> SeatPlayerTypeCode.COMPUTER_MONTE_CARLO
            PlayerType.COMPUTER_MAD -> SeatPlayerTypeCode.COMPUTER_MAD
            PlayerType.COMPUTER_DRAFT_BOT -> SeatPlayerTypeCode.COMPUTER_DRAFT_BOT
            null -> SeatPlayerTypeCode.UNKNOWN
        }

    /**
     * Translates an upstream [TableState] to its app-schema [TableStateCode]. An unknown/null state
     * maps defensively to [TableStateCode.UNKNOWN] rather than throwing, keeping the relay resilient to
     * upstream additions.
     */
    private fun stateOf(state: TableState?): TableStateCode =
        when (state) {
            TableState.WAITING -> TableStateCode.WAITING
            TableState.READY_TO_START -> TableStateCode.READY_TO_START
            TableState.STARTING -> TableStateCode.STARTING
            TableState.DRAFTING -> TableStateCode.DRAFTING
            TableState.CONSTRUCTING -> TableStateCode.CONSTRUCTING
            TableState.DUELING -> TableStateCode.DUELING
            TableState.SIDEBOARDING -> TableStateCode.SIDEBOARDING
            TableState.FINISHED -> TableStateCode.FINISHED
            null -> TableStateCode.UNKNOWN
        }

    /** Translates an upstream [SkillLevel] to its app-schema [SkillLevelCode] (null → [SkillLevelCode.UNKNOWN]). */
    private fun skillOf(skill: SkillLevel?): SkillLevelCode =
        when (skill) {
            SkillLevel.BEGINNER -> SkillLevelCode.BEGINNER
            SkillLevel.CASUAL -> SkillLevelCode.CASUAL
            SkillLevel.SERIOUS -> SkillLevelCode.SERIOUS
            null -> SkillLevelCode.UNKNOWN
        }
}
