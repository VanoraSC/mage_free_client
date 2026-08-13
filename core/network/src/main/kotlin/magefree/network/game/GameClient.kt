package magefree.network.game

import kotlinx.coroutines.flow.Flow

/**
 * The app-side, **UI-free** game client (story 0052, EPIC-11): the *act* side of a running game, layered
 * over 0028's multiplexed request/response and 0023/0024's session/resume — the game-side counterpart of
 * story 0037's `TableClient`, which stops at the moment a match begins.
 *
 * Every verb turns a 0051 game request into a `suspend` call that maps the correlated `GameActionResult`
 * into a [Result]: a server decline surfaces as a typed [GameActionFailure] carrying the server's reason,
 * and a decline that means *there was no session to ask* surfaces as [GameSessionGoneFailure] (story
 * 0050's distinction). Never a silent drop, and never an unhandled throw — a transport failure is
 * captured as a failed [Result] too.
 *
 * **The reply surface is typed by prompt, not by wire message.** Upstream's client→server game protocol
 * is five untyped primitives (`sendPlayerUUID/Boolean/Integer/String/ManaType`), which is exactly why a
 * UI built directly on it would be guesswork. Here, each method answers a named [GamePrompt] subtype and
 * accepts only what that prompt can validly be answered with; the KDoc of each names the prompt it
 * answers, and the KDoc of each [GamePrompt] names the method. The one prompt with no answering method is
 * [GamePrompt.Unrecognised], on purpose: nothing knows what a valid reply to it is.
 *
 * **No rules logic.** The client never decides whether something may be played. [GameState.playable] is
 * the server's own `canPlayObjects` list, and it is the only input to that question.
 *
 * No `:protocol` or `mage.*` type appears on this ABI; the implementation is `internal` and is assembled
 * by [GameClients.overBridge], the `:protocol`-free factory (the seam that lets a test drive the real
 * client — the property story 0045's live suite depends on).
 *
 * A [magefree.network.fake.FakeGameClient] backs hermetic tests of downstream code.
 */
interface GameClient {
    // --- joining and leaving -------------------------------------------------------------------------

    /**
     * Take your seat in the game [gameId] — the call that follows a table's `MatchStarting` and makes the
     * server start pushing this session's game callbacks.
     *
     * Success means the server accepted the join; the state itself arrives **asynchronously** on
     * [observeGame], so a caller collects that flow *before* (or at least alongside) calling this.
     */
    suspend fun joinGame(gameId: String): Result<Unit>

    /**
     * Watch (spectate) the game [gameId]. A spectator receives the same snapshots as a player, but
     * [GameState.viewerPlayerId] is null (see [GameState.isSpectator]), its hand is empty and it is never
     * prompted.
     */
    suspend fun watchGame(gameId: String): Result<Unit>

    /**
     * Quit the match the game [gameId] belongs to. This is a **concession of the whole match**, not a
     * pause and not a way to leave temporarily — an app that merely wants to stop looking at the game
     * should stop collecting [observeGame] instead.
     */
    suspend fun quitMatch(gameId: String): Result<Unit>

    /** Stop spectating the game [gameId] (the [watchGame] counterpart). */
    suspend fun stopWatching(gameId: String): Result<Unit>

    // --- answering the outstanding prompt ------------------------------------------------------------

    /**
     * Answer a [GamePrompt.Select] by playing or activating [objectId].
     *
     * [objectId] must be one the **server** offered — an id from [GameState.playable]. The client does
     * not check that (legality is not a client concern), but a UI that offers anything else is offering
     * an action the server will refuse.
     */
    suspend fun playObject(
        gameId: String,
        objectId: String,
    ): Result<Unit>

    /**
     * Answer a [GamePrompt.Select] by **passing priority** — upstream's plain "I'm done here", which is
     * the `false` arm of the select. For the standing "pass until …" instructions, which are not answers
     * to a prompt at all, use [passPriorityUntil].
     */
    suspend fun passPriority(gameId: String): Result<Unit>

    /**
     * Use the server's extra "special" button on a [GamePrompt.Select] or [GamePrompt.PlayMana]. Only
     * valid when the prompt's [PromptOptions.specialButtonText] is non-null — that is the server telling
     * you the button exists.
     */
    suspend fun useSpecialAction(gameId: String): Result<Unit>

    /** Answer a [GamePrompt.Target] by choosing [targetId]. */
    suspend fun chooseTarget(
        gameId: String,
        targetId: String,
    ): Result<Unit>

    /** Answer a [GamePrompt.Ask] with yes ([answer] true) or no. */
    suspend fun answerAsk(
        gameId: String,
        answer: Boolean,
    ): Result<Unit>

    /** Answer a [GamePrompt.ChooseAbility] with one of its [AbilityChoice.abilityId]s. */
    suspend fun chooseAbility(
        gameId: String,
        abilityId: String,
    ): Result<Unit>

    /**
     * Answer a [GamePrompt.ChoosePile]: [first] `true` picks [GamePrompt.ChoosePile.pile1], `false` picks
     * [GamePrompt.ChoosePile.pile2].
     */
    suspend fun choosePile(
        gameId: String,
        first: Boolean,
    ): Result<Unit>

    /**
     * Answer a [GamePrompt.ChooseChoice] with a [ChoiceOption.key].
     *
     * @param special picks the choice's *special* option instead of the plain one; only meaningful when
     *   the prompt's [GamePrompt.ChooseChoice.specialText] is non-null.
     */
    suspend fun chooseChoice(
        gameId: String,
        key: String,
        special: Boolean = false,
    ): Result<Unit>

    /** Answer a [GamePrompt.PlayMana] by tapping [sourceId] for mana. */
    suspend fun playManaSource(
        gameId: String,
        sourceId: String,
    ): Result<Unit>

