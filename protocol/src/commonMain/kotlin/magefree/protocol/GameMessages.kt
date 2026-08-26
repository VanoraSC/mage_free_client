package magefree.protocol

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder

/*
 * In-game play: the app-schema projection of XMage's game protocol. Where the table messages
 * carried a **table** to the moment a match begins ([MatchStarting]), this carries the **game** itself.
 *
 * **The shape of the upstream protocol.** It is "the server asks a typed question, the client answers
 * with a primitive":
 * - server → client, one payload type for everything — every in-game callback carries a
 *   `mage.view.GameClientMessage` (`gameView`, `cardsView1`, `cardsView2`, `message`, `flag`, `targets`,
 *   `min`, `max`, `options`, `choice`, `messages`), except `GAME_INIT`/`GAME_UPDATE` which carry a bare
 *   `GameView`, `GAME_CHOOSE_ABILITY` which carries an `AbilityPickerView`, `GAME_ERROR` which carries a
 *   `String`, and `WATCHGAME` which carries a `TableClientMessage`;
 * - client → server — `sendPlayerUUID/Boolean/Integer/String/ManaType(gameId, …)`,
 *   `sendPlayerAction(PlayerAction, gameId, data)`, `joinGame`/`watchGame`/`quitMatch`/`stopWatching`.
 *
 * **Two facts shape everything here.**
 * 1. **State is a snapshot, not a delta.** Every callback carries the *whole* `GameView`, so the app
 *    never reconciles deltas: each event/prompt below carries a complete [GameStateView] and the app
 *    replaces what it holds. This is a large simplification and it is deliberate.
 * 2. **The server owns the rules.** [GameStateView.playable] is projected from `GameView.canPlayObjects`
 *    — the server's own answer to "what may this player play right now". Nothing here re-derives
 *    legality, and nothing downstream may: a rules engine would dwarf everything built so far.
 *
 * **The one thing upstream cannot do, and the bridge can.** There is no "get game" verb upstream at all,
 * and re-joining a running game does not resync — so the board is push-only and a reconnecting client is
 * blind until the next push. [GetGameState] -> [GameStateSnapshot]/[GameStateUnavailable] answers that:
 * a read answered by the bridge from the last snapshot it relayed **to that session**, not by the server.
 * It is a replay of the server's own data, never a fabrication, and its absence is typed rather than
 * rendered as an empty board.
 *
 * **Why a closed, typed prompt set.** A generic "the server asked something" blob would leave the app
 * unable to know what a valid answer looks like. [GamePrompt] is therefore a closed discriminated set —
 * one subtype per upstream prompt callback — and each subtype's KDoc names the exact reply message that
 * answers it. That is what lets the client and the eventual UI be honest rather than guessing.
 *
 * As with every message file these extend the sealed [ClientMessage]/[ServerMessage] hierarchies rather
 * than forking them, so [ProtocolVersion]'s additive forward-compatibility rules keep holding, and
 * everything is pure Kotlin: no `mage.*` type appears, the projection happening at the single mapper
 * boundary (`magefree.bridge.mapping`).
 */

// ---------------------------------------------------------------------------------------------------
// Requests (app → bridge)
// ---------------------------------------------------------------------------------------------------

/**
 * App→bridge: take your seat in the game [gameId] (upstream `SessionImpl.joinGame`) — the call that
 * follows a [MatchStarting] and makes the server start pushing this session's game callbacks. The reply
 * is a [GameActionResult] for [GameActionCode.JOIN_GAME]; the game state itself arrives asynchronously
 * as a pushed [GameStarted].
 */
@Serializable
@SerialName("join_game")
public data class JoinGame(
    val gameId: String,
    val requestId: String? = null,
) : ClientMessage

/**
 * App→bridge: watch (spectate) the game [gameId] (upstream `SessionImpl.watchGame`). The reply is a
 * [GameActionResult] for [GameActionCode.WATCH_GAME]; a spectator receives the same events as a player
 * but its [GameStateView.viewerPlayerId] is null and it is never prompted.
 */
@Serializable
@SerialName("watch_game")
public data class WatchGame(
    val gameId: String,
    val requestId: String? = null,
) : ClientMessage

/**
 * App→bridge: quit the match the game [gameId] belongs to (upstream `SessionImpl.quitMatch`) — a
 * concession, not a pause. The reply is a [GameActionResult] for [GameActionCode.QUIT_MATCH].
 */
@Serializable
@SerialName("quit_match")
public data class QuitMatch(
    val gameId: String,
    val requestId: String? = null,
) : ClientMessage

/**
 * App→bridge: stop spectating the game [gameId] (upstream `SessionImpl.stopWatching`). The reply is a
 * [GameActionResult] for [GameActionCode.STOP_WATCHING].
 */
@Serializable
@SerialName("stop_watching")
public data class StopWatching(
    val gameId: String,
    val requestId: String? = null,
) : ClientMessage

/**
 * App→bridge: answer the outstanding prompt in [gameId] with an **object id** (upstream
 * `SessionImpl.sendPlayerUUID`) — the card/permanent/ability/target the player picked. [value] must be a
 * UUID string; a malformed one is refused as a failed [GameActionResult] rather than being guessed at.
 *
 * Answers [SelectPrompt] (play this object), [TargetPrompt] (choose this target), [ChooseAbilityPrompt]
 * (use this ability) and [PlayManaPrompt] (tap this source for mana).
 */
@Serializable
@SerialName("send_player_uuid")
public data class SendPlayerUuid(
    val gameId: String,
    val value: String,
    val requestId: String? = null,
) : ClientMessage

/**
 * App→bridge: answer the outstanding prompt in [gameId] with a **yes/no** (upstream
 * `SessionImpl.sendPlayerBoolean`).
 *
 * Answers [AskPrompt] (the answer), [ChoosePilePrompt] (`true` = the first pile), and — as `false` — the
 * "done / cancel / pass" arm of [SelectPrompt], [TargetPrompt], [ChooseAbilityPrompt], [PlayManaPrompt]
 * and [PlayXManaPrompt].
 */
@Serializable
@SerialName("send_player_boolean")
public data class SendPlayerBoolean(
    val gameId: String,
    val value: Boolean,
    val requestId: String? = null,
) : ClientMessage

/**
 * App→bridge: answer the outstanding prompt in [gameId] with a **number** (upstream
 * `SessionImpl.sendPlayerInteger`). Answers [GetAmountPrompt] and [PlayXManaPrompt].
 */
