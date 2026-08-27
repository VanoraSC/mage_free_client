package magefree.network.game

/*
 * The app-schema, **UI-free** projection of a running game — the game-side twin of
 * the `TableState`, and the layer every later board work reads.
 *
 * Two facts shape every type here, and neither is negotiable:
 *
 * 1. **State is a snapshot, not a delta.** Every in-game callback carries the *whole* `GameView`, so
 *    [GameState] is **replaced** per snapshot rather than reconciled. There is no merge logic anywhere in
 *    this package, deliberately: a field absent from a snapshot is absent, not stale-but-remembered.
 * 2. **The server owns the rules.** [GameState.playable] is the server's own `canPlayObjects` list and is
 *    the *only* source of "may I play this". Nothing here re-derives legality, and nothing above may.
 *
 * Every field carries a **named production producer** (verification standard 2); where a thing a UI might
 * want has no producer upstream, that is written down as such rather than invented — see
 * [GameState.priorityPlayerName] (there is no priority *id* upstream) and [GameState.hasSnapshot].
 *
 * No `:protocol` or `mage.*` type appears on anything in this file: the wire shapes stay confined to the
 * `internal` mapper/fold/client, exactly as the discipline requires.
 */

/**
 * One whole-game snapshot as the app holds it, folded from the pushed game events by [GameEventFold]
 * and observed via [GameClient.observeGame].
 *
 * **Where each UI-facing field comes from** (the reachability record, standard 2 — the upstream field is
 * named in each property's doc):
 * - *is it my turn* → [activePlayerId] vs [viewerPlayerId] (see [isViewerTurn]).
 * - *do I have priority* → [viewerHasPriority].
 * - *what can I play* → [playable] — and **only** [playable].
 * - *is there a prompt* → [prompt].
 *
 * @property gameId the game this state describes; the fold filters pushes by it.
 * @property turn the turn number (upstream `GameView.getTurn()`); `0` until the first snapshot lands.
 * @property phase the current phase (upstream `GameView.getPhase()`).
 * @property step the current step (upstream `GameView.getStep()`).
 * @property activePlayerId the id of the player whose turn it is (upstream `GameView.getActivePlayerId()`).
 * @property activePlayerName that player's display name (upstream `GameView.getActivePlayerName()`).
 * @property priorityPlayerName the **name** of the player holding priority (upstream
 *   `GameView.getPriorityPlayerName()`). Upstream exposes no priority *id* at all, so this is a display
 *   string and nothing may be keyed off it; use [viewerHasPriority] / [GamePlayer.hasPriority] instead.
 * @property viewerPlayerId the seat this snapshot was built for (upstream `GameView.getMyPlayer()`), or
 *   `null` for a spectator — see [isSpectator].
 * @property viewerHasPriority whether the viewer holds priority right now (upstream `PlayerView.hasPriority()`
 *   on the viewer's own `PlayerView`) — the id-safe answer to "is the game waiting on me".
 * @property players every seat in the game, in server order (upstream `GameView.getPlayers()`).
 * @property hand the viewer's own hand (upstream `GameView.getMyHand()`); empty for a spectator.
 * @property stack the stack, top last (upstream `GameView.getStack()`).
 * @property exile the exile zones (upstream `GameView.getExile()`).
 * @property revealed the revealed card sets (upstream `GameView.getRevealed()`).
 * @property combat the current combat groups (upstream `GameView.getCombat()`); empty outside combat.
 * @property playable **the server's own answer** to what the viewer may play (upstream
 *   `GameView.getCanPlayObjects()`). Populated **only** for the player who currently holds priority, and
 *   only on the view built for them: for everyone else, and for every spectator, it is empty. An empty
 *   list is therefore information — "nothing for you to do right now" — not a gap to paper over.
 * @property specialActionsAvailable whether the server offered special actions (upstream
 *   `GameView.getSpecial()`).
 * @property priorityTimeSeconds the viewer's remaining priority clock, in seconds (0 when untimed).
 * @property bufferTimeSeconds the viewer's remaining buffer clock, in seconds (0 when untimed).
 * @property prompt **the** outstanding question, or `null` when the server is not waiting on the viewer.
 *   Modelled as one prompt, not a queue: upstream blocks the game thread on the player's response, so a
 *   new prompt means the previous one is finished with (the `GamePrompted` records the same).
 * @property lastMessage the most recent line of server narration, or `null` before the first one.
 * @property lastError the most recent server-reported game error, or `null`; sticky, since upstream's
 *   `GAME_ERROR` carries no state and nothing clears it.
 * @property result the terminal result once the game has ended (upstream `GAME_OVER`), else `null`.
 * @property hasSnapshot whether **any** snapshot has been folded yet. Produced by the arrival of the
 *   first state-carrying push; it exists so a consumer can tell "no state yet" from "state that happens
 *   to be empty" without inventing a loading flag the server does not send.
 * @property isWatching whether the server has confirmed this session as a spectator of the game
 *   (upstream `WATCHGAME`).
 */