    /**
     * Answer a [GamePrompt.PlayMana] by unlocking [manaType] from a pool that already holds it.
     *
     * @param playerId the pool's owner — usually the viewer ([GameState.viewerPlayerId]), but a player
     *   whose turn is under your control is possible, which is why upstream takes the id explicitly.
     */
    suspend fun unlockMana(
        gameId: String,
        playerId: String,
        manaType: ManaType,
    ): Result<Unit>

    /** Answer a [GamePrompt.PlayXMana] by announcing [value] for X. */
    suspend fun announceX(
        gameId: String,
        value: Int,
    ): Result<Unit>

    /** Answer a [GamePrompt.GetAmount] with [amount] (within the prompt's own min/max). */
    suspend fun chooseAmount(
        gameId: String,
        amount: Int,
    ): Result<Unit>

    /**
     * Answer a [GamePrompt.GetMultiAmount] with one amount per [GamePrompt.GetMultiAmount.entries] item,
     * **in the same order**. Upstream parses exactly this positional form, so a short or reordered list
     * is a wrong answer, not a partial one.
     */
    suspend fun distributeAmounts(
        gameId: String,
        amounts: List<Int>,
    ): Result<Unit>

    /**
     * Decline the outstanding prompt — upstream's shared "done / cancel" arm, which answers
     * [GamePrompt.Target], [GamePrompt.ChooseAbility], [GamePrompt.PlayMana] and [GamePrompt.PlayXMana].
     *
     * It is the same wire message as [passPriority]; the two are separate methods because they mean
     * different things to a reader and to a UI, and only one of them is ever the right label on a button.
     * Declining a [GamePrompt.Target] whose [GamePrompt.Target.isRequired] is true (and for which too few
     * targets have been chosen) is refused by the server, which then re-asks.
     */
    suspend fun cancelPrompt(gameId: String): Result<Unit>

    // --- out-of-band player actions ------------------------------------------------------------------

    /**
     * Set a standing pass instruction of [scope] — the "pass until my next turn / until the stack
     * resolves / …" verbs, which upstream models as player actions rather than as answers to a prompt.
     * Unlike [passPriority] this does not answer the outstanding [GamePrompt.Select]; the server applies
     * it and keeps passing on the player's behalf until the scope ends.
     */
    suspend fun passPriorityUntil(
        gameId: String,
        scope: PassPriorityScope,
    ): Result<Unit>

    /**
     * Concede the game [gameId] (a player action, so it works whether or not a prompt is outstanding).
     * The game ends and a terminal [GameState.result] follows on [observeGame].
     */
    suspend fun concede(gameId: String): Result<Unit>

    // --- reading -------------------------------------------------------------------------------------

    /**
     * Read the current board of [gameId] **on demand** (story 0054) — the game-side counterpart of
     * `TableClient.refreshTable`, and the thing 0052 could not offer.
     *
     * **Nothing upstream answers this.** XMage has no verb that reads a `GameView`, and re-joining a
     * running game does not resync, so a client that dropped its socket used to be blind until the next
     * push — which may be minutes away while an opponent thinks, or never. The **bridge** answers it, from
     * the last snapshot it relayed to *this* session: it sees every snapshot go past, and a snapshot is
     * the whole truth rather than a delta, so holding the most recent one is sufficient.
     *
     * **A miss is a failure, never a blank board.** When the bridge holds nothing for that game the
     * result is a failed [Result] carrying [GameStateUnavailableFailure] — because an empty [GameState]
     * is a perfectly legal board and a caller could not tell it from the truth. A caller that already
     * holds a state keeps it; a caller that does not keeps showing that it has nothing yet.
     *
     * [observeGame] issues this itself on open and after a resume, so a board built on that flow does not
     * need to call it; this is here for a caller that wants a one-shot read.
     */
    suspend fun refreshGame(gameId: String): Result<GameSnapshot>

    // --- observing -----------------------------------------------------------------------------------

    /**
     * Observe the evolving [GameState] of [gameId]: emit [seed] first, then a new state each time one of
     * story 0051's game pushes folds in ([GameEventFold]). Cold — each collection starts a fresh
     * subscription and the caller owns its scope.
     *
     * **Snapshot replace.** Every state-carrying push carries the whole game view, so each emission is a
     * complete picture rather than a patch; a consumer renders the latest and never reconciles.
     *
     * **Reads (story 0054).** [refreshGame] is issued **on open** and **again on each return to
     * [magefree.model.ConnectionState.Connected]** (a 0023/0024 resume), and its snapshot is folded onto
     * the held state exactly as a push would be. That is what makes a reconnecting board show something
     * before the opponent acts. Until 0054 there was nothing to issue — game state was push-only — and
     * `observeGameNeverIssuesARequestOfItsOwn` existed to stop anyone polling against a request the
     * bridge could not answer; it now pins these two reads and the continued **absence of a poll**.
     *
     * **Resume (0023/0024).** The held state is also re-emitted on that return, so a collector that had
     * stopped rendering is not stranded — the same non-destructive re-sync `TableClient.observeTable`
     * performs. The bridge's parked session keeps pumping into a durable, re-bindable buffer while the
     * app socket is down (story 0023), so pushes produced during the gap still arrive when the socket
     * re-binds; the read is what covers the case where **no** push comes.
     *
     * @param seed the starting state; defaults to an empty [GameState] for [gameId]. A caller that
     *   already holds one (e.g. re-opening a board) may pass it so the first emission is not blank.
     */
    fun observeGame(
        gameId: String,
        seed: GameState = GameState(gameId),
    ): Flow<GameState>
}