@Serializable
@SerialName("send_player_integer")
public data class SendPlayerInteger(
    val gameId: String,
    val value: Int,
    val requestId: String? = null,
) : ClientMessage

/**
 * App→bridge: answer the outstanding prompt in [gameId] with **text** (upstream
 * `SessionImpl.sendPlayerString`).
 *
 * Answers [ChooseChoicePrompt] (the chosen [GameChoiceOption.key]; prefix it with `#` to pick the
 * choice's *special* option) and [GetMultiAmountPrompt] (the amounts as a comma-separated list, one per
 * [GetMultiAmountPrompt.entries] item, in order). Upstream also accepts the literal `"special"` here as
 * the special-button arm of [SelectPrompt]/[PlayManaPrompt].
 */
@Serializable
@SerialName("send_player_string")
public data class SendPlayerString(
    val gameId: String,
    val value: String,
    val requestId: String? = null,
) : ClientMessage

/**
 * App→bridge: answer the outstanding [PlayManaPrompt] in [gameId] by naming a **mana type** to unlock
 * from [playerId]'s pool (upstream `SessionImpl.sendPlayerManaType`). [playerId] is the pool's owner —
 * usually the viewer, but a player whose turn is under your control is possible.
 */
@Serializable
@SerialName("send_player_mana_type")
public data class SendPlayerManaType(
    val gameId: String,
    val playerId: String,
    val manaType: ManaTypeCode,
    val requestId: String? = null,
) : ClientMessage

/**
 * App→bridge: perform an out-of-band **player action** in [gameId] (upstream
 * `SessionImpl.sendPlayerAction`) — the verbs that are not answers to a prompt: pass priority in its
 * various scopes, concede, undo, toggle auto-payment, and so on.
 *
 * [dataInt]/[dataText] carry the action's optional payload (upstream's `Object data`): [dataInt] for
 * [PlayerActionCode.ROLLBACK_TURNS] (how many turns), [dataText] for the text-keyed auto-answer actions.
 * At most one is meaningful for any action; both default to null. The reply is a [GameActionResult] for
 * [GameActionCode.PLAYER_ACTION].
 */
@Serializable
@SerialName("send_player_action")
public data class SendPlayerAction(
    val gameId: String,
    val action: PlayerActionCode,
    val dataInt: Int? = null,
    val dataText: String? = null,
    val requestId: String? = null,
) : ClientMessage

/**
 * App→bridge: **read** the current state of the game [gameId] — the targeted read that
 * `observeGame` has had no way to issue, and the game-side sibling of the [GetTable].
 *
 * There is no upstream verb behind this, and there never will be: XMage's `GameController.join` on an
 * already-running game only logs "rejoined", so a reconnecting client is blind until the *next* push —
 * which may be minutes away, or never while an opponent thinks. The bridge answers it from its own
 * **per-session** cache of the most recent snapshot the server pushed *to this session*, which is why the
 * request carries no room/scope: the only state it can name is this session's own.
 *
 * The reply is a [GameStateSnapshot] carrying the same [requestId], or a typed [GameStateUnavailable]
 * when this session has been sent no snapshot for that game (or has no session at all). It is never an
 * empty [GameStateView]: an empty board is indistinguishable from a real one and would be read as truth.
 */
@Serializable
@SerialName("get_game_state")
public data class GetGameState(
    val gameId: String,
    val requestId: String? = null,
) : ClientMessage

// ---------------------------------------------------------------------------------------------------
// Results (bridge → app, correlated by requestId)
// ---------------------------------------------------------------------------------------------------

/**
 * Bridge→app: the reply to a [GetGameState] — the most recent [GameStateView] the bridge
 * relayed to **this** session for [gameId], **verbatim**.
 *
 * It is the server's own snapshot replayed, never a reconstruction: nothing is merged across snapshots,
 * nothing is remembered about a card that has since been hidden, and no history is inferred (that is
 * accumulation is deliberately out of scope). The [state] here is byte-identical to the one that travelled in
 * the [GameStarted]/[GameStateUpdated]/[GameInformed]/[GamePrompted]/[GameOver] that produced it, so a
 * client folds it exactly as it folds a push.
 *
 * @property capturedAtEpochMs when the bridge captured this snapshot (wall clock, `System.currentTimeMillis`).
 *   Staleness is bounded and *knowable* rather than guessed: the snapshot is current as of the last push,
 *   and this says when that was. Null only from a bridge older than this.
 * @property prompt the outstanding question this session was last shown for [gameId], if the most recent
 *   thing the bridge relayed for it was a [GamePrompted] and nothing has superseded that prompt since
 *. `null` when the session's last relayed message carried no prompt (an ordinary state
 *   push, or no snapshot has ever included one), which is the ordinary case — not every rejoin lands
 *   mid-question. Safe to re-serve as live truth rather than a guess: XMage never re-asks a one-shot
 *   prompt on rejoin (`GameController.join`/`watch`), so if nothing newer
 *   replaced it, the server is still, genuinely, waiting on exactly this answer.
 */
@Serializable
@SerialName("game_state_snapshot")
public data class GameStateSnapshot(
    val gameId: String,
    val state: GameStateView,
    val capturedAtEpochMs: Long? = null,
    val requestId: String? = null,
    val prompt: GamePrompt? = null,
) : ServerMessage

/**
 * Bridge→app: the typed **"no state"** reply to a [GetGameState] — this session has never
 * been sent a snapshot for [gameId], the game has ended, or the socket has no bound session.
 *
 * A miss is a typed result, never a silent drop and never an empty [GameStateSnapshot]: an all-defaults
 * [GameStateView] renders as a real board with no players, no hand and nothing playable, which a client
 * would show — and a player would act on — as though it were the truth. [reason] is the *kind*, so the app
 * can branch; [detail] is the bridge's optional human-readable note.
 */
@Serializable
@SerialName("game_state_unavailable")
public data class GameStateUnavailable(
    val gameId: String,
    val reason: GameStateUnavailableCode,
    val detail: String? = null,
    val requestId: String? = null,
) : ServerMessage

/**
 * Why a [GameStateUnavailable] carries no state, as a **kind** rather than as prose — the same discipline
 * [GameFailureCode] applies to the action verbs. Additive-only within a protocol major.
 */
@Serializable
public enum class GameStateUnavailableCode {
    /**
     * The bridge holds no snapshot for that game on this session. Either nothing has been pushed yet
     * (the join has not produced its `GAME_INIT`), the id names a game this session is not in, or the
     * game ended and its cache entry was dropped. Waiting is what fixes the first; nothing fixes the
     * others, which is why they are not distinguished — the bridge genuinely cannot tell them apart.
     */
    NO_STATE_YET,