data class GameState(
    val gameId: String,
    val turn: Int = 0,
    val phase: TurnPhase = TurnPhase.Unknown,
    val step: PhaseStep = PhaseStep.Unknown,
    val activePlayerId: String? = null,
    val activePlayerName: String? = null,
    val priorityPlayerName: String? = null,
    val viewerPlayerId: String? = null,
    val viewerHasPriority: Boolean = false,
    val players: List<GamePlayer> = emptyList(),
    val hand: List<GameCard> = emptyList(),
    val stack: List<GameCard> = emptyList(),
    val exile: List<GameZone> = emptyList(),
    val revealed: List<GameZone> = emptyList(),
    val combat: List<CombatGroup> = emptyList(),
    val playable: List<PlayableObject> = emptyList(),
    val specialActionsAvailable: Boolean = false,
    val priorityTimeSeconds: Int = 0,
    val bufferTimeSeconds: Int = 0,
    val prompt: GamePrompt? = null,
    val lastMessage: GameMessage? = null,
    val lastError: String? = null,
    val result: GameResult? = null,
    val hasSnapshot: Boolean = false,
    val isWatching: Boolean = false,
) {
    /**
     * Whether it is the viewer's own turn — [viewerPlayerId] and [activePlayerId] are the same seat. Both
     * halves are server-produced ids, so this is a comparison, not a guess. `false` for a spectator (which
     * has no seat) and before the first snapshot.
     */
    val isViewerTurn: Boolean get() = viewerPlayerId != null && viewerPlayerId == activePlayerId

    /** Whether the game has ended (a [result] has landed). */
    val isOver: Boolean get() = result != null

    /**
     * Whether this session is watching rather than playing: a snapshot has arrived and it was **not**
     * built for a seat (upstream `GameView.getMyPlayer()` is null for a watcher). Guarded by
     * [hasSnapshot] so the seedless starting state does not masquerade as a spectator.
     */
    val isSpectator: Boolean get() = hasSnapshot && viewerPlayerId == null

    /** The viewer's own seat, or `null` for a spectator / before the first snapshot. */
    val viewer: GamePlayer? get() = players.firstOrNull { it.isViewer }

    /**
     * Whether the **server** says [objectId] can be played right now. This is a lookup in [playable] and
     * nothing else — there is no client-side legality rule anywhere in this package.
     */
    fun isPlayable(objectId: String): Boolean = playable.any { it.objectId == objectId }

    /**
     * The specific abilities of [objectId] the server says are playable (a land with two mana abilities
     * has two), or an empty list when the object is not playable at all.
     */
    fun playableAbilitiesOf(objectId: String): List<String> = playable.firstOrNull { it.objectId == objectId }?.abilityIds.orEmpty()
}

/**
 * One seat in a [GameState] — the app-schema projection of `mage.view.PlayerView`.
 *
 * @property playerId the seat's server id — the stable key, and the only id-safe way to talk about a seat.
 * @property name the display name.
 * @property life the current life total.
 * @property libraryCount / @property handCount / @property graveyardCount / @property exileCount the
 *   zone sizes the server reports (a *count*, not the cards — only the viewer's own hand is revealed).
 * @property wins matches/games won so far; @property winsNeeded how many take the match.
 * @property isViewer whether this is the seat the snapshot was built for (upstream `getControlled()`).
 * @property isActive whether it is this player's turn (upstream `isActive()`).
 * @property hasPriority whether this player holds priority (upstream `hasPriority()`) — the per-player
 *   flag that stands in for the priority *id* upstream never exposes.
 * @property isHuman whether the seat is a human rather than an AI.
 * @property hasLeft whether the player has conceded/left.
 * @property manaPool the player's floating mana.
 * @property battlefield the permanents this player controls.
 * @property counters the counters on the **player** — poison, energy, experience — in the same
 *   `{name, count}` shape [GameCounter] uses for cards. Ten poison counters is a loss, so this is
 *   win-condition state. Look counters up by [GameCounter.name]: the order is the server's hash-map
 *   order and means nothing.
 * @property isMonarch whether this player is the monarch (draws each end step; lost to whoever deals
 *   them combat damage).
 * @property hasInitiative whether this player has the initiative.
 * @property designationNames the player's own designations. In practice this is City's Blessing or
 *   nothing: the Monarch and Initiative designations are game-level upstream, never per-player, which
 *   is why [isMonarch] and [hasInitiative] are separate flags.
 * @property commandList this player's command zone — emblems, commanders, the active dungeon and the
 *   current plane. A **plane appears on every seat's list**, because planes are universal; the other
 *   three are the player's own.
 * @property graveyard the cards in this player's graveyard, in the server's order. A graveyard has a
 *   meaningful order and the client is not the place to decide what it is, so nothing sorts this.
 * @property exile the cards this player **owns** that are in exile — owner, not controller, so a card
 *   of yours an opponent exiled is here. [GameState.exile] is the same cards grouped by the pile they
 *   sit in, which is what carries a zone's name.
 */
