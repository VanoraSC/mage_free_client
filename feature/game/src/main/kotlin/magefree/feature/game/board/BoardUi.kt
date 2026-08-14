package magefree.feature.game.board

import magefree.cards.art.CardArtFace
import magefree.cards.art.CardArtRequest
import magefree.cards.art.CardArtSize
import magefree.designsystem.card.CardDisplay
import magefree.network.game.CombatGroup
import magefree.network.game.GameCard
import magefree.network.game.GamePermanent
import magefree.network.game.GamePlayer
import magefree.network.game.GameState
import magefree.network.game.PhaseStep
import magefree.network.game.TurnPhase

/*
 * The board's **presentation model** (story 0055): one pure function, [BoardUi.from], turning a 0052
 * [GameState] snapshot into everything the portrait board draws. No Compose type, no Android type, no
 * coroutine — so every region and every *empty* region is provable in a plain JVM unit test.
 *
 * ## Reachability record (verification standard 2) — rendered field -> the `GameState` field that
 * produces it. Nothing on this screen is derived from anything else.
 *
 * | Rendered | Produced by |
 * |---|---|
 * | which seat is *yours* | `GamePlayer.isViewer` (never the list index — see [BoardUi.from]) |
 * | opponent / viewer battlefield | `GamePlayer.battlefield` on the seat located above |
 * | life, library/hand/graveyard/exile counts, wins | `GamePlayer.life` / `libraryCount` / `handCount` / `graveyardCount` / `exileCount` / `wins` / `winsNeeded` |
 * | floating mana pip | `GamePlayer.manaPool.total` |
 * | "active" seat marker | `GamePlayer.isActive` |
 * | "conceded/left" seat marker | `GamePlayer.hasLeft` |
 * | your hand | `GameState.hand` |
 * | the stack | `GameState.stack` (top **last** upstream; reversed here so the top reads first) |
 * | turn number | `GameState.turn` |
 * | phase / step | `GameState.phase` / `GameState.step` |
 * | whose turn | `GameState.activePlayerName`, `GameState.isViewerTurn` (`activePlayerId` vs `viewerPlayerId`) |
 * | the priority banner | `GameState.viewerHasPriority`, then `GameState.prompt != null` (see [PriorityUi.Asked]); the waiting-on detail line is `GameState.priorityPlayerName` |
 * | "nothing you can play" note | `GameState.playable` **while** `viewerHasPriority` (see [PriorityUi]) |
 * | the per-card "server offered this" mark | `GameState.playable` **while** `viewerHasPriority` (see [PermanentUi.isOfferedByServer]) |
 * | attacking / blocking marks | `GameState.combat[].attackerIds` / `blockerIds` |
 * | tapped / summoning sick / damage | `GamePermanent.isTapped` / `hasSummoningSickness` / `damage` |
 * | card name, cost, type, P/T, rules | `GameCard.name` / `manaCost` / `typeLine` / `power` / `toughness` / `rules` |
 * | card **art** | `GameCard.setCode` + `GameCard.collectorNumber` -> 0031's [CardArtRequest] |
 * | the prompt notice (read-only) | `GameState.prompt.message` |
 * | the narration line | `GameState.lastMessage.text` |
 * | the clock | `GameState.priorityTimeSeconds` (suppressed at 0 — see [ClockUi]) |
 * | the error line | `GameState.lastError` |
 * | the result line | `GameState.result.message` |
 * | "no snapshot yet" | `GameState.hasSnapshot` |
 * | spectator mode | `GameState.isSpectator` (`hasSnapshot` && `viewerPlayerId == null`) |
 *
 * ## The constraints this model exists to honour (each verified live before it was written)
 *
 * - **Seats are located by [GamePlayer.isViewer], never by index.** Player order is not stable and is
 *   not viewer-first (observed live: the AI first in two runs, the viewer first in two others).
 * - **Legality is never computed.** `playable` is the server's own `canPlayObjects` and is populated
 *   *only while the viewer holds priority*. So an unmarked card may mean "not your moment", not "not
 *   playable" — which is why [PermanentUi.isOfferedByServer] / [HandCardUi.isOfferedByServer] are
 *   forced to `false` for **every** card whenever priority is not held: the board says nothing about
 *   playability rather than saying something false. (The mark is also inert — see [BoardUi].)
 * - **Exile is judged by cards, not by list size**: `GameState.exile` carries one zone entry even when
 *   nothing is exiled, so [BoardUi.exiledCardCount] sums the cards. (`GamePlayer.exileCount` is a
 *   different thing and is safe: the bridge maps it from `PlayerView.exile.size`, a set of *cards*.)
 * - **`manaCost` is null for lands** — carried through as null, never rendered as an empty cost.
 * - **Clocks read 0 on an untimed table** — [ClockUi.of] returns null there, so nothing renders
 *   "0 seconds left".
 * - **Server narration is HTML** (`<font color='#20B2AA'>Computer</font>`) — [stripServerMarkup] takes
 *   the tags off before anything is shown; markup never reaches the screen.
 * - **The opponent's stack can hold a phantom** after they cancel a cast (requirements §17.4: the
 *   rewind is not pushed to the opponent). The stack is therefore labelled as *last pushed* and is
 *   never presented as authoritative between pushes.
 */

