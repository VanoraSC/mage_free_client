package magefree.bridge.mapping

import mage.constants.ManaType
import mage.constants.PlayerAction
import mage.remote.SessionImpl
import magefree.protocol.GameActionCode
import magefree.protocol.GameActionResult
import magefree.protocol.GameFailureCode
import magefree.protocol.ManaTypeCode
import magefree.protocol.PlayerActionCode
import java.util.UUID

/**
 * The in-game dispatch+map boundary — the sibling of [TableRelay] for the **play** side.
 * Each method dispatches one app request to the matching `SessionImpl` verb and maps its bare `boolean`
 * back to a structured [GameActionResult]. The `mage.*` argument construction (`ManaType`,
 * `PlayerAction`) stays **inside** this package, so no upstream shape crosses the wire.
 *
 * These are **blocking** JBoss-remoting calls; callers ([magefree.bridge.xmage.XMageSession]) invoke
 * them on `Dispatchers.IO`. This layer does not catch [mage.remote.MageRemoteException] — a transport
 * failure propagates and the caller decides the reply.
 *
 * **Result semantics.** Upstream's game verbs all return `boolean`, and a `false` means one of two very
 * different things: the server considered the request and declined it, or `SessionImpl` was not
 * connected and never asked. This layer maps a `false` to a [GameFailureCode.REFUSED] result; the
 * caller, which knows whether the session is live, is what turns a dead session into
 * [GameFailureCode.SESSION_GONE] (the convention). Either way a failure is a **typed result**,
 * never a silent drop — a swallowed reply in a game means the player waits forever for a prompt that
 * was already answered.
 */
public object GameRelay {
    /** Takes the seat in [gameId] (`SessionImpl.joinGame`) — after this the server pushes its callbacks. */
    public fun joinGame(
        session: SessionImpl,
        gameId: UUID,
    ): GameActionResult = resultOf(GameActionCode.JOIN_GAME, session.joinGame(gameId))

    /** Spectates [gameId] (`SessionImpl.watchGame`). */
    public fun watchGame(
        session: SessionImpl,
        gameId: UUID,
    ): GameActionResult = resultOf(GameActionCode.WATCH_GAME, session.watchGame(gameId))

    /** Quits the match [gameId] belongs to (`SessionImpl.quitMatch`) — a concession. */
    public fun quitMatch(
        session: SessionImpl,
        gameId: UUID,
    ): GameActionResult = resultOf(GameActionCode.QUIT_MATCH, session.quitMatch(gameId))

    /** Stops spectating [gameId] (`SessionImpl.stopWatching`). */
    public fun stopWatching(
        session: SessionImpl,
        gameId: UUID,
    ): GameActionResult = resultOf(GameActionCode.STOP_WATCHING, session.stopWatching(gameId))

    /** Answers the outstanding prompt in [gameId] with an object id (`SessionImpl.sendPlayerUUID`). */
    public fun sendPlayerUuid(
        session: SessionImpl,
        gameId: UUID,
        value: UUID,
    ): GameActionResult = resultOf(GameActionCode.SEND_UUID, session.sendPlayerUUID(gameId, value))

    /** Answers the outstanding prompt in [gameId] with a yes/no (`SessionImpl.sendPlayerBoolean`). */
    public fun sendPlayerBoolean(
        session: SessionImpl,
        gameId: UUID,
        value: Boolean,
    ): GameActionResult = resultOf(GameActionCode.SEND_BOOLEAN, session.sendPlayerBoolean(gameId, value))

    /** Answers the outstanding prompt in [gameId] with a number (`SessionImpl.sendPlayerInteger`). */
    public fun sendPlayerInteger(
        session: SessionImpl,
        gameId: UUID,
        value: Int,
    ): GameActionResult = resultOf(GameActionCode.SEND_INTEGER, session.sendPlayerInteger(gameId, value))

    /** Answers the outstanding prompt in [gameId] with text (`SessionImpl.sendPlayerString`). */
    public fun sendPlayerString(
        session: SessionImpl,
        gameId: UUID,
        value: String,
    ): GameActionResult = resultOf(GameActionCode.SEND_STRING, session.sendPlayerString(gameId, value))

