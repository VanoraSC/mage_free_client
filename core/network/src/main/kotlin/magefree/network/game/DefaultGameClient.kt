package magefree.network.game

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import magefree.model.ConnectionState
import magefree.network.BridgeClient
import magefree.network.ServerPushSource
import magefree.protocol.GameActionResult
import magefree.protocol.GameFailureCode
import magefree.protocol.GamePrompted
import magefree.protocol.JoinGame
import magefree.protocol.PlayerActionCode
import magefree.protocol.QuitMatch
import magefree.protocol.SendPlayerAction
import magefree.protocol.SendPlayerBoolean
import magefree.protocol.SendPlayerInteger
import magefree.protocol.SendPlayerManaType
import magefree.protocol.SendPlayerString
import magefree.protocol.SendPlayerUuid
import magefree.protocol.ServerMessage
import magefree.protocol.StopWatching
import magefree.protocol.WatchGame
import java.util.UUID

/**
 * The production [GameClient] (story 0052), over the same [BridgeClient] singleton the lobby and table
 * clients ride — one socket, three feature clients, exactly as story 0037 established.
 *
 * **Actions** mint a `requestId`, send the matching 0051 request through [BridgeClient.request] (the
 * erased `request<ServerMessage>` seam, so no `:protocol` type crosses the ABI), and map the correlated
 * `GameActionResult`: `ok = true` → success; `ok = false` → a [Result.failure] carrying either
 * [GameSessionGoneFailure] (the bridge had no session, so the server was never asked — story 0050) or a
 * plain [GameActionFailure] with the server's reason. A transport failure thrown by `request` (no
 * session, timeout, drop) is captured as a failed [Result] rather than propagated.
 *
 * **[observeGame]** merges two sources into a single folding collector, so the held state is mutated by
 * exactly one coroutine and no lock is needed: the [ServerPushSource] stream folded by [GameEventFold]
 * for this game, and a re-sync trigger on each return to [ConnectionState.Connected] that re-emits the
 * held state. Unlike the table client there is **no** periodic read and no read-on-open — not as an
 * omission but because upstream has no verb that reads a `GameView`; game state is push-only, and the
 * bridge's parked-session buffer (story 0023) is what carries it across a socket gap.
 *
 * @param newRequestId how a correlation id is minted (a UUID in production; overridable so a test can
 *   assert the exact id sent).
 */