    /**
     * There is no usable session behind this socket, so there is not even a cache to look in. Nothing
     * was read, and re-authenticating — not retrying — is what fixes it (the convention).
     */
    SESSION_GONE,
}

/**
 * Bridge→app: the structured result of a game request. Every upstream game verb returns a bare
 * `boolean`, so — exactly as [TableActionResult] does for tables — a `false` becomes a
 * **typed result**, never a silent drop. [action] identifies which request this answers; [requestId]
 * echoes it; [failure] says what *kind* of failure it was so the app can distinguish "the server said
 * no" from "you have no session any more".
 */
@Serializable
@SerialName("game_action_result")
public data class GameActionResult(
    val action: GameActionCode,
    val ok: Boolean,
    val reason: String? = null,
    val failure: GameFailureCode? = null,
    val requestId: String? = null,
) : ServerMessage

/**
 * The game request a [GameActionResult] answers. Additive-only within a protocol major; an app that
 * does not recognise a code must treat it defensively (log and continue).
 */
public enum class GameActionCode {
    /** A [JoinGame]. */
    JOIN_GAME,

    /** A [WatchGame]. */
    WATCH_GAME,

    /** A [QuitMatch]. */
    QUIT_MATCH,

    /** A [StopWatching]. */
    STOP_WATCHING,

    /** A [SendPlayerUuid]. */
    SEND_UUID,

    /** A [SendPlayerBoolean]. */
    SEND_BOOLEAN,

    /** A [SendPlayerInteger]. */
    SEND_INTEGER,

    /** A [SendPlayerString]. */
    SEND_STRING,

    /** A [SendPlayerManaType]. */
    SEND_MANA_TYPE,

    /** A [SendPlayerAction]. */
    PLAYER_ACTION,
}

/**
 * Why a [GameActionResult] failed, as a **kind** rather than as prose — the game-side twin of
 * [TableFailureCode]. [GameActionResult.reason] is written for a human and can say
 * anything; the app cannot branch on it, but it can branch on this.
 */
@Serializable
public enum class GameFailureCode {
    /** The server was asked and refused — or the request was malformed (e.g. an unparseable id). */
    REFUSED,

    /**
     * There is no usable session to act through: nothing is bound to this socket, or the upstream
     * session behind it is gone. The request was never put to the server, and re-authenticating — not
     * retrying — is what fixes it.
     */
    SESSION_GONE,
}

// ---------------------------------------------------------------------------------------------------
// Events (bridge → app, server-pushed — no requestId correlation)
// ---------------------------------------------------------------------------------------------------

/**
 * Bridge→app: the game [gameId] is now yours to see — mapped from the upstream `GAME_INIT` callback,
 * which the server fires once the seat (or watcher) is attached. [state] is the first full snapshot: for
 * a player it already carries the opening hand.
 */
@Serializable
@SerialName("game_started")
public data class GameStarted(
    val gameId: String,
    val state: GameStateView,
    val requestId: String? = null,
) : ServerMessage

/**
 * Bridge→app: a fresh snapshot of the game [gameId] — mapped from the upstream `GAME_UPDATE` callback.
 * [state] **replaces** what the app holds; it is never a delta.
 */
@Serializable
@SerialName("game_state_updated")
public data class GameStateUpdated(
    val gameId: String,
    val state: GameStateView,
    val requestId: String? = null,
) : ServerMessage

/**
 * Bridge→app: a snapshot plus a line of narration for the game [gameId].
 *
 * Mapped from two upstream callbacks that differ only in audience: `GAME_UPDATE_AND_INFORM` (the game's
 * running feedback, [personal] = false) and `GAME_INFORM_PERSONAL` (a message meant for this seat alone,
 * which the desktop client raises as a modal, [personal] = true). Both carry the full [state].
 */
@Serializable
@SerialName("game_informed")
public data class GameInformed(
    val gameId: String,
    val message: String,
    val state: GameStateView? = null,
    val personal: Boolean = false,
    val requestId: String? = null,
) : ServerMessage

/**
 * Bridge→app: the server reported an error in the game [gameId] — mapped from the upstream `GAME_ERROR`
 * callback, whose payload is a bare message string (there is no state with it). This is the server's
 * own failure, not a protocol error, so it is a game event rather than a [ProtocolError].
 */
@Serializable
@SerialName("game_error")
public data class GameError(
    val gameId: String,
    val message: String,
    val requestId: String? = null,
) : ServerMessage

/**
 * Bridge→app: the game [gameId] has ended — mapped from the upstream `GAME_OVER` callback. [message] is
 * the server's own end-of-game line ("Xyz has won the game"), [state] the final snapshot.
 */
@Serializable
@SerialName("game_over")
public data class GameOver(
    val gameId: String,
    val message: String? = null,
    val state: GameStateView? = null,
    val requestId: String? = null,
) : ServerMessage

/**
 * Bridge→app: the server accepted a spectate and is now streaming the game [gameId] to this session —
 * mapped from the upstream `WATCHGAME` callback (whose payload names the table, the game id being the
 * callback's own object id). It carries no state; the snapshots follow as [GameStarted]/[GameStateUpdated].
 */
@Serializable
@SerialName("watching_game")
public data class WatchingGame(
    val gameId: String,
    val tableId: String? = null,
    val parentTableId: String? = null,
    val requestId: String? = null,
) : ServerMessage

/**
 * Bridge→app: **the server is waiting for you.** The single envelope for every upstream in-game prompt
 * callback: [state] is the snapshot at the moment of asking (mapped once, so [prompt] can reference it
 * by id rather than duplicating it) and [prompt] is *what is being asked*, as a closed typed set.
 *
 * There is at most one outstanding prompt per game: upstream blocks the game thread on the player's
 * response, so a new prompt means the previous one was answered (or superseded by the server itself).
 */
@Serializable
@SerialName("game_prompted")
public data class GamePrompted(
    val gameId: String,
    val state: GameStateView,
    val prompt: GamePrompt,
    val requestId: String? = null,
) : ServerMessage

// ---------------------------------------------------------------------------------------------------
// The prompt set (closed and typed — see the file header)
// ---------------------------------------------------------------------------------------------------