data class GamePlayer(
    val playerId: String,
    val name: String,
    val life: Int = 0,
    val libraryCount: Int = 0,
    val handCount: Int = 0,
    val graveyardCount: Int = 0,
    val exileCount: Int = 0,
    val wins: Int = 0,
    val winsNeeded: Int = 0,
    val isViewer: Boolean = false,
    val isActive: Boolean = false,
    val hasPriority: Boolean = false,
    val isHuman: Boolean = true,
    val hasLeft: Boolean = false,
    val manaPool: ManaPool = ManaPool(),
    val battlefield: List<GamePermanent> = emptyList(),
    val counters: List<GameCounter> = emptyList(),
    val isMonarch: Boolean = false,
    val hasInitiative: Boolean = false,
    val designationNames: List<String> = emptyList(),
    val commandList: List<GameCommandObject> = emptyList(),
    val graveyard: List<GameCard> = emptyList(),
    val exile: List<GameCard> = emptyList(),
)

/**
 * One object in a player's command zone — an emblem, a commander, a dungeon or a plane.
 *
 * These belong to a player rather than to a pile of cards, which is why they live on [GamePlayer] and
 * not in a zone. One flat type with a [kind] rather than four: the four upstream implementations
 * differ in what they are, not in what a client reads off them.
 *
 * @property collectorNumber `null` for a dungeon and a plane, which have no card number at all. Only
 *   an emblem printed on a card, and a commander, carry one.
 * @property rules the object's rules text as the server rendered it.
 */
data class GameCommandObject(
    val id: String,
    val name: String,
    val kind: CommandObjectKind = CommandObjectKind.Unknown,
    val setCode: String? = null,
    val collectorNumber: String? = null,
    val rules: List<String> = emptyList(),
)

/**
 * What kind of command object a [GameCommandObject] is.
 *
 * [Unknown] covers a kind this build does not know: upstream adds command-object kinds, and one it
 * has never heard of costs a single value rather than the whole snapshot.
 */
enum class CommandObjectKind {
    Emblem,
    Commander,
    Dungeon,

    /** Universal: the same plane appears on every seat's command list, owned by none of them. */
    Plane,
    Unknown,
}

/**
 * A card as the server rendered it — the app-schema projection of `mage.view.CardView`. Deliberately
 * thin: enough to identify a printing ([setCode] + [collectorNumber], the pair the card catalog
 * resolves art by) and to render a text-only representation.
 *
 * **What the card currently *is* is game state, not printing.** [cardTypes], [isCreature]
 * and [counters] are recomputed by the server for every snapshot, so an Earthbent land really does
 * arrive as a creature and stops being one when the effect ends. A UI must therefore ask [isCreature]
 * rather than reading [typeLine] — parsing that display string would be the client interpreting rules.
 *
 * @property id the object id — what a reply names when this card is chosen.
 * @property power / @property toughness the **current** values, after effects, as *strings*: `*` is a
 *   real power (Tarmogoyf, Mortivore), so nothing anywhere parses these into numbers. Upstream reports
 *   `"0"`/`"0"` for a noncreature permanent, which is why showing them is gated on [isCreature] rather
 *   than on their presence.
 * @property isCreature the server's own `CardView.isCreature()`. The single question a board asks about
 *   creature-ness — never the printed type, never [typeLine].
 * @property counters every counter on the object, by name and count. Not battlefield-only: a card in
 *   another zone can carry them, because upstream puts them on `CardView`.
 * @property alternateName upstream `CardView.getAlternateName()`: a catalog fact, set
 *   unconditionally on any transformable/double-faced/flip/meld object to its *other* face's name,
 *   regardless of which face is currently showing. Not a signal for current face — see [transformed].
 * @property transformed upstream `CardView.isTransformed()` — the live "is this permanent
 *   currently showing its back face" fact. `false` for anything that is not a permanent, and for an
 *   untransformed permanent. This is the signal for which face's art to request, never [alternateName].
 * @property targets what this object is pointing at, from upstream `CardView.getTargets()`
 *   — the ids of every chosen target of a spell or ability on the stack, de-duplicated and in
 *   upstream's own stable order. **Ids into the same [GameState]**: a battlefield permanent, a player,
 *   or another entry on the [GameState.stack], since upstream resolves them all through
 *   `game.getObject(uuid)` into one flat list. Resolving an id to the object it names is the
 *   renderer's job — everything it could name is already in the same snapshot. Empty for anything that
 *   is not a targeting stack object, which is most cards.
 */
