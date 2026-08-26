package magefree.bridge.mapping.game

import mage.view.GameClientMessage
import mage.view.GameView
import mage.view.TableClientMessage
import magefree.bridge.mapping.GameViewMapper
import magefree.protocol.GameError
import magefree.protocol.GameInformed
import magefree.protocol.GameOver
import magefree.protocol.GameStarted
import magefree.protocol.GameStateUpdated
import magefree.protocol.WatchingGame
import java.util.UUID

/*
 * The per-callback mappers for the **non-prompt** in-game pushes — the game-side siblings
 * of `magefree.bridge.mapping.table.*`. Each is pure and deterministic, takes the callback's already
 * decompressed payload plus the callback's own object id (which *is* the game id for every game
 * callback: see `mage.server.game.GameSessionWatcher`/`GameSessionPlayer`, which pass `game.getId()`),
 * and yields one app-schema `ServerMessage`.
 *
 * The payload types differ per callback and are **not** uniform — this is the upstream shape, not a
 * simplification we chose:
 * - `GAME_INIT` / `GAME_UPDATE` carry a bare `mage.view.GameView`;
 * - `GAME_UPDATE_AND_INFORM` / `GAME_INFORM_PERSONAL` / `GAME_OVER` carry a `GameClientMessage`;
 * - `GAME_ERROR` carries a bare `String`;
 * - `WATCHGAME` carries a `TableClientMessage`.
 *
 * **`GAME_REDRAW_GUI` is deliberately mapped to nothing.** Upstream classifies it
 * `ClientCallbackType.CLIENT_SIDE_EVENT` and the desktop handler's whole body is `panel.updateGame()` —
 * a repaint of already-held state (its documented purpose is "re-draw game's gui elements like attack
 * arrows … uses for client side only"). It carries no payload we need and no state the app does not
 * already have from the last snapshot, so [magefree.bridge.mapping.CallbackMapper] leaves it unmapped
 * and the relay drops it. Mapping it would put an event on the wire that means nothing to a client that
 * does not draw attack arrows in Swing.
 */

/**
 * `GAME_INIT` → [GameStarted]. Fired once the seat (or watcher) is attached to the game; its `GameView`
 * is the first full snapshot and, for a player, already carries the opening hand.
 */
public object GameStartedMapper {
    /** Maps [view] for the game [gameId] to its app-schema [GameStarted]. */
    public fun map(
        gameId: UUID?,
        view: GameView,
    ): GameStarted = GameStarted(gameId = gameId.asGameId(), state = GameViewMapper.map(view))
}

/** `GAME_UPDATE` → [GameStateUpdated]. A fresh whole snapshot; it replaces what the app holds. */
public object GameStateUpdatedMapper {
    /** Maps [view] for the game [gameId] to its app-schema [GameStateUpdated]. */
    public fun map(
        gameId: UUID?,
        view: GameView,
    ): GameStateUpdated = GameStateUpdated(gameId = gameId.asGameId(), state = GameViewMapper.map(view))
}

/**
 * `GAME_UPDATE_AND_INFORM` / `GAME_INFORM_PERSONAL` → [GameInformed]. Both carry a snapshot plus a line
 * of narration and differ only in audience, which [personal] records: the personal form is the one the
 * desktop client raises as a modal for this seat alone.
 */
public object GameInformedMapper {
    /** Maps [message] for the game [gameId] to its app-schema [GameInformed]. */
    public fun map(
        gameId: UUID?,
        message: GameClientMessage,
        personal: Boolean,
    ): GameInformed =
        GameInformed(
            gameId = gameId.asGameId(),
            message = message.message.orEmpty(),
            state = message.gameView?.let(GameViewMapper::map),
            personal = personal,
        )
}

/** `GAME_OVER` → [GameOver]: the server's own end-of-game line plus the final snapshot. */
public object GameOverMapper {
    /** Maps [message] for the game [gameId] to its app-schema [GameOver]. */
    public fun map(
        gameId: UUID?,
        message: GameClientMessage,
    ): GameOver =
        GameOver(
            gameId = gameId.asGameId(),
            message = message.message?.takeIf { it.isNotBlank() },
            state = message.gameView?.let(GameViewMapper::map),
        )
}

/**
 * `GAME_ERROR` → [GameError]. The payload is a bare message string — there is no `GameView` with it, so
 * the app keeps the last snapshot it had and surfaces the error alongside it.
 */
public object GameErrorMapper {
    /** Maps the error [message] for the game [gameId] to its app-schema [GameError]. */
    public fun map(
        gameId: UUID?,
        message: String?,
    ): GameError = GameError(gameId = gameId.asGameId(), message = message.orEmpty())
}

/**
 * `WATCHGAME` → [WatchingGame]. Upstream's `User.ccWatchGame` fires it with the **game id** as the
 * callback's object id and a `TableClientMessage` naming the table, so both halves are carried here.
 * It has no state; the snapshots follow as `GAME_INIT`/`GAME_UPDATE`.
 */
public object WatchingGameMapper {
    /** Maps [message] for the game [gameId] to its app-schema [WatchingGame]. */
    public fun map(
        gameId: UUID?,
        message: TableClientMessage,
    ): WatchingGame =
        WatchingGame(
            gameId = gameId.asGameId(),
            tableId = message.currentTableId?.toString(),
            parentTableId = message.parentTableId?.toString(),
        )
}

/** A callback's object id as the protocol's game id; `""` when upstream sent none (it always does). */
internal fun UUID?.asGameId(): String = this?.toString() ?: ""