/**
 * What the server is asking, as a **closed discriminated set** — one subtype per upstream prompt
 * callback. Each subtype's KDoc names the reply message(s) that answer it, which is the whole point:
 * the app can render a prompt and know what a valid answer is without inspecting free-form text.
 *
 * Discriminated by the same `type` field as the envelope (see [ProtocolJson]); an unrecognised prompt
 * type decodes to [UnknownGamePrompt] rather than throwing, so a newer bridge can add one additively.
 */
@Serializable
public sealed interface GamePrompt {
    /** The server's own prompt text, as written for a human. */
    public val message: String
}

/**
 * `GAME_SELECT` — **you have priority**: choose something to do. [options] carries the server's UI hints
 * (button labels, the possible attackers/blockers when the select is a combat declaration).
 *
 * Answer with [SendPlayerUuid] (play/activate that object — it will be one of
 * [GameStateView.playable]), [SendPlayerBoolean] `false` (pass priority / done), or [SendPlayerString]
 * `"special"` when [GamePromptOptions.SPECIAL_BUTTON] is offered. A pass in a wider scope (until end of
 * turn, until my next turn, …) is a [SendPlayerAction] instead.
 */
@Serializable
@SerialName("select")
public data class SelectPrompt(
    override val message: String,
    val options: GamePromptOptions = GamePromptOptions(),
) : GamePrompt

/**
 * `GAME_TARGET` — choose a target (or a card from a shown set). [cards] is the candidate set the server
 * sent with the prompt (upstream `cardsView1`), [targetIds] the ids it marked as targetable/already
 * chosen (upstream `targets`), and [required] its `flag`: `false` means the choice may be declined.
 *
 * Answer with [SendPlayerUuid] (the chosen object) or [SendPlayerBoolean] `false` (done/cancel — only
 * legitimate when [required] is false, or when enough targets have already been chosen).
 */
@Serializable
@SerialName("target")
public data class TargetPrompt(
    override val message: String,
    val cards: List<GameCardView> = emptyList(),
    val targetIds: List<String> = emptyList(),
    val required: Boolean = false,
    val options: GamePromptOptions = GamePromptOptions(),
) : GamePrompt

/**
 * `GAME_ASK` — a yes/no question (a mulligan, an optional trigger, "do you want to …").
 * [GamePromptOptions.LEFT_BUTTON_TEXT]/[GamePromptOptions.RIGHT_BUTTON_TEXT] carry the server's own
 * labels for yes/no when it supplied them (e.g. "Mulligan"/"Keep").
 *
 * Answer with [SendPlayerBoolean].
 */
@Serializable
@SerialName("ask")
public data class AskPrompt(
    override val message: String,
    val options: GamePromptOptions = GamePromptOptions(),
) : GamePrompt

/**
 * `GAME_CHOOSE_ABILITY` — pick which of an object's abilities (or which mode) to use. [choices] is the
 * server's own ordered, pre-rendered list.
 *
 * Answer with [SendPlayerUuid] carrying the chosen [GameAbilityChoice.abilityId], or [SendPlayerBoolean]
 * `false` to cancel.
 */
@Serializable
@SerialName("choose_ability")
public data class ChooseAbilityPrompt(
    override val message: String,
    val choices: List<GameAbilityChoice> = emptyList(),
) : GamePrompt

/**
 * `GAME_CHOOSE_PILE` — pick one of two piles of cards ([pile1] / [pile2], upstream `cardsView1` and
 * `cardsView2`).
 *
 * Answer with [SendPlayerBoolean]: `true` picks [pile1], `false` picks [pile2].
 */
@Serializable
@SerialName("choose_pile")
public data class ChoosePilePrompt(
    override val message: String,
    val pile1: List<GameCardView> = emptyList(),
    val pile2: List<GameCardView> = emptyList(),
) : GamePrompt

/**
 * `GAME_CHOOSE_CHOICE` — pick one entry from a server-supplied list (a colour, a card name, a mode).
 * Mapped from upstream's `mage.choices.Choice`, which comes in two flavours: a plain set of strings and
 * a key→value map. Both are normalised here to [choices] of key/label pairs — for the plain flavour the
 * key *is* the label — so the app always answers with a key and never has to know which flavour it got.
 *
 * Answer with [SendPlayerString] carrying the chosen [GameChoiceOption.key]. When [specialText] is
 * non-null the choice also offers a "special" option, answered by the same key prefixed with `#`.
 */
@Serializable
@SerialName("choose_choice")
public data class ChooseChoicePrompt(
    override val message: String,
    val choices: List<GameChoiceOption> = emptyList(),
    val subMessage: String? = null,
    val required: Boolean = false,
    val specialText: String? = null,
) : GamePrompt

/**
 * `GAME_PLAY_MANA` — you owe mana: tap something for it, or cancel the spell/ability.
 *
 * Answer with [SendPlayerUuid] (the source to tap), [SendPlayerManaType] (unlock a type already in the
 * pool), [SendPlayerString] `"special"` when [GamePromptOptions.SPECIAL_BUTTON] is offered, or
 * [SendPlayerBoolean] `false` to cancel the payment.
 */
@Serializable
@SerialName("play_mana")
public data class PlayManaPrompt(
    override val message: String,
    val options: GamePromptOptions = GamePromptOptions(),
) : GamePrompt

/**
 * `GAME_PLAY_XMANA` — announce the value of X.
 *
 * Answer with [SendPlayerInteger] (the value) or [SendPlayerBoolean] `false` to cancel.
 */
@Serializable
@SerialName("play_x_mana")
public data class PlayXManaPrompt(
    override val message: String,
    val options: GamePromptOptions = GamePromptOptions(),
) : GamePrompt

/**
 * `GAME_GET_AMOUNT` — choose a number between [min] and [max] inclusive.
 *
 * Answer with [SendPlayerInteger].
 */
@Serializable
@SerialName("get_amount")
public data class GetAmountPrompt(
    override val message: String,
    val min: Int = 0,
    val max: Int = 0,
    val options: GamePromptOptions = GamePromptOptions(),
) : GamePrompt

/**
 * `GAME_GET_MULTI_AMOUNT` — distribute a total between several entries (damage among blockers, counters
 * among permanents). Each [entries] item carries its own label and per-entry bounds; [min]/[max] bound
 * the **total**.
 *
 * Answer with [SendPlayerString] carrying the amounts as a comma-separated list, one per entry, in the
 * same order as [entries] (upstream parses exactly that form).
 */
@Serializable
@SerialName("get_multi_amount")
public data class GetMultiAmountPrompt(
    override val message: String,
    val entries: List<GameMultiAmountEntry> = emptyList(),
    val min: Int = 0,
    val max: Int = 0,
    val options: GamePromptOptions = GamePromptOptions(),
) : GamePrompt