/**
 * Everything the read-only portrait board draws, for one [GameState] snapshot.
 *
 * **Read-only.** Nothing in this model carries a callback, an id to act on, or an "answer" of any kind.
 * [promptNotice] is text for comprehension only — the answering surface is story 0056. The per-card
 * [PermanentUi.isOfferedByServer] mark is decoration; it triggers nothing.
 *
 * @property hasSnapshot whether any snapshot has folded yet (`GameState.hasSnapshot`) — the board's own
 *   "nothing has arrived" state, which is distinct from a snapshot that happens to be empty.
 * @property viewerSeat the viewer's seat, found via [GamePlayer.isViewer]; null for a spectator and
 *   before the first snapshot.
 * @property opponentSeats every other seat, in server order.
 * @property exiledCardCount how many cards are actually in exile, counted from the cards.
 */
data class BoardUi(
    val gameId: String,
    val hasSnapshot: Boolean = false,
    val isSpectator: Boolean = false,
    val viewerSeat: SeatUi? = null,
    val opponentSeats: List<SeatUi> = emptyList(),
    val hand: HandUi = HandUi(),
    val stack: StackUi = StackUi(),
    val turn: TurnUi = TurnUi(),
    val priority: PriorityUi = PriorityUi.NotStarted,
    val promptNotice: String? = null,
    val narration: String? = null,
    val clock: ClockUi? = null,
    val errorNotice: String? = null,
    val resultNotice: String? = null,
    val exiledCardCount: Int = 0,
) {
    /** Whether anything is in exile — judged by cards, never by `GameState.exile.size`. */
    val hasExiledCards: Boolean get() = exiledCardCount > 0

    /** The game has ended: `GameState.result` landed and carried the server's own prose line. */
    val isOver: Boolean get() = resultNotice != null

    companion object {
        /**
         * Project [state] onto the board.
         *
         * The seat split is the load-bearing line: `players.partition { it.isViewer }` — the server's
         * own per-seat flag (upstream `PlayerView.getControlled()`), which 0052 carries. Index-based
         * seating ("players[0] is me") is wrong against the real server and is what this exists to
         * prevent.
         */
        fun from(state: GameState): BoardUi {
            // `playable` is meaningful ONLY while we hold priority (0052's KDoc, verified live). Outside
            // that window it is empty for reasons that have nothing to do with a card's playability, so
            // the board must not draw any conclusion from it at all.
            val playableIds: Set<String> =
                if (state.viewerHasPriority) state.playable.map { it.objectId }.toSet() else emptySet()

            val (viewerSeats, otherSeats) = state.players.partition { it.isViewer }
            val attackerIds = state.combat.flatMap(CombatGroup::attackerIds).toSet()
            val blockerIds = state.combat.flatMap(CombatGroup::blockerIds).toSet()

            return BoardUi(
                gameId = state.gameId,
                hasSnapshot = state.hasSnapshot,
                isSpectator = state.isSpectator,
                viewerSeat = viewerSeats.firstOrNull()?.toSeatUi(playableIds, attackerIds, blockerIds),
                opponentSeats = otherSeats.map { it.toSeatUi(playableIds, attackerIds, blockerIds) },
                hand =
                    HandUi(
                        cards =
                            state.hand.map { card ->
                                HandCardUi(objectId = card.id, card = card.toCardUi(), isOfferedByServer = card.id in playableIds)
                            },
                    ),
                stack =
                    StackUi(
                        // Upstream sends the stack with the **top last**; the board reads top-first.
                        entries = state.stack.asReversed().map { StackEntryUi(objectId = it.id, card = it.toCardUi()) },
                    ),
                turn =
                    TurnUi(
                        number = state.turn,
                        phase = state.phase,
                        step = state.step,
                        activePlayerName = state.activePlayerName,
                        isViewerTurn = state.isViewerTurn,
                    ),
                priority =
                    PriorityUi.of(
                        hasSnapshot = state.hasSnapshot,
                        viewerHasPriority = state.viewerHasPriority,
                        isBeingAsked = state.prompt != null,
                        priorityPlayerName = state.priorityPlayerName,
                        anythingOffered = playableIds.isNotEmpty(),
                    ),
                promptNotice = state.prompt?.message?.cleanedOrNull(),
                narration = state.lastMessage?.text?.cleanedOrNull(),
                clock = ClockUi.of(state.priorityTimeSeconds),
                errorNotice = state.lastError?.cleanedOrNull(),
                resultNotice = state.result?.let { it.message?.cleanedOrNull() ?: GAME_OVER_FALLBACK },
                // One zone entry exists even when nothing is exiled, so count the cards.
                exiledCardCount = state.exile.sumOf { zone -> zone.cards.size },
            )
        }

        /** What the result line says when `GAME_OVER` carried no prose at all (upstream may send none). */
        const val GAME_OVER_FALLBACK: String = "The game has ended."
    }
}