data class GameCard(
    val id: String,
    val name: String,
    val setCode: String? = null,
    val collectorNumber: String? = null,
    val manaCost: String? = null,
    val typeLine: String? = null,
    val power: String? = null,
    val toughness: String? = null,
    val rules: List<String> = emptyList(),
    val isFaceDown: Boolean = false,
    val cardTypes: List<CardType> = emptyList(),
    val isCreature: Boolean = false,
    val counters: List<GameCounter> = emptyList(),
    val alternateName: String? = null,
    val transformed: Boolean = false,
    val targets: List<String> = emptyList(),
)

/**
 * One counter on a card or permanent — the app-schema projection of `mage.view.CounterView`, which is
 * exactly `{name, count}`.
 *
 * The kind is the server's own [name] string and stays one: `+1/+1` and `-1/-1` matter most, but
 * loyalty, charge, oil, stun and hundreds more exist, and every set adds more. Nothing in the app
 * enumerates counter kinds.
 */
data class GameCounter(
    val name: String,
    val count: Int = 0,
)

/**
 * A card type as the server currently reports it — the app-schema projection of `mage.constants.CardType`
 * (via `CardTypeCode`), **after** continuous effects.
 *
 * Carried as the structured list, not just as [GameCard.isCreature], so later work (subtypes,
 * supertypes, "which effect animated this") extends this rather than re-deriving from a display string.
 */
enum class CardType {
    Artifact,
    Battle,
    Conspiracy,
    Creature,
    Dungeon,
    Enchantment,
    Instant,
    Kindred,
    Land,
    Phenomenon,
    Plane,
    Planeswalker,
    Scheme,
    Sorcery,
    Vanguard,

    /** A type this build does not know — upstream added one, or the bridge sent nothing usable. */
    Unknown,
}

/**
 * A permanent on the battlefield — the app-schema projection of `mage.view.PermanentView`: a [card] plus
 * the battlefield-only state a board must show.
 *
 * **Attachments travel in both directions.** [attachedTo] is what this permanent is
 * attached to; [attachments] is what is attached *to it*. The reverse direction is carried rather than
 * derived because deriving it means every host scanning every battlefield, on every frame, for anything
 * pointing at it — and upstream already computed it once for the snapshot.
 *
 * @property attachedTo the permanent **or player** this is attached to (an aura/equipment/curse), or
 *   `null`. [isAttachedToPermanent] is what says which of the two it is.
 * @property attachments the ids of everything attached to this permanent, in upstream's order.
 * @property isAttachedToPermanent true when the host resolved to a permanent rather than a player.
 * @property attachedControllerDiffers your Aura on their creature. Narrower than it sounds: upstream
 *   computes it only when the host is a permanent, so it is always `false` when
 *   [isAttachedToPermanent] is `false` — a Curse on an opposing player included.
 * @property isControlledByViewer whether the viewer controls it (which can differ from whose battlefield
 *   it sits on).
 */
data class GamePermanent(
    val card: GameCard,
    val isTapped: Boolean = false,
    val isFlipped: Boolean = false,
    val isPhasedIn: Boolean = true,
    val hasSummoningSickness: Boolean = false,
    val damage: Int = 0,
    val attachedTo: String? = null,
    val attachments: List<String> = emptyList(),
    val isAttachedToPermanent: Boolean = false,
    val attachedControllerDiffers: Boolean = false,
    val isControlledByViewer: Boolean = false,
)