/**
 * A [GamePrompt] subtype this build does not recognise — the prompt-level twin of [UnknownServerMessage]
 *. Deserialize-only: encoding one throws, so a fabricated prompt can never reach the
 * wire. A consumer that receives one must **not** answer it (it cannot know what a valid reply is); it
 * logs the [type] and waits for the next push.
 */
@Serializable(with = UnknownGamePrompt.Serializer::class)
public data class UnknownGamePrompt(
    /** The unrecognised discriminator value, when the JSON carried one; `null` otherwise. */
    val type: String?,
) : GamePrompt {
    override val message: String get() = "unrecognised prompt"

    internal object Serializer : KSerializer<UnknownGamePrompt> {
        override val descriptor: SerialDescriptor =
            buildClassSerialDescriptor("magefree.protocol.UnknownGamePrompt")

        override fun serialize(
            encoder: Encoder,
            value: UnknownGamePrompt,
        ): Nothing = throw SerializationException(UNKNOWN_PROMPT_DESERIALIZE_ONLY)

        override fun deserialize(decoder: Decoder): UnknownGamePrompt = deserializerFor(null).deserialize(decoder)
    }

    public companion object {
        /**
         * A [DeserializationStrategy] that consumes the whole unknown prompt object and yields an
         * [UnknownGamePrompt] carrying [type]. Used by [ProtocolJson]'s polymorphic default.
         */
        internal fun deserializerFor(type: String?): DeserializationStrategy<UnknownGamePrompt> =
            object : DeserializationStrategy<UnknownGamePrompt> {
                override val descriptor: SerialDescriptor = Serializer.descriptor

                override fun deserialize(decoder: Decoder): UnknownGamePrompt {
                    // Consume the entire polymorphic object (discriminator + any additive fields).
                    (decoder as JsonDecoder).decodeJsonElement()
                    return UnknownGamePrompt(type)
                }
            }
    }
}

private const val UNKNOWN_PROMPT_DESERIALIZE_ONLY: String =
    "UnknownGamePrompt is deserialize-only and must never be encoded onto the wire."

// ---------------------------------------------------------------------------------------------------
// App-schema payloads
// ---------------------------------------------------------------------------------------------------

/**
 * A whole-game snapshot, projected from upstream's `mage.view.GameView`. **Replaces** the previous
 * snapshot wholesale — there are no deltas in this protocol.
 *
 * The three questions the app gates on, and the upstream field each comes from:
 * - **whose turn is it** — [activePlayerId]/[activePlayerName], from `GameView.getActivePlayerId()` and
 *   `getActivePlayerName()` (equivalently the player whose [GamePlayerView.active] is true, from
 *   `PlayerView.isActive()`).
 * - **does the viewer have priority** — [viewerHasPriority], from `PlayerView.hasPriority()` on the
 *   viewer's own `PlayerView`. `GameView` exposes only `getPriorityPlayerName()` and no priority *id*,
 *   so the per-player flag is the only id-safe source; [priorityPlayerName] carries the name for display.
 * - **what can the viewer play** — [playable], from `GameView.getCanPlayObjects()`. The server fills
 *   that list **only** for the player who currently has priority (and only when the view was built for
 *   them); for everyone else, and for spectators, it is absent and this list is empty. That absence is
 *   information, not a gap: it means "nothing for you to do right now".
 *
 * @property viewerPlayerId the seat this snapshot was built for, or `null` when the viewer is a
 *   spectator (upstream: `GameView.getMyPlayer()`, itself null for a watcher).
 * @property hand the viewer's own hand (upstream `myHand`); empty for a spectator.
 */
@Serializable
public data class GameStateView(
    val turn: Int = 0,
    val phase: TurnPhaseCode = TurnPhaseCode.UNKNOWN,
    val step: PhaseStepCode = PhaseStepCode.UNKNOWN,
    val activePlayerId: String? = null,
    val activePlayerName: String? = null,
    val priorityPlayerName: String? = null,
    val viewerPlayerId: String? = null,
    val viewerHasPriority: Boolean = false,
    val players: List<GamePlayerView> = emptyList(),
    val hand: List<GameCardView> = emptyList(),
    val stack: List<GameCardView> = emptyList(),
    val exile: List<GameZoneView> = emptyList(),
    val revealed: List<GameZoneView> = emptyList(),
    val combat: List<GameCombatGroupView> = emptyList(),
    val playable: List<GamePlayableObject> = emptyList(),
    val specialActionsAvailable: Boolean = false,
    val priorityTimeSeconds: Int = 0,
    val bufferTimeSeconds: Int = 0,
)

/**
 * One player in a [GameStateView], projected from `mage.view.PlayerView`.
 *
 * @property viewer true when this is the seat the snapshot was built for (upstream `getControlled()`).
 * @property active true when it is this player's turn (upstream `isActive()`).
 * @property hasPriority true when this player currently holds priority (upstream `hasPriority()`).
 * @property battlefield the permanents this player controls (upstream `getBattlefield()`).
 * @property counters the counters on the **player**, from upstream `getCounters()` — the same
 *   `{name, count}` shape [GameCounterView] carries for cards. Ten poison counters is a loss, so this
 *   is win-condition state; energy and experience are resources a player spends. The kind is a string
 *   because the set is open and grows with every release.
 *
 *   Order is not meaningful. Upstream builds the list from `player.getCountersAsCopy()`, and
 *   `mage.counters.Counters` extends `HashMap`, so this arrives in hash order — unlike the zone lists,
 *   which come from `LinkedHashMap`s. Look a counter up by [GameCounterView.name]; never index into it.
 * @property monarch true when this player is the monarch (upstream `isMonarch()`, itself
 *   `player.getId().equals(game.getMonarchId())`). The monarch draws a card each end step and loses
 *   the crown to whoever deals them combat damage.
 * @property initiative true when this player has the initiative (upstream `isInitiative()`, the same
 *   id comparison against `game.getInitiativeId()`).
 * @property designationNames the player's own designations, by name (upstream
 *   `getDesignationNames()`).
 *
 *   In practice this carries City's Blessing or nothing. `PlayerView` reads
 *   `player.getDesignations()`, and the only production caller of `Player.addDesignation` in XMage is
 *   `AscendAbility`. The Monarch, Initiative and Speed designations go through
 *   `GameState.addDesignation` — they are game-level, not per-player — which is why [monarch] and
 *   [initiative] are separate flags rather than entries in this list. "The Monarch" never appears here.
 */