/**
 * One seat's vitals + battlefield — the compact per-player bar of requirements §4.3 plus the band it
 * labels.
 *
 * @property isViewer whether this is the viewer's own seat (`GamePlayer.isViewer`) — the only seating
 *   signal used anywhere on this board.
 * @property hasPriority the per-seat `PlayerView.hasPriority()`; upstream exposes no priority *id*, so
 *   this per-seat flag is the id-safe answer.
 */
data class SeatUi(
    val playerId: String,
    val name: String,
    val life: Int,
    val libraryCount: Int,
    val handCount: Int,
    val graveyardCount: Int,
    val exileCount: Int,
    val floatingMana: Int,
    val wins: Int,
    val winsNeeded: Int,
    val isViewer: Boolean,
    val isActive: Boolean,
    val hasPriority: Boolean,
    val isHuman: Boolean,
    val hasLeft: Boolean,
    val battlefield: List<PermanentUi>,
) {
    /** Whether this seat's battlefield band shows its empty state. */
    val hasNoPermanents: Boolean get() = battlefield.isEmpty()

    /** The match score line, shown only for a real best-of-N (`winsNeeded > 0`). */
    val matchScore: String? get() = if (winsNeeded > 0) "$wins/$winsNeeded" else null
}

/**
 * One permanent on a battlefield.
 *
 * @property isOfferedByServer the server listed this object in `canPlayObjects` **while the viewer held
 *   priority**. It is false for everything when priority is not held — see the class docs on [BoardUi].
 *   It is a *mark*, never an affordance: this board offers no way to act on it (story 0056 does).
 */
data class PermanentUi(
    val objectId: String,
    val card: CardUi,
    val isTapped: Boolean,
    val hasSummoningSickness: Boolean,
    val damage: Int,
    val isAttacking: Boolean,
    val isBlocking: Boolean,
    val isOfferedByServer: Boolean,
)