/** A named zone of cards (an exile zone, a revealed set) — projection of `ExileView`/`RevealedView`. */
data class GameZone(
    val name: String,
    val cards: List<GameCard> = emptyList(),
    val zoneId: String? = null,
)

/**
 * One combat group — projection of `mage.view.CombatGroupView`: who is being attacked, by what, and what
 * is blocking. The ids reference permanents already present in [GameState.players]' battlefields, so a
 * board resolves them from state it already holds rather than from a second lookup.
 */
data class CombatGroup(
    val defenderId: String? = null,
    val defenderName: String? = null,
    val isBlocked: Boolean = false,
    val attackerIds: List<String> = emptyList(),
    val blockerIds: List<String> = emptyList(),
)

/** A player's floating mana — projection of `mage.view.ManaPoolView`. */
data class ManaPool(
    val white: Int = 0,
    val blue: Int = 0,
    val black: Int = 0,
    val red: Int = 0,
    val green: Int = 0,
    val colorless: Int = 0,
) {
    /** The total floating mana, for the common "is there anything in the pool" check. */
    val total: Int get() = white + blue + black + red + green + colorless
}

/**
 * One entry of the server's `canPlayObjects` — **the** answer to "may this be played right now".
 *
 * @property objectId the card/permanent that may be played or activated.
 * @property abilityIds the specific abilities on it that are playable (a land with two mana abilities has
 *   two). Answering a [GamePrompt.ChooseAbility] names one of these.
 */
data class PlayableObject(
    val objectId: String,
    val abilityIds: List<String> = emptyList(),
)

/**
 * A line of server narration folded from `GAME_UPDATE_AND_INFORM` / `GAME_INFORM_PERSONAL`.
 *
 * @property text the server's own wording.
 * @property isPersonal true when the message was addressed to this seat alone (the desktop client raises
 *   those as a modal); false for the game's running commentary.
 */
data class GameMessage(
    val text: String,
    val isPersonal: Boolean = false,
)

/**
 * The terminal result of a game, from upstream's `GAME_OVER`.
 *
 * Upstream's payload is a **single line of prose** ("Xyz has won the game") and nothing else — there is
 * no structured winner id, no reason code. So this carries the message and, where the final snapshot
 * allows it, nothing more: a winner id has **no producer**, and inventing one from the message text would
 * be exactly the kind of derived flag standard 2 exists to prevent.
 *
 * @property message the server's own end-of-game line, or `null` when it sent none.
 */
data class GameResult(
    val message: String? = null,
)

/** A turn phase — the app-schema mirror of `mage.constants.TurnPhase`. */
enum class TurnPhase {
    /** The beginning phase (untap/upkeep/draw). */
    Beginning,

    /** The precombat main phase. */
    PrecombatMain,

    /** The combat phase. */
    Combat,

    /** The postcombat main phase. */
    PostcombatMain,

    /** The ending phase. */
    End,

    /** No phase reported yet, or one this build does not recognise. */
    Unknown,
}

/** A step within a phase — the app-schema mirror of `mage.constants.PhaseStep`. */
enum class PhaseStep {
    /** Untap step. */
    Untap,

    /** Upkeep step. */
    Upkeep,

    /** Draw step. */
    Draw,

    /** Precombat main step. */
    PrecombatMain,

    /** Beginning of combat step. */
    BeginCombat,

    /** Declare attackers step. */
    DeclareAttackers,

    /** Declare blockers step. */
    DeclareBlockers,

    /** First-strike combat damage step. */
    FirstCombatDamage,

    /** Combat damage step. */
    CombatDamage,

    /** End of combat step. */
    EndCombat,

    /** Postcombat main step. */
    PostcombatMain,

    /** End step. */
    EndTurn,

    /** Cleanup step. */
    Cleanup,

    /** No step reported yet, or one this build does not recognise. */
    Unknown,
}

/** A kind of mana — the app-schema mirror of `mage.constants.ManaType`, for [GameClient.unlockMana]. */
enum class ManaType {
    /** White. */
    White,

    /** Blue. */
    Blue,

    /** Black. */
    Black,

    /** Red. */
    Red,

    /** Green. */
    Green,

    /** Colourless (`{C}`) — a real, payable type. */
    Colorless,

    /** Generic (`{1}`) — payable by any type; upstream never returns it from a pool. */
    Generic,
}