@Serializable
public data class GamePlayerView(
    val playerId: String,
    val name: String,
    val life: Int = 0,
    val libraryCount: Int = 0,
    val handCount: Int = 0,
    val graveyardCount: Int = 0,
    val exileCount: Int = 0,
    val wins: Int = 0,
    val winsNeeded: Int = 0,
    val viewer: Boolean = false,
    val active: Boolean = false,
    val hasPriority: Boolean = false,
    val human: Boolean = true,
    val hasLeft: Boolean = false,
    val manaPool: GameManaPoolView = GameManaPoolView(),
    val battlefield: List<GamePermanentView> = emptyList(),
    val counters: List<GameCounterView> = emptyList(),
    val monarch: Boolean = false,
    val initiative: Boolean = false,
    val designationNames: List<String> = emptyList(),
)

/**
 * A card as the server rendered it, projected from `mage.view.CardView`. Deliberately thin: enough to
 * identify a printing ([setCode] + [collectorNumber], the pair the catalog resolves art by) and
 * to render a text-only representation. Anything richer is additive.
 *
 * **What a card *currently is*.** [cardTypes], [creature] and [counters] are the server's
 * own answer about the live game object, not about the printing: Earthbend makes a land a creature,
 * Ensoul Artifact makes an artifact one, crewing makes a Vehicle one until end of turn — and upstream
 * recomputes all three for every snapshot. They are what lets a client render "is this a creature right
 * now" without parsing [typeLine], which would be rules interpretation in the client.
 *
 * @property power / @property toughness the **current** values, after continuous effects, as *strings* —
 *   `*` is a real value (Tarmogoyf, Mortivore), so they are never numbers on this wire.
 * @property creature upstream `CardView.isCreature()`, which is `cardTypes.contains(CREATURE)` computed
 *   over the live object (verified against `mage-common-1.4.60`). Carried as its own field rather than
 *   re-derived downstream, so the server keeps owning the predicate.
 * @property counters every counter on the object, by name and count. **Not battlefield-only**: upstream
 *   populates them on `CardView` (from `Card.getCounters(game)`) as well as on `PermanentView`, so a
 *   card outside the battlefield can carry them too.
 * @property alternateName upstream `CardView.getAlternateName()`, threaded through
 *   unchanged, exactly as upstream's own client (`CardPanel.java`) treats it: a **catalog fact**, set
 *   unconditionally on any transformable/double-faced/flip/meld object regardless of which face is
 *   currently showing, to the name of its *other* face. It answers "does this have another face, and
 *   what's it called" — never "which face is up right now" (see [transformed] for that). `null` for an
 *   ordinary card with no other face.
 * @property transformed upstream `CardView.isTransformed()` — the live "is this permanent
 *   currently showing its back face" fact, set by upstream's own `CardView` constructor
 *   (`if (permanent.isTransformed()) transformed = true`) for any battlefield permanent, and inherited
 *   by `PermanentView` via its `super()` call. `false` for anything that is not a permanent, and for an
 *   untransformed permanent — only `true` once the permanent has actually flipped to its back face.
 *   This, not [alternateName], is the signal for which face's art to request.
 * @property targets what this object is pointing at, from upstream `CardView.getTargets()`
 * — the ids of every chosen target of a spell or ability on the stack, de-duplicated and in
 *   upstream's own stable order. **Ids into the same snapshot**, resolvable against any object in it:
 *   a battlefield permanent, a player, or **another entry on the [GameStateView.stack]**, because
 *   upstream resolves them through `game.getObject(uuid)` and puts them in one flat list with no
 *   per-kind branching. Empty for anything not targeting — a hand card, an ordinary permanent, an
 *   untargeted spell — because upstream only calls `addTargets` while building a stack object.
 *

 *   before these two fields were
 *   traced fully against upstream source: a transformed permanent stuck showing front art, a hand card
 *   showing its other face's art, and an untransformed permanent showing back-face art with no flip
 *   button — all from treating `alternateName != null` as "currently transformed," which upstream never
 *   does anywhere, including in its own client.
 */
@Serializable
public data class GameCardView(
    val id: String,
    val name: String,
    val setCode: String? = null,
    val collectorNumber: String? = null,
    val manaCost: String? = null,
    val typeLine: String? = null,
    val power: String? = null,
    val toughness: String? = null,
    val rules: List<String> = emptyList(),
    val faceDown: Boolean = false,
    val cardTypes: List<CardTypeCode> = emptyList(),
    val creature: Boolean = false,
    val counters: List<GameCounterView> = emptyList(),
    val alternateName: String? = null,
    val transformed: Boolean = false,
    val targets: List<String> = emptyList(),
)

/**
 * One counter on a card or permanent, projected from `mage.view.CounterView` — which is exactly
 * `{name, count}`, so there is nothing richer to model.
 *
 * **Generic by construction.** `+1/+1` and `-1/-1` are the common ones, but loyalty, charge, oil, stun
 * and hundreds more exist and new ones ship with every set, so the kind is carried as the server's own
 * [name] string and never as a closed set the app would have to keep enumerating.
 */
@Serializable
public data class GameCounterView(
    val name: String,
    val count: Int = 0,
)

/**
 * A card type as the server currently reports it (`mage.constants.CardType`), *after* continuous
 * effects — an animated land really does carry [CREATURE] here.
 *
 * Carried as the structured list rather than only as [GameCardView.creature] so that later work
 * (subtypes, supertypes, "why is this a creature") extend the same field instead of re-deriving from a
 * display string.
 *
 * **Unknown values decode to [UNKNOWN] rather than throwing** (see [Serializer]). The set below is
 * upstream's set at `mage-common-1.4.60`; upstream adds types (BATTLE and DUNGEON are recent), and a
 * newer bridge sending a type this build has never heard of must cost one list entry, not the whole
 * snapshot — the list form of the additive-compatibility promise in [ProtocolVersion].
 */
@Serializable(with = CardTypeCode.Serializer::class)
public enum class CardTypeCode {
    ARTIFACT,
    BATTLE,
    CONSPIRACY,
    CREATURE,
    DUNGEON,
    ENCHANTMENT,
    INSTANT,
    KINDRED,
    LAND,
    PHENOMENON,
    PLANE,
    PLANESWALKER,
    SCHEME,
    SORCERY,
    VANGUARD,