/** One card in the viewer's hand (`GameState.hand`). */
data class HandCardUi(
    val objectId: String,
    val card: CardUi,
    val isOfferedByServer: Boolean,
)

/** The viewer's hand region, which is legitimately empty on the very first snapshot. */
data class HandUi(
    val cards: List<HandCardUi> = emptyList(),
) {
    val count: Int get() = cards.size

    /** Whether the hand shows its empty state — the state the *first* snapshot after joining is in. */
    val isEmpty: Boolean get() = cards.isEmpty()
}

/** One object on the stack. */
data class StackEntryUi(
    val objectId: String,
    val card: CardUi,
)

/**
 * The stack region. Usually empty and filled abruptly, so the board gives it a **fixed** slice of the
 * layout: filling it must never reflow the battlefields (requirements §4.1).
 *
 * Not authoritative between pushes: the server does not push the opponent a snapshot when a cast is
 * cancelled (§17.4), so a spell the caster has already rewound can linger here until the next push.
 */
data class StackUi(
    val entries: List<StackEntryUi> = emptyList(),
) {
    val count: Int get() = entries.size

    val isEmpty: Boolean get() = entries.isEmpty()

    /** The object on top of the stack (first, because [BoardUi.from] reverses upstream's order). */
    val top: StackEntryUi? get() = entries.firstOrNull()
}

/** Turn / phase / step, and whose turn it is. */
data class TurnUi(
    val number: Int = 0,
    val phase: TurnPhase = TurnPhase.Unknown,
    val step: PhaseStep = PhaseStep.Unknown,
    val activePlayerName: String? = null,
    val isViewerTurn: Boolean = false,
) {
    /** Whether the turn indicator shows its "not started" state (no snapshot has set a turn yet). */
    val hasStarted: Boolean get() = number > 0

    /** "Turn 3" — or the pre-game state when no turn has been reported. */
    val turnLabel: String get() = if (hasStarted) "Turn $number" else "Turn —"

    /** The step's own name where the server named one, else the phase's. */
    val phaseLabel: String get() = step.label() ?: phase.label() ?: "…"
}

/**
 * The **explicit** priority statement of requirements §4.2 — stated as words, in addition to any
 * per-card mark, because "I hold priority and there is nothing to play" is a real state that a card
 * highlight alone cannot express.
 *
 * Driven by `GameState.viewerHasPriority` and nothing else. In particular it is **never** inferred from
 * `playable` being empty: that is exactly the ambiguity this exists to remove.
 */
sealed interface PriorityUi {
    /** The line the board always shows, even with every floating control hidden (§16.3). */
    val headline: String

    /** An optional second line; null when there is nothing more to say. */
    val detail: String?

    /** No snapshot has arrived yet, so nothing is known about priority. */
    data object NotStarted : PriorityUi {
        override val headline: String get() = "Waiting for the game"
        override val detail: String? get() = "The board fills in as the server sends it."
    }

    /**
     * The viewer holds priority.
     *
     * @property anythingOffered whether the server offered anything at all in `canPlayObjects`. When it
     *   did not, the board says so out loud rather than leaving the player to read an absence of glow.
     */
    data class Yours(
        val anythingOffered: Boolean,
    ) : PriorityUi {
        override val headline: String get() = "Your turn to act"
        override val detail: String?
            get() = if (anythingOffered) null else "Nothing the server offers you right now."
    }

    /**
     * The server is **waiting on the viewer** without the viewer holding priority.
     *
     * Found on device, and it is not a corner case: the very first thing a new game does is ask one
     * seat to choose who goes first, and it does that *before* priority exists. On that snapshot
     * `viewerHasPriority` is false while `prompt` is `Select a starting player` — so a banner driven by
     * priority alone reads "Waiting for opponent" at the exact moment the whole game is waiting on
     * **you**. Requirements §16.3 names that failure directly: whatever else is hidden, the player must
     * never be left unable to tell a waiting game from a frozen one.
     *
     * Its producer is `GameState.prompt`, which 0052 defines as "**the** outstanding question, or null
     * when the server is not waiting on the viewer" — a server fact, not an inference. It is a separate
     * state from [Yours] because the two are genuinely different: being asked a question is not the
     * same as holding priority, and the board should not claim otherwise.
     */
    data object Asked : PriorityUi {
        override val headline: String get() = "The server is waiting on you"
        override val detail: String? get() = "Answer with the controls over the board."
    }