internal class DefaultGameClient(
    private val bridgeClient: BridgeClient,
    private val pushSource: ServerPushSource,
    private val connectionState: StateFlow<@JvmSuppressWildcards ConnectionState>,
    private val newRequestId: () -> String = { UUID.randomUUID().toString() },
) : GameClient {
    // --- joining and leaving -------------------------------------------------------------------------

    override suspend fun joinGame(gameId: String): Result<Unit> =
        action { id -> bridgeClient.request(JoinGame(gameId = gameId, requestId = id), id) }

    override suspend fun watchGame(gameId: String): Result<Unit> =
        action { id -> bridgeClient.request(WatchGame(gameId = gameId, requestId = id), id) }

    override suspend fun quitMatch(gameId: String): Result<Unit> =
        action { id -> bridgeClient.request(QuitMatch(gameId = gameId, requestId = id), id) }

    override suspend fun stopWatching(gameId: String): Result<Unit> =
        action { id -> bridgeClient.request(StopWatching(gameId = gameId, requestId = id), id) }

    // --- answering the outstanding prompt ------------------------------------------------------------

    override suspend fun playObject(
        gameId: String,
        objectId: String,
    ): Result<Unit> = sendUuid(gameId, objectId)

    override suspend fun passPriority(gameId: String): Result<Unit> = sendBoolean(gameId, false)

    override suspend fun useSpecialAction(gameId: String): Result<Unit> = sendString(gameId, SPECIAL)

    override suspend fun chooseTarget(
        gameId: String,
        targetId: String,
    ): Result<Unit> = sendUuid(gameId, targetId)

    override suspend fun answerAsk(
        gameId: String,
        answer: Boolean,
    ): Result<Unit> = sendBoolean(gameId, answer)

    override suspend fun chooseAbility(
        gameId: String,
        abilityId: String,
    ): Result<Unit> = sendUuid(gameId, abilityId)

    override suspend fun choosePile(
        gameId: String,
        first: Boolean,
    ): Result<Unit> = sendBoolean(gameId, first)

    override suspend fun chooseChoice(
        gameId: String,
        key: String,
        special: Boolean,
    ): Result<Unit> = sendString(gameId, if (special) SPECIAL_CHOICE_PREFIX + key else key)

    override suspend fun playManaSource(
        gameId: String,
        sourceId: String,
    ): Result<Unit> = sendUuid(gameId, sourceId)

    override suspend fun unlockMana(
        gameId: String,
        playerId: String,
        manaType: ManaType,
    ): Result<Unit> =
        action { id ->
            bridgeClient.request(
                SendPlayerManaType(
                    gameId = gameId,
                    playerId = playerId,
                    manaType = GameViewMapper.toCode(manaType),
                    requestId = id,
                ),
                id,
            )
        }

    override suspend fun announceX(
        gameId: String,
        value: Int,
    ): Result<Unit> = sendInteger(gameId, value)

    override suspend fun chooseAmount(
        gameId: String,
        amount: Int,
    ): Result<Unit> = sendInteger(gameId, amount)

    /**
     * Upstream parses the multi-amount reply as a comma-separated positional list, so the join is the
     * whole encoding — see `GamePrompt.GetMultiAmount`. Rendered here (not by the caller) so no consumer
     * ever has to know the wire form.
     */
    override suspend fun distributeAmounts(
        gameId: String,
        amounts: List<Int>,
    ): Result<Unit> = sendString(gameId, amounts.joinToString(","))

    override suspend fun cancelPrompt(gameId: String): Result<Unit> = sendBoolean(gameId, false)

    // --- out-of-band player actions ------------------------------------------------------------------

    override suspend fun passPriorityUntil(
        gameId: String,
        scope: PassPriorityScope,
    ): Result<Unit> = playerAction(gameId, GameViewMapper.toCode(scope))

    override suspend fun concede(gameId: String): Result<Unit> = playerAction(gameId, PlayerActionCode.CONCEDE)

    // --- observing -----------------------------------------------------------------------------------

    override fun observeGame(
        gameId: String,
        seed: GameState,
    ): Flow<GameState> =
        callbackFlow {
            var state = seed
            trySend(state)

            // Both sources funnel into one collector: `merge` serialises them downstream, so the held
            // `state` is read/written by exactly one coroutine — no lock, no race.
            val pushes = pushSource.serverPushes.map<ServerMessage, Intent> { Intent.Push(it) }
            val resyncs = connectionState.reEstablishments().map { Intent.Resync }

            merge(pushes, resyncs)
                .onEach { intent ->
                    when (intent) {
                        is Intent.Push -> {
                            val next = GameEventFold.fold(state, intent.message)
                            // An unchanged state is not re-emitted — every consumer would recompose for
                            // nothing. A **prompt** is the deliberate exception: when the server refuses
                            // an answer it re-asks with the very same view, so the folded state can be
                            // byte-identical while a real, new question is outstanding. Swallowing that
                            // emission would leave a consumer waiting for an event that never comes
                            // while the server waits for an answer — a deadlock, not a redundancy.
                            if (next != null && (next != state || intent.message is GamePrompted)) {
                                state = next
                                trySend(next)
                            }
                        }
                        // A 0023/0024 resume completed. Re-emit the held state (the 0037 non-destructive
                        // re-sync) so a collector that had stopped rendering is not stranded. Nothing is
                        // re-read: there is no upstream verb that reads a GameView. The true current
                        // snapshot arrives as the bridge's parked-session buffer drains into the
                        // re-bound socket.
                        Intent.Resync -> trySend(state)
                    }
                }.launchIn(this)

            awaitClose { }
        }

    /** An `observeGame` input: a server push to fold, or a resume that re-syncs the current state. */
    private sealed interface Intent {
        data class Push(
            val message: ServerMessage,
        ) : Intent

        data object Resync : Intent
    }

    // --- request plumbing ----------------------------------------------------------------------------

    private suspend fun sendUuid(
        gameId: String,
        value: String,
    ): Result<Unit> = action { id -> bridgeClient.request(SendPlayerUuid(gameId = gameId, value = value, requestId = id), id) }

    private suspend fun sendBoolean(
        gameId: String,
        value: Boolean,
    ): Result<Unit> = action { id -> bridgeClient.request(SendPlayerBoolean(gameId = gameId, value = value, requestId = id), id) }

    private suspend fun sendInteger(
        gameId: String,
        value: Int,
    ): Result<Unit> = action { id -> bridgeClient.request(SendPlayerInteger(gameId = gameId, value = value, requestId = id), id) }

    private suspend fun sendString(
        gameId: String,
        value: String,
    ): Result<Unit> = action { id -> bridgeClient.request(SendPlayerString(gameId = gameId, value = value, requestId = id), id) }

    private suspend fun playerAction(
        gameId: String,
        code: PlayerActionCode,
    ): Result<Unit> = action { id -> bridgeClient.request(SendPlayerAction(gameId = gameId, action = code, requestId = id), id) }

    /**
     * Run one game action: mint an id, run [block], and map its correlated reply. Any transport throw
     * (no session / timeout / drop from `request`) is captured as a failed [Result], so a caller always
     * gets a [Result] and never an unhandled throw. [CancellationException] is re-thrown so structured
     * cancellation is preserved.
     */
    private inline fun action(block: (id: String) -> ServerMessage): Result<Unit> =
        try {
            when (val reply = block(newRequestId())) {
                is GameActionResult -> reply.asUnit()
                else -> Result.failure(GameActionFailure("game: unexpected reply ${reply::class.simpleName}"))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

    private fun GameActionResult.asUnit(): Result<Unit> =
        if (ok) {
            Result.success(Unit)
        } else {
            Result.failure(
                // Story 0050's distinction, on the game verbs: SESSION_GONE means the bridge had nothing
                // to act through, so the server never saw the request. Retrying cannot help;
                // re-authenticating can, and only a distinct type lets a caller tell the two apart.
                if (failure == GameFailureCode.SESSION_GONE) GameSessionGoneFailure(reason) else GameActionFailure(reason),
            )
        }

    /**
     * A return to [ConnectionState.Connected] after something else (a 0023/0024 resume completing). The
     * initial `Connected` is skipped — the seed already covers the current state; only a *return* to
     * Connected re-syncs. Identical to the table client's rule, deliberately.
     */
    private fun StateFlow<ConnectionState>.reEstablishments(): Flow<Unit> =
        flow {
            var previous: ConnectionState? = null
            collect { current ->
                if (current == ConnectionState.Connected &&
                    previous != null &&
                    previous != ConnectionState.Connected
                ) {
                    emit(Unit)
                }
                previous = current
            }
        }

    private companion object {
        /** Upstream's literal token for the extra "special" button on a select/mana prompt. */
        const val SPECIAL: String = "special"

        /** Upstream's prefix that turns a choice key into "the choice's special option". */
        const val SPECIAL_CHOICE_PREFIX: String = "#"
    }
}