    /**
     * Unlocks [manaType] from [playerId]'s mana pool in [gameId] (`SessionImpl.sendPlayerManaType`).
     * [playerId] is the pool's owner — normally the viewer, but a player whose turn is under the
     * viewer's control is possible, which is why upstream takes it explicitly.
     */
    public fun sendPlayerManaType(
        session: SessionImpl,
        gameId: UUID,
        playerId: UUID,
        manaType: ManaTypeCode,
    ): GameActionResult = resultOf(GameActionCode.SEND_MANA_TYPE, session.sendPlayerManaType(gameId, playerId, manaTypeOf(manaType)))

    /**
     * Performs an out-of-band player action in [gameId] (`SessionImpl.sendPlayerAction`). [data] is
     * upstream's opaque `Object` payload: an `Int` for `ROLLBACK_TURNS`, a `String` for the text-keyed
     * auto-answer actions, `null` for everything else.
     */
    public fun sendPlayerAction(
        session: SessionImpl,
        gameId: UUID,
        action: PlayerActionCode,
        data: Any? = null,
    ): GameActionResult = resultOf(GameActionCode.PLAYER_ACTION, session.sendPlayerAction(playerActionOf(action), gameId, data))

    /**
     * The pure boolean-verb result decision (unit-testable): `true` → an ok [GameActionResult]; `false`
     * → a failed one tagged [GameFailureCode.REFUSED] — a typed failure, never a silent drop.
     */
    public fun resultOf(
        action: GameActionCode,
        ok: Boolean,
    ): GameActionResult =
        GameActionResult(
            action = action,
            ok = ok,
            reason = if (ok) null else ACTION_DECLINED,
            failure = if (ok) null else GameFailureCode.REFUSED,
        )

    /**
     * Maps an app-schema mana type onto `mage.constants.ManaType`. Written as a total `when` rather than
     * `ManaType.valueOf(code.name)` so an upstream rename is a **compile** error here, at the boundary,
     * instead of a runtime failure in the middle of paying for a spell.
     */
    public fun manaTypeOf(code: ManaTypeCode): ManaType =
        when (code) {
            ManaTypeCode.WHITE -> ManaType.WHITE
            ManaTypeCode.BLUE -> ManaType.BLUE
            ManaTypeCode.BLACK -> ManaType.BLACK
            ManaTypeCode.RED -> ManaType.RED
            ManaTypeCode.GREEN -> ManaType.GREEN
            ManaTypeCode.COLORLESS -> ManaType.COLORLESS
            ManaTypeCode.GENERIC -> ManaType.GENERIC
        }