    /**
     * Someone else holds priority.
     *
     * @property playerName the server's display name for the holder (`GameState.priorityPlayerName`),
     *   or null — upstream exposes no priority id, so this is a label and nothing is keyed off it.
     */
    data class Opponent(
        val playerName: String?,
    ) : PriorityUi {
        override val headline: String get() = "Waiting for opponent"
        override val detail: String? get() = playerName?.let { "$it has priority" }
    }

    companion object {
        internal fun of(
            hasSnapshot: Boolean,
            viewerHasPriority: Boolean,
            isBeingAsked: Boolean,
            priorityPlayerName: String?,
            anythingOffered: Boolean,
        ): PriorityUi =
            when {
                !hasSnapshot -> NotStarted
                viewerHasPriority -> Yours(anythingOffered = anythingOffered)
                // Ordered deliberately: priority wins when both are true (a Select prompt *is* your
                // priority window), and an outstanding question beats "waiting for opponent" when it is
                // not (the starting-player choice, seen live).
                isBeingAsked -> Asked
                else -> Opponent(playerName = priorityPlayerName?.cleanedOrNull())
            }
    }
}

/**
 * The viewer's priority clock.
 *
 * **Absent on an untimed table.** The server reports `0` there (verified live), and "0 seconds left" on
 * a table with no clock would be a lie, so [of] returns null and the board shows no timer at all.
 */
data class ClockUi(
    val seconds: Int,
) {
    /** `m:ss`, e.g. `1:05`. */
    val label: String get() = "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"

    companion object {
        /** A clock for [seconds], or null when the table is untimed (upstream reports 0). */
        fun of(seconds: Int): ClockUi? = if (seconds > 0) ClockUi(seconds) else null
    }
}

/**
 * A card as the board draws it: the design-system display fields, the 0031 art identity, and the
 * battlefield-relevant extras.
 *
 * @property art the printing to fetch art for — `(setCode, collectorNumber)`, which is exactly how
 *   0030/0031 identify a printing. Null when the server sent no printing, or when the card is face
 *   down: a face-down card's art must not be fetched, because fetching it would show the player a card
 *   they are not entitled to see.
 */
data class CardUi(
    val name: String,
    val display: CardDisplay,
    val art: CardArtRequest?,
    val powerToughness: String?,
    val isFaceDown: Boolean,
)

/** The name shown for a card the viewer is not entitled to identify. */
const val FACE_DOWN_NAME: String = "Face-down"

private fun GamePlayer.toSeatUi(
    playableIds: Set<String>,
    attackerIds: Set<String>,
    blockerIds: Set<String>,
): SeatUi =
    SeatUi(
        playerId = playerId,
        name = name,
        life = life,
        libraryCount = libraryCount,
        handCount = handCount,
        graveyardCount = graveyardCount,
        exileCount = exileCount,
        floatingMana = manaPool.total,
        wins = wins,
        winsNeeded = winsNeeded,
        isViewer = isViewer,
        isActive = isActive,
        hasPriority = hasPriority,
        isHuman = isHuman,
        hasLeft = hasLeft,
        battlefield = battlefield.map { it.toPermanentUi(playableIds, attackerIds, blockerIds) },
    )

private fun GamePermanent.toPermanentUi(
    playableIds: Set<String>,
    attackerIds: Set<String>,
    blockerIds: Set<String>,
): PermanentUi =
    PermanentUi(
        objectId = card.id,
        card = card.toCardUi(),
        isTapped = isTapped,
        hasSummoningSickness = hasSummoningSickness,
        damage = damage,
        isAttacking = card.id in attackerIds,
        isBlocking = card.id in blockerIds,
        isOfferedByServer = card.id in playableIds,
    )