/**
 * How far a [GameClient.passPriorityUntil] pass runs. The plain "pass this priority" is *not* here — it
 * is the answer to the outstanding [GamePrompt.Select] and is [GameClient.passPriority]; these are the
 * standing instructions upstream models as player actions instead.
 */
enum class PassPriorityScope {
    /** Until this player's next turn. */
    UntilMyNextTurn,

    /** Until the end step of the current turn. */
    UntilTurnEndStep,

    /** Until the next main phase. */
    UntilNextMainPhase,

    /** Until the next turn. */
    UntilNextTurn,

    /** Until the next turn, without stopping for the stack. */
    UntilNextTurnSkipStack,

    /** Until the stack has resolved. */
    UntilStackResolved,

    /** Until the end step before this player's next turn. */
    UntilEndStepBeforeMyNextTurn,

    /** Cancel every outstanding "pass until …" instruction. */
    CancelAll,
}

/**
 * The result of [GameClient.refreshGame]: the bridge's held snapshot of a game, plus when
 * it was captured.
 *
 * **This is a replay, not a reconstruction.** [state] is the server's own most recent `GameView` for
 * *this session*, mapped exactly as a pushed one is — nothing is merged with an older snapshot, and
 * nothing about a card the server has stopped showing is remembered (accumulation is deliberately
 * not this one). It is a standalone [GameState]: only the fields a snapshot owns are set, so
 * [GameState.prompt], [GameState.lastMessage], [GameState.lastError] and [GameState.result] are null —
 * a read tells you what the board looks like, never that the server is waiting on you.
 *
 * @property capturedAtEpochMs when the **bridge** captured the snapshot (wall clock). The board is
 *   current as of the last push the bridge saw, and this says when that was, so a caller can reason
 *   about staleness instead of guessing. Null only against a bridge that does not stamp one.
 */
data class GameSnapshot(
    val state: GameState,
    val capturedAtEpochMs: Long? = null,
)

/**
 * The typed failure [GameClient.refreshGame] surfaces when the bridge has **no state** to give
 * — the `GameStateUnavailable`.
 *
 * It exists so that "there is nothing to show" can never arrive as an empty board. An all-defaults
 * [GameState] is a legal snapshot (a spectator of a game that has not started has exactly that), so a
 * caller could not tell it from the truth and would render it — which is the whole failure mode the
 * is about. A failed [Result] cannot be mistaken for a board.
 *
 * @property reason which kind of absence it is; the two demand different responses.
 */
class GameStateUnavailableFailure(
    val gameId: String,
    val reason: GameStateUnavailableReason,
    val detail: String? = null,
) : Exception(detail ?: "no game state available for $gameId")

/** Why a [GameStateUnavailableFailure] carries no state — the app-schema mirror of the wire code. */
enum class GameStateUnavailableReason {
    /**
     * The bridge holds no snapshot for that game on this session: nothing has been pushed yet, the id
     * names a game this session is not in, or the game ended and its entry was dropped. Waiting fixes
     * the first; the caller keeps whatever it already holds meanwhile.
     */
    NoStateYet,

    /**
     * There is no session behind the socket, so there was not even a cache to look in. Re-authenticating
     * — not retrying — is what fixes it (the distinction, on the read side).
     */
    SessionGone,
}

/**
 * The typed failure a [GameClient] verb surfaces when the server declines a game action — the
 * `GameActionResult` with `ok = false`, or an unexpected reply. [reason] is the server's optional
 * human-readable detail: a decline is a **typed result**, never a silent drop.
 *
 * The game-side twin of the `TableActionFailure`, kept separate for the same reason the
 * `GameFailureCode` is separate from `TableFailureCode`: a caller handling a game should not have to
 * catch a table type to learn that its reply was refused.
 */
open class GameActionFailure(
    val reason: String?,
) : Exception(reason ?: "game action declined")

/**
 * The typed failure a [GameClient] verb surfaces when the action **never reached the server** because the
 * session is gone — the bridge answered with the `GameFailureCode.SESSION_GONE` (the
 * distinction, applied to the game verbs).
 *
 * A [GameActionFailure], so every caller's error path keeps working, but a *distinct* one because the two
 * demand opposite responses: a decline means the server considered the action and said no (retrying is
 * pointless but the session is fine); this means there was nothing to ask (re-authenticating is the only
 * fix, and that is what the UI must offer).
 */
class GameSessionGoneFailure(
    reason: String?,
) : GameActionFailure(reason ?: "your session has ended; sign in again")
