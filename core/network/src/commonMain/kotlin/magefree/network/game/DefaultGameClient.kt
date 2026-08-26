package magefree.network.game

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import magefree.model.ConnectionState
import magefree.network.BridgeClient
import magefree.network.ServerPushSource
import magefree.protocol.GameActionResult
import magefree.protocol.GameFailureCode
import magefree.protocol.GamePrompted
import magefree.protocol.GameStateSnapshot
import magefree.protocol.GameStateUnavailable
import magefree.protocol.GameStateUnavailableCode
import magefree.protocol.GetGameState
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
import kotlin.uuid.Uuid

/**
 * The production [GameClient], over the same [BridgeClient] singleton the lobby and table
 * clients ride — one socket, three feature clients.
 *
 * **Actions** mint a `requestId`, send the matching 0051 request through [BridgeClient.request] (the
 * erased `request<ServerMessage>` seam, so no `:protocol` type crosses the ABI), and map the correlated
 * `GameActionResult`: `ok = true` → success; `ok = false` → a [Result.failure] carrying either
 * [GameSessionGoneFailure] (the bridge had no session, so the server was never asked) or a
 * plain [GameActionFailure] with the server's reason. A transport failure thrown by `request` (no
 * session, timeout, drop) is captured as a failed [Result] rather than propagated.
 *
 * **[observeGame]** merges three sources into a single folding collector, so the held state is mutated by
 * exactly one coroutine and no lock is needed: the [ServerPushSource] stream folded by [GameEventFold]
 * for this game, a re-sync trigger on each return to [ConnectionState.Connected] that re-emits the held
 * state, and a **targeted read** ([refreshGame]) issued on open and on each such
 * return. Unlike the table client there is still **no periodic poll**: the two triggers are "the board
 * opened" and "the socket came back", and nothing else changes the answer, so a timer would ask the
 * bridge the same question forever.
 *
 * The read is answered by the *bridge*, not by XMage — upstream has no verb that reads a `GameView` and
 * re-joining a running game does not resync, which is why a targeted read is the only way to get one. The
 * bridge's parked-session buffer still carries the pushes produced during a socket gap; the
 * read is what covers the gap in which the game produced **none**, which is when a reconnecting player
 * would otherwise sit in front of an empty board until the opponent moved.
 *
 * @param newRequestId how a correlation id is minted (a UUID in production; overridable so a test can
 *   assert the exact id sent).
 */