internal fun GameCard.toCardUi(): CardUi {
    val shownName = if (isFaceDown) FACE_DOWN_NAME else name
    val pt =
        if (power.isNullOrBlank() || toughness.isNullOrBlank()) null else "$power/$toughness"
    return CardUi(
        name = shownName,
        display =
            CardDisplay(
                name = shownName,
                // Null for lands upstream; carried through as null so no empty cost is drawn.
                manaCost = if (isFaceDown) null else manaCost?.cleanedOrNull(),
                typeLine = if (isFaceDown) null else typeLine?.cleanedOrNull(),
                oracleText = if (isFaceDown) null else rules.mapNotNull { it.cleanedOrNull() }.joinToString("\n").ifBlank { null },
            ),
        art = if (isFaceDown) null else artRequestOf(setCode, collectorNumber),
        powerToughness = if (isFaceDown) null else pt,
        isFaceDown = isFaceDown,
    )
}

/**
 * The 0031 art identity for a printing, or null when the server did not name one — in which case the
 * design-system placeholder is what renders, and the card's text stays readable.
 */
internal fun artRequestOf(
    setCode: String?,
    collectorNumber: String?,
): CardArtRequest? {
    if (setCode.isNullOrBlank() || collectorNumber.isNullOrBlank()) return null
    return CardArtRequest(
        setCode = setCode,
        collectorNumber = collectorNumber,
        face = CardArtFace.FRONT,
        size = CardArtSize.SMALL,
    )
}

/**
 * Strips the server's HTML out of a line of narration or prompt text.
 *
 * XMage's game log is HTML — `Draw - Waiting for <font color='#20B2AA'>Computer</font>` — and the board
 * must show neither raw markup nor a stripped-to-nothing blank. Tags are removed, the handful of
 * entities upstream actually emits are decoded, and whitespace is collapsed.
 */
internal fun stripServerMarkup(raw: String): String =
    raw
        .replace(BREAK_TAG, " ")
        .replace(ANY_TAG, "")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace(WHITESPACE_RUN, " ")
        .trim()

/** [stripServerMarkup], returning null when nothing readable is left — so no blank line is drawn. */
internal fun String.cleanedOrNull(): String? = stripServerMarkup(this).ifBlank { null }

private val BREAK_TAG = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
private val ANY_TAG = Regex("""<[^>]*>""")
private val WHITESPACE_RUN = Regex("""\s+""")

/** The server's own name for a step, or null when it reported none this build recognises. */
private fun PhaseStep.label(): String? =
    when (this) {
        PhaseStep.Untap -> "Untap"
        PhaseStep.Upkeep -> "Upkeep"
        PhaseStep.Draw -> "Draw"
        PhaseStep.PrecombatMain -> "Main 1"
        PhaseStep.BeginCombat -> "Begin combat"
        PhaseStep.DeclareAttackers -> "Attackers"
        PhaseStep.DeclareBlockers -> "Blockers"
        PhaseStep.FirstCombatDamage -> "First-strike damage"
        PhaseStep.CombatDamage -> "Combat damage"
        PhaseStep.EndCombat -> "End of combat"
        PhaseStep.PostcombatMain -> "Main 2"
        PhaseStep.EndTurn -> "End step"
        PhaseStep.Cleanup -> "Cleanup"
        PhaseStep.Unknown -> null
    }

/** The server's own name for a phase, or null when it reported none this build recognises. */
private fun TurnPhase.label(): String? =
    when (this) {
        TurnPhase.Beginning -> "Beginning"
        TurnPhase.PrecombatMain -> "Main 1"
        TurnPhase.Combat -> "Combat"
        TurnPhase.PostcombatMain -> "Main 2"
        TurnPhase.End -> "Ending"
        TurnPhase.Unknown -> null
    }