    /** Maps an app-schema player action onto `mage.constants.PlayerAction`, as a total `when` (see [manaTypeOf]). */
    public fun playerActionOf(code: PlayerActionCode): PlayerAction =
        when (code) {
            PlayerActionCode.PASS_PRIORITY_UNTIL_MY_NEXT_TURN -> PlayerAction.PASS_PRIORITY_UNTIL_MY_NEXT_TURN
            PlayerActionCode.PASS_PRIORITY_UNTIL_TURN_END_STEP -> PlayerAction.PASS_PRIORITY_UNTIL_TURN_END_STEP
            PlayerActionCode.PASS_PRIORITY_UNTIL_NEXT_MAIN_PHASE -> PlayerAction.PASS_PRIORITY_UNTIL_NEXT_MAIN_PHASE
            PlayerActionCode.PASS_PRIORITY_UNTIL_NEXT_TURN -> PlayerAction.PASS_PRIORITY_UNTIL_NEXT_TURN
            PlayerActionCode.PASS_PRIORITY_UNTIL_NEXT_TURN_SKIP_STACK -> PlayerAction.PASS_PRIORITY_UNTIL_NEXT_TURN_SKIP_STACK
            PlayerActionCode.PASS_PRIORITY_UNTIL_STACK_RESOLVED -> PlayerAction.PASS_PRIORITY_UNTIL_STACK_RESOLVED
            PlayerActionCode.PASS_PRIORITY_UNTIL_END_STEP_BEFORE_MY_NEXT_TURN ->
                PlayerAction.PASS_PRIORITY_UNTIL_END_STEP_BEFORE_MY_NEXT_TURN
            PlayerActionCode.PASS_PRIORITY_CANCEL_ALL_ACTIONS -> PlayerAction.PASS_PRIORITY_CANCEL_ALL_ACTIONS
            PlayerActionCode.HOLD_PRIORITY -> PlayerAction.HOLD_PRIORITY
            PlayerActionCode.UNHOLD_PRIORITY -> PlayerAction.UNHOLD_PRIORITY
            PlayerActionCode.CONCEDE -> PlayerAction.CONCEDE
            PlayerActionCode.UNDO -> PlayerAction.UNDO
            PlayerActionCode.ROLLBACK_TURNS -> PlayerAction.ROLLBACK_TURNS
            PlayerActionCode.MANA_AUTO_PAYMENT_ON -> PlayerAction.MANA_AUTO_PAYMENT_ON
            PlayerActionCode.MANA_AUTO_PAYMENT_OFF -> PlayerAction.MANA_AUTO_PAYMENT_OFF
            PlayerActionCode.MANA_AUTO_PAYMENT_RESTRICTED_ON -> PlayerAction.MANA_AUTO_PAYMENT_RESTRICTED_ON
            PlayerActionCode.MANA_AUTO_PAYMENT_RESTRICTED_OFF -> PlayerAction.MANA_AUTO_PAYMENT_RESTRICTED_OFF
            PlayerActionCode.USE_FIRST_MANA_ABILITY_ON -> PlayerAction.USE_FIRST_MANA_ABILITY_ON
            PlayerActionCode.USE_FIRST_MANA_ABILITY_OFF -> PlayerAction.USE_FIRST_MANA_ABILITY_OFF
            PlayerActionCode.TRIGGER_AUTO_ORDER_ABILITY_FIRST -> PlayerAction.TRIGGER_AUTO_ORDER_ABILITY_FIRST
            PlayerActionCode.TRIGGER_AUTO_ORDER_ABILITY_LAST -> PlayerAction.TRIGGER_AUTO_ORDER_ABILITY_LAST
            PlayerActionCode.TRIGGER_AUTO_ORDER_NAME_FIRST -> PlayerAction.TRIGGER_AUTO_ORDER_NAME_FIRST
            PlayerActionCode.TRIGGER_AUTO_ORDER_NAME_LAST -> PlayerAction.TRIGGER_AUTO_ORDER_NAME_LAST
            PlayerActionCode.TRIGGER_AUTO_ORDER_RESET_ALL -> PlayerAction.TRIGGER_AUTO_ORDER_RESET_ALL
            PlayerActionCode.RESET_AUTO_SELECT_REPLACEMENT_EFFECTS -> PlayerAction.RESET_AUTO_SELECT_REPLACEMENT_EFFECTS
            PlayerActionCode.REQUEST_AUTO_ANSWER_ID_YES -> PlayerAction.REQUEST_AUTO_ANSWER_ID_YES
            PlayerActionCode.REQUEST_AUTO_ANSWER_ID_NO -> PlayerAction.REQUEST_AUTO_ANSWER_ID_NO
            PlayerActionCode.REQUEST_AUTO_ANSWER_TEXT_YES -> PlayerAction.REQUEST_AUTO_ANSWER_TEXT_YES
            PlayerActionCode.REQUEST_AUTO_ANSWER_TEXT_NO -> PlayerAction.REQUEST_AUTO_ANSWER_TEXT_NO
            PlayerActionCode.REQUEST_AUTO_ANSWER_RESET_ALL -> PlayerAction.REQUEST_AUTO_ANSWER_RESET_ALL
            PlayerActionCode.VIEW_LIMITED_DECK -> PlayerAction.VIEW_LIMITED_DECK
            PlayerActionCode.VIEW_SIDEBOARD -> PlayerAction.VIEW_SIDEBOARD
        }

    /** The reason paired with a [GameFailureCode.REFUSED]; the app renders its own wording. */
    public const val ACTION_DECLINED: String = "the server declined the action"
}