    /** A type this build does not know — either upstream added one, or the server sent nothing usable. */
    UNKNOWN,
    ;

    internal object Serializer : KSerializer<CardTypeCode> {
        override val descriptor: SerialDescriptor =
            PrimitiveSerialDescriptor("magefree.protocol.CardTypeCode", PrimitiveKind.STRING)

        override fun serialize(
            encoder: Encoder,
            value: CardTypeCode,
        ) {
            encoder.encodeString(value.name)
        }

        override fun deserialize(decoder: Decoder): CardTypeCode {
            val raw = decoder.decodeString()
            return entries.firstOrNull { it.name == raw } ?: UNKNOWN
        }
    }
}

/**
 * A permanent on the battlefield, projected from `mage.view.PermanentView` — a [card] plus the
 * battlefield-only state the UI must show.
 *
 * **Attachments travel in both directions, because upstream computes both.**
 * [attachedTo] says what this permanent is attached to; [attachments] says what is attached *to it*.
 * Neither is derivable from the other cheaply: without [attachments], a host would have to scan every
 * battlefield on every frame for anything pointing at it, on both players' boards — work the server
 * already did once per snapshot.
 *
 * @property attachedTo the object this permanent is attached to (upstream `getAttachedTo()`), or
 *   `null`. It may name a **player** as well as a permanent — a Curse attaches to a player — so
 *   [attachedToPermanent] is what says which.
 * @property attachments the ids of everything attached to this permanent (upstream `getAttachments()`),
 *   in upstream's order. Empty for the ordinary permanent that carries nothing.
 * @property attachedToPermanent upstream `isAttachedToPermanent()` — true when [attachedTo] resolved to
 *   a permanent in the game rather than to a player. Upstream computes it by actually looking the host
 *   up (`game.getPermanent(attachedTo)`), so it is `false` both for an unattached permanent and for one
 *   attached to a player.
 * @property attachedControllerDiffers upstream `isAttachedToDifferentlyControlledPermanent()` — your
 *   Aura on their creature. Note the exact semantics, which are narrower than the name suggests:
 *   upstream only computes it **inside** the "did the host resolve to a permanent" branch, so it is
 *   always `false` when [attachedToPermanent] is `false`, including for a Curse on an opposing player.
 *   It answers "is the host a permanent someone else controls", never "is the host someone else's".
 */
@Serializable
public data class GamePermanentView(
    val card: GameCardView,
    val tapped: Boolean = false,
    val flipped: Boolean = false,
    val phasedIn: Boolean = true,
    val summoningSickness: Boolean = false,
    val damage: Int = 0,
    val attachedTo: String? = null,
    val attachments: List<String> = emptyList(),
    val attachedToPermanent: Boolean = false,
    val attachedControllerDiffers: Boolean = false,
    val controlledByViewer: Boolean = false,
)

/** A named zone of cards (an exile zone, a revealed set), projected from `ExileView`/`RevealedView`. */
@Serializable
public data class GameZoneView(
    val name: String,
    val cards: List<GameCardView> = emptyList(),
    val zoneId: String? = null,
)

/**
 * One combat group, projected from `mage.view.CombatGroupView`: who is being attacked ([defenderId] /
 * [defenderName]), by what ([attackerIds]), and what is blocking ([blockerIds]). Ids reference
 * permanents already present in [GameStateView.players]' battlefields.
 */
@Serializable
public data class GameCombatGroupView(
    val defenderId: String? = null,
    val defenderName: String? = null,
    val blocked: Boolean = false,
    val attackerIds: List<String> = emptyList(),
    val blockerIds: List<String> = emptyList(),
)

/** A player's floating mana, projected from `mage.view.ManaPoolView`. */
@Serializable
public data class GameManaPoolView(
    val white: Int = 0,
    val blue: Int = 0,
    val black: Int = 0,
    val red: Int = 0,
    val green: Int = 0,
    val colorless: Int = 0,
)

/**
 * One entry of `GameView.canPlayObjects` — **the server's answer** to "may this be played right now".
 * [objectId] is the card/permanent, [abilityIds] the specific playable abilities on it (a land with two
 * mana abilities has two). The app must not infer playability from anything else.
 */
@Serializable
public data class GamePlayableObject(
    val objectId: String,
    val abilityIds: List<String> = emptyList(),
)

/** One option of a [ChooseAbilityPrompt]: the ability's [abilityId] and the server's rendered [text]. */
@Serializable
public data class GameAbilityChoice(
    val abilityId: String,
    val text: String,
)

/**
 * One option of a [ChooseChoicePrompt]. [key] is what a [SendPlayerString] reply carries; [label] is
 * what to show. For upstream's plain (non-keyed) `Choice` the two are equal.
 */
@Serializable
public data class GameChoiceOption(
    val key: String,
    val label: String,
)

/** One row of a [GetMultiAmountPrompt]: its label and its own bounds/default. */
@Serializable
public data class GameMultiAmountEntry(
    val message: String,
    val min: Int = 0,
    val max: Int = 0,
    val defaultValue: Int = 0,
)

/**
 * The prompt's upstream `options` map (`Map<String, Serializable>`), projected into two typed halves so
 * nothing arbitrary crosses the wire: [text] holds the scalar entries rendered as strings, [ids] the
 * entries that are collections of object ids. Well-known keys are named in the companion; an unknown key
 * is carried through rather than dropped, because the server may add hints we do not yet render.
 */
@Serializable
public data class GamePromptOptions(
    val text: Map<String, String> = emptyMap(),
    val ids: Map<String, List<String>> = emptyMap(),
) {
    public companion object {
        /** [text] key: the server's label for the affirmative/left button (e.g. "Mulligan"). */
        public const val LEFT_BUTTON_TEXT: String = "UI.left.btn.text"

        /** [text] key: the server's label for the negative/right button (e.g. "Keep", "Done"). */
        public const val RIGHT_BUTTON_TEXT: String = "UI.right.btn.text"

        /** [text] key: the label of an extra "special" button; its reply is `SendPlayerString("special")`. */
        public const val SPECIAL_BUTTON: String = "specialButton"

        /** [ids] key: the permanents that may be declared as attackers in this select. */
        public const val POSSIBLE_ATTACKERS: String = "possibleAttackers"

        /** [ids] key: the permanents that may be declared as blockers in this select. */
        public const val POSSIBLE_BLOCKERS: String = "possibleBlockers"

        /** [ids] key: the targets already chosen for the current target requirement. */
        public const val CHOSEN_TARGETS: String = "chosenTargets"

        /** [ids] key: the targets that may still be chosen. */
        public const val POSSIBLE_TARGETS: String = "possibleTargets"
    }
}

