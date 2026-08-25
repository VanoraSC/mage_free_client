package magefree.network.game

import magefree.protocol.GameError
import magefree.protocol.GameInformed
import magefree.protocol.GameOver
import magefree.protocol.GamePrompted
import magefree.protocol.GameStarted
import magefree.protocol.GameStateUpdated
import magefree.protocol.ServerMessage
import magefree.protocol.WatchingGame

/**
 * The **pure** reducer that folds story 0051's server-pushed game events into a [GameState] (story 0052)
 * — the game-side twin of 0037's `TableEventFold`, and for the same reason: a plain function with no
 * client, socket or coroutine dependency is exhaustively unit-testable over scripted sequences (game
 * start → phases → a prompt appears and clears → game over), independent of [GameClient].
 *
 * [fold] is a filter *and* a reducer: it returns the next [GameState] only for a game event belonging to
 * this game, and `null` for everything else — another game's push, a correlated reply, a lobby/table/chat
 * frame the same push side-channel carries — so `observeGame` emits exactly when a game event changes it.
 *
 * **Snapshot replace, never merge.** Every state-carrying event hands its whole snapshot to
 * [GameViewMapper.apply], which overwrites all the snapshot-owned fields. The only fields this fold
 * carries *across* events are the ones no snapshot contains: the outstanding prompt, the last narration,
 * the last error, the terminal result and the spectator confirmation.
 *
 * **How the prompt clears.** [GamePrompted] sets it; [GameStateUpdated] and [GameOver] clear it. Those
 * two are the pushes that mean "the game moved on", so a prompt surviving them would let a UI send an
 * answer to a question the server is no longer asking. [GameInformed] and [GameError] deliberately do
 * **not** clear it: upstream's `informOthers` excludes the very player it is waiting on, and `GAME_ERROR`
 * carries no state at all, so treating either as an answer would drop a live prompt on the floor.
 */
internal object GameEventFold {
    /**
     * Fold one server-pushed [message] into [state], or return `null` when it is not a game event for
     * this game (so the caller emits nothing). Handled events:
     * - [GameStarted] — `GAME_INIT`: the first snapshot. Applied like any other; it is not special-cased,
     *   because the server also fires it before the opening hand is dealt (so it can legitimately carry
     *   an empty hand, as `:bridge`'s `GameRelayIT` records).
     * - [GameStateUpdated] — `GAME_UPDATE`: a fresh snapshot, and the prompt (if any) is finished with.
     * - [GameInformed] — narration, with or without a snapshot; the outstanding prompt survives it.
     * - [GameError] — a server-reported error; no snapshot, so only [GameState.lastError] moves.
     * - [GameOver] — the terminal result, plus the final snapshot when one came with it.
     * - [GamePrompted] — the snapshot at the moment of asking, plus **the** outstanding question.
     * - [WatchingGame] — the server accepted a spectate; carries no state.
     */
    fun fold(
        state: GameState,
        message: ServerMessage,
    ): GameState? =
        when (message) {
            is GameStarted ->
                if (message.gameId != state.gameId) null else GameViewMapper.apply(state, message.state)

            is GameStateUpdated ->
                if (message.gameId != state.gameId) {
                    null
                } else {
                    GameViewMapper.apply(state, message.state).copy(prompt = null)
                }

            is GameInformed ->
                if (message.gameId != state.gameId) {
                    null
                } else {
                    // The snapshot is optional here (0051 models it as nullable); the narration is not.
                    val next = message.state?.let { GameViewMapper.apply(state, it) } ?: state
                    next.copy(lastMessage = GameMessage(text = message.message, isPersonal = message.personal))
                }

            is GameError ->
                if (message.gameId != state.gameId) null else state.copy(lastError = message.message)

            is GameOver ->
                if (message.gameId != state.gameId) {
                    null
                } else {
                    val next = message.state?.let { GameViewMapper.apply(state, it) } ?: state
                    next.copy(prompt = null, result = GameResult(message = message.message))
                }

            is GamePrompted ->
                if (message.gameId != state.gameId) {
                    null
                } else {
                    GameViewMapper.apply(state, message.state).copy(prompt = GameViewMapper.toPrompt(message.prompt))
                }

            is WatchingGame ->
                if (message.gameId != state.gameId) null else state.copy(isWatching = true)

            else -> null
        }
}
