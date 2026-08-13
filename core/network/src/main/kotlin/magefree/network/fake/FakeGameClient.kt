package magefree.network.fake

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.onStart
import magefree.network.game.GameClient
import magefree.network.game.GameState
import magefree.network.game.ManaType
import magefree.network.game.PassPriorityScope

/**
 * A scriptable [GameClient] test double (story 0052) for hermetic tests of downstream code — no bridge,
 * no `:protocol`. Every verb records its call and returns its configured [Result]; [observeGame] emits
 * its `seed` **first** (exactly as production does), then replays [gameStates], then forwards anything
 * pushed through [emitGameState], so a test can drive a board through a whole game without a socket.
 *
 * The seed-first behaviour is not incidental: story 0044 found that a fake which skipped the seed
 * manufactures a loading state production never has, and would hide a regression in seed emission
 * entirely. This one mirrors `DefaultGameClient.observeGame`.
 *
 * Every result defaults to success; set [actionResult] (or the per-call [actionResultFor]) to a
 * [Result.failure] carrying a [magefree.network.game.GameActionFailure] /
 * [magefree.network.game.GameSessionGoneFailure] to exercise a caller's error path.
 */
class FakeGameClient(
    /** The result every verb returns unless [actionResultFor] overrides it. */
    var actionResult: Result<Unit> = Result.success(Unit),
) : GameClient {
    /**
     * The verbs invoked, in order, each rendered as `"verb:gameId"` plus its argument where the argument
     * is what the assertion is about (`"play:g-1:card-7"`, `"ask:g-1:true"`, …). One flat list, because
     * what a game test asserts is almost always a *sequence*.
     */
    val calls: MutableList<String> = mutableListOf()

    /** Per-call override: when set, its result wins over [actionResult] for the matching [calls] entry. */
    var actionResultFor: ((String) -> Result<Unit>?)? = null

    /** States [observeGame] replays after the seed and before any live [emitGameState]. */
    var gameStates: List<GameState> = emptyList()

    private val emitted = MutableSharedFlow<GameState>(replay = 0, extraBufferCapacity = 64)

    override suspend fun joinGame(gameId: String): Result<Unit> = record("join:$gameId")

    override suspend fun watchGame(gameId: String): Result<Unit> = record("watch:$gameId")

    override suspend fun quitMatch(gameId: String): Result<Unit> = record("quit:$gameId")

    override suspend fun stopWatching(gameId: String): Result<Unit> = record("stopWatching:$gameId")

    override suspend fun playObject(
        gameId: String,
        objectId: String,
    ): Result<Unit> = record("play:$gameId:$objectId")

    override suspend fun passPriority(gameId: String): Result<Unit> = record("pass:$gameId")

    override suspend fun useSpecialAction(gameId: String): Result<Unit> = record("special:$gameId")

    override suspend fun chooseTarget(
        gameId: String,
        targetId: String,
    ): Result<Unit> = record("target:$gameId:$targetId")

    override suspend fun answerAsk(
        gameId: String,
        answer: Boolean,
    ): Result<Unit> = record("ask:$gameId:$answer")

    override suspend fun chooseAbility(
        gameId: String,
        abilityId: String,
    ): Result<Unit> = record("ability:$gameId:$abilityId")

    override suspend fun choosePile(
        gameId: String,
        first: Boolean,
    ): Result<Unit> = record("pile:$gameId:$first")

    override suspend fun chooseChoice(
        gameId: String,
        key: String,
        special: Boolean,
    ): Result<Unit> = record("choice:$gameId:$key:$special")

    override suspend fun playManaSource(
        gameId: String,
        sourceId: String,
    ): Result<Unit> = record("mana:$gameId:$sourceId")

    override suspend fun unlockMana(
        gameId: String,
        playerId: String,
        manaType: ManaType,
    ): Result<Unit> = record("unlockMana:$gameId:$playerId:$manaType")

    override suspend fun announceX(
        gameId: String,
        value: Int,
    ): Result<Unit> = record("x:$gameId:$value")

    override suspend fun chooseAmount(
        gameId: String,
        amount: Int,
    ): Result<Unit> = record("amount:$gameId:$amount")

    override suspend fun distributeAmounts(
        gameId: String,
        amounts: List<Int>,
    ): Result<Unit> = record("multiAmount:$gameId:${amounts.joinToString(",")}")

    override suspend fun cancelPrompt(gameId: String): Result<Unit> = record("cancel:$gameId")

    override suspend fun passPriorityUntil(
        gameId: String,
        scope: PassPriorityScope,
    ): Result<Unit> = record("passUntil:$gameId:$scope")

    override suspend fun concede(gameId: String): Result<Unit> = record("concede:$gameId")

    override fun observeGame(
        gameId: String,
        seed: GameState,
    ): Flow<GameState> =
        emitted.onStart {
            emit(seed)
            gameStates.forEach { emit(it) }
        }

    /** Push a [GameState] to live [observeGame] collectors, as the real fold would on a game push. */
    suspend fun emitGameState(state: GameState) {
        emitted.emit(state)
    }

    private fun record(call: String): Result<Unit> {
        calls += call
        return actionResultFor?.invoke(call) ?: actionResult
    }
}