/** A kind of mana, mirroring `mage.constants.ManaType`. Additive-only. */
@Serializable
public enum class ManaTypeCode {
    /** White. */
    WHITE,

    /** Blue. */
    BLUE,

    /** Black. */
    BLACK,

    /** Red. */
    RED,

    /** Green. */
    GREEN,

    /** Colourless (`{C}`) — a real, payable type. */
    COLORLESS,

    /** Generic (`{1}`) — payable by any type; upstream never returns it from a pool. */
    GENERIC,
}

/** A turn phase, mirroring `mage.constants.TurnPhase`. [UNKNOWN] covers an absent/unmapped value. */
@Serializable
public enum class TurnPhaseCode {
    /** The beginning phase (untap/upkeep/draw). */
    BEGINNING,

    /** The precombat main phase. */
    PRECOMBAT_MAIN,

    /** The combat phase. */
    COMBAT,

    /** The postcombat main phase. */
    POSTCOMBAT_MAIN,

    /** The ending phase. */
    END,

    /** No phase reported, or one this build does not recognise. */
    UNKNOWN,
}

/** A step within a phase, mirroring `mage.constants.PhaseStep`. [UNKNOWN] covers an absent value. */
@Serializable
public enum class PhaseStepCode {
    /** Untap step. */
    UNTAP,

    /** Upkeep step. */
    UPKEEP,

    /** Draw step. */
    DRAW,

    /** Precombat main step. */
    PRECOMBAT_MAIN,

    /** Beginning of combat step. */
    BEGIN_COMBAT,

    /** Declare attackers step. */
    DECLARE_ATTACKERS,

    /** Declare blockers step. */
    DECLARE_BLOCKERS,

    /** First-strike combat damage step. */
    FIRST_COMBAT_DAMAGE,

    /** Combat damage step. */
    COMBAT_DAMAGE,

    /** End of combat step. */
    END_COMBAT,

    /** Postcombat main step. */
    POSTCOMBAT_MAIN,

    /** End step. */
    END_TURN,

    /** Cleanup step. */
    CLEANUP,

    /** No step reported, or one this build does not recognise. */
    UNKNOWN,
}

/**
 * An out-of-band player action, mirroring the in-game subset of `mage.constants.PlayerAction`. Names
 * match upstream deliberately, but the bridge maps each explicitly (a total `when`) so an upstream
 * rename is a **compile** error at the boundary rather than a runtime surprise.
 *
 * Tournament/draft/client-lifecycle actions are excluded: they are not in-game verbs (tournaments and drafts own the
 * first, the session protocol owns the second).
 */
public enum class PlayerActionCode {
    /** Pass priority until this player's next turn. */
    PASS_PRIORITY_UNTIL_MY_NEXT_TURN,

    /** Pass priority until the end step of the current turn. */
    PASS_PRIORITY_UNTIL_TURN_END_STEP,

    /** Pass priority until the next main phase. */
    PASS_PRIORITY_UNTIL_NEXT_MAIN_PHASE,

    /** Pass priority until the next turn. */
    PASS_PRIORITY_UNTIL_NEXT_TURN,

    /** Pass priority until the next turn, without stopping for the stack. */
    PASS_PRIORITY_UNTIL_NEXT_TURN_SKIP_STACK,

    /** Pass priority until the stack has resolved. */
    PASS_PRIORITY_UNTIL_STACK_RESOLVED,

    /** Pass priority until the end step before this player's next turn. */
    PASS_PRIORITY_UNTIL_END_STEP_BEFORE_MY_NEXT_TURN,

    /** Cancel every outstanding "pass until …" instruction. */
    PASS_PRIORITY_CANCEL_ALL_ACTIONS,

    /** Hold priority after the next spell/ability. */
    HOLD_PRIORITY,

    /** Release a held priority. */
    UNHOLD_PRIORITY,

    /** Concede this game. */
    CONCEDE,

    /** Undo the last action (when the server allows it). */
    UNDO,

    /** Roll the game back; the number of turns travels in [SendPlayerAction.dataInt]. */
    ROLLBACK_TURNS,

    /** Turn automatic mana payment on. */
    MANA_AUTO_PAYMENT_ON,

    /** Turn automatic mana payment off. */
    MANA_AUTO_PAYMENT_OFF,

    /** Restrict automatic mana payment to unambiguous cases. */
    MANA_AUTO_PAYMENT_RESTRICTED_ON,

    /** Lift the automatic-mana-payment restriction. */
    MANA_AUTO_PAYMENT_RESTRICTED_OFF,

    /** Use the first mana ability without asking. */
    USE_FIRST_MANA_ABILITY_ON,

    /** Stop using the first mana ability without asking. */
    USE_FIRST_MANA_ABILITY_OFF,

    /** Always order this triggered ability first, by ability. */
    TRIGGER_AUTO_ORDER_ABILITY_FIRST,

    /** Always order this triggered ability last, by ability. */
    TRIGGER_AUTO_ORDER_ABILITY_LAST,

    /** Always order this triggered ability first, by source name. */
    TRIGGER_AUTO_ORDER_NAME_FIRST,

    /** Always order this triggered ability last, by source name. */
    TRIGGER_AUTO_ORDER_NAME_LAST,

    /** Forget every automatic trigger-ordering preference. */
    TRIGGER_AUTO_ORDER_RESET_ALL,

    /** Forget every automatic replacement-effect selection. */
    RESET_AUTO_SELECT_REPLACEMENT_EFFECTS,

    /** Always answer yes to this exact optional effect (matched by id). */
    REQUEST_AUTO_ANSWER_ID_YES,

    /** Always answer no to this exact optional effect (matched by id). */
    REQUEST_AUTO_ANSWER_ID_NO,

    /** Always answer yes to optional effects with this text; the text travels in [SendPlayerAction.dataText]. */
    REQUEST_AUTO_ANSWER_TEXT_YES,

    /** Always answer no to optional effects with this text; the text travels in [SendPlayerAction.dataText]. */
    REQUEST_AUTO_ANSWER_TEXT_NO,

    /** Forget every automatic optional-effect answer. */
    REQUEST_AUTO_ANSWER_RESET_ALL,

    /** Ask the server to send the limited deck for viewing. */
    VIEW_LIMITED_DECK,

    /** Ask the server to send the sideboard for viewing. */
    VIEW_SIDEBOARD,
}