internal class DefaultGameClient(
    private val bridgeClient: BridgeClient,
    private val pushSource: ServerPushSource,
    private val connectionState: StateFlow<@JvmSuppressWildcards ConnectionState>,
    private val newRequestId: () -> String = { Uuid.random().toString() },
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

    // --- reading -------------------------------------------------------------------------------------

    override suspend fun refreshGame(gameId: String): Result<GameSnapshot> =
        readSnapshot(gameId).map { reply ->
            GameSnapshot(
                // A standalone state: only the snapshot-owned fields, applied onto a blank one. A read
                // says what the board looks like, never that the server is waiting on us — the bridge
                // caches the `GameView`, and a prompt is not part of it.
                state = GameViewMapper.apply(GameState(gameId), reply.state),
                capturedAtEpochMs = reply.capturedAtEpochMs,
            )
        }

    /**
     * The wire half of the read, shared by [refreshGame] and [observeGame] (which needs the snapshot
     * itself so it can apply it to the state it already holds rather than to a blank one).
     *
     * A [GameStateUnavailable] becomes a typed failure rather than an empty success: an all-defaults
     * `GameStateView` is a legal board, so "nothing to show" *must* be a different shape from "here is
     * the board", or every caller renders the absence as truth.
     */
    private suspend fun readSnapshot(gameId: String): Result<GameStateSnapshot> {
        val id = newRequestId()
        return try {
            when (val reply = bridgeClient.request<ServerMessage>(GetGameState(gameId = gameId, requestId = id), id)) {
                is GameStateSnapshot -> Result.success(reply)
                is GameStateUnavailable ->
                    Result.failure(
                        GameStateUnavailableFailure(
                            gameId = reply.gameId,
                            reason = reply.reason.toReason(),
                            detail = reply.detail,
                        ),
                    )

                else -> Result.failure(GameActionFailure("game: unexpected reply ${reply::class.simpleName}"))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun GameStateUnavailableCode.toReason(): GameStateUnavailableReason =
        when (this) {
            GameStateUnavailableCode.NO_STATE_YET -> GameStateUnavailableReason.NoStateYet
            GameStateUnavailableCode.SESSION_GONE -> GameStateUnavailableReason.SessionGone
        }

    // --- observing -----------------------------------------------------------------------------------

    override fun observeGame(
        gameId: String,
        seed: GameState,
    ): Flow<GameState> =
        callbackFlow {
            var state = seed

            // The single read trigger every source funnels into, CONFLATED exactly as the
            // table client's is: at most one token is ever pending and the reader below suspends inside
            // its own branch, so a resume that lands while an open-read is in flight collapses into one
            // follow-up read rather than a second concurrent one. There is deliberately **no** timer
            // feeding it: the two triggers are "the board opened" and "the socket came back", and
            // nothing else. A poll would ask the bridge the same question forever for no new answer.
            val reads = Channel<Unit>(Channel.CONFLATED)

            trySend(state)

            // Every source funnels into one collector: `merge` serialises them downstream, so the held
            // `state` is read/written by exactly one coroutine — no lock, no race.
            val pushes = pushSource.serverPushes.map<ServerMessage, Intent> { Intent.Push(it) }
            val resyncs = connectionState.reEstablishments().map { Intent.Resync }
            val snapshots =
                reads
                    .receiveAsFlow()
                    .mapNotNull { readSnapshot(gameId).getOrNull()?.let { Intent.Snapshot(it) } }

            merge(pushes, resyncs, snapshots)
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
                        // re-sync) so a collector that had stopped rendering is not stranded, *and* read
                        // the board — the buffered pushes the bridge drains into the re-bound socket
                        // cover a game that moved during the gap, but nothing covers a game that did
                        // not, and that is precisely when a reconnecting player sees an empty board.
                        Intent.Resync -> {
                            trySend(state)
                            reads.trySend(Unit)
                        }

                        // A completed read. The snapshot is applied exactly as a pushed one is —
                        // replacing every field a `GameView` owns and touching none it does not, so the
                        // last narration and a terminal result survive it. The outstanding prompt is the
                        // one exception: `intent.reply.prompt` is this session's last-cached
                        // question, and GameViewMapper.apply *restores* it when non-null (never clears an
                        // existing one when it is null — see that function's KDoc). A finished game is
                        // left alone: re-applying a board to it would resurrect one that no longer exists.
                        is Intent.Snapshot ->
                            if (!state.isOver) {
                                val next = GameViewMapper.apply(state, intent.reply.state, intent.reply.prompt)
                                if (next != state) {
                                    state = next
                                    trySend(next)
                                }
                            }
                    }
                }.launchIn(this)

            // Read the board once as the game opens. Upstream cannot answer this - there is
            // no request the bridge could answer — which is why a client re-opening a running game saw
            // nothing until the next push.
            reads.trySend(Unit)

            awaitClose { reads.close() }
        }

    /**
     * An `observeGame` input: a server push to fold, a resume that re-syncs the current state and
     * triggers a read, or a completed read carrying the bridge's held snapshot.
     */
    private sealed interface Intent {
        data class Push(
            val message: ServerMessage,
        ) : Intent

        data object Resync : Intent

        data class Snapshot(
            val reply: GameStateSnapshot,
        ) : Intent
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
                // the distinction, on the game verbs: SESSION_GONE means the bridge had nothing
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
