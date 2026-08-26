package magefree.bridge.session

import magefree.protocol.GameInformed
import magefree.protocol.GameOver
import magefree.protocol.GamePrompt
import magefree.protocol.GamePrompted
import magefree.protocol.GameStarted
import magefree.protocol.GameStateSnapshot
import magefree.protocol.GameStateUnavailable
import magefree.protocol.GameStateUnavailableCode
import magefree.protocol.GameStateUpdated
import magefree.protocol.GameStateView
import magefree.protocol.GetGameState
import magefree.protocol.ServerMessage
import org.slf4j.LoggerFactory
import java.util.Collections

/**
 * The bridge's answer to a question upstream cannot answer: **what does the board look like right now**
 *.
 *
 * XMage has no "get game" verb, and re-joining a running game does not resync (`GameController.join`
 * on an already-running game only logs "rejoined"), so a client that dropped its socket is blind until
 * the *next* push — which may be minutes away while an opponent thinks, or never. The bridge, however,
 * sees every snapshot on its way past, and a snapshot is the whole truth rather than a delta
 *. So keeping the most recent one is sufficient, and that is exactly all this does.
 *
 * **Per session, and that is not an optimisation.** One instance belongs to one [LiveSession]. A
 * `GameView` is built by the server *for a specific player*: it carries that player's `myPlayerId`, that
 * player's own hand, and `canPlayObjects` populated **only** for whoever holds priority. A cache shared
 * across sessions would therefore hand one player another player's view — including their hand — and
 * every single-client test would pass. What is cached here is what *this* session was actually sent.
 *
 * **What produces the cached value (reachability, standard 2).** [observe] is called from
 * [LiveSession]'s outbound pump, on every [ServerMessage] the session relays — the same mapped
 * `magefree.bridge.mapping` output the app receives. Nothing else writes to it, and no value in it was
 * built here: a snapshot is stored byte-identical to the one that crossed the wire.
 *
 * **What invalidates it.** A [GameOver] drops that game's entry (the cache must not outlive the thing
 * it describes), and [clear] drops everything when the session's pump ends or the session is evicted.
 *
 * **What it deliberately does not do.** No accumulation, no merging, no inference across snapshots —
 * accumulation and inference are not this object's job. It holds the latest snapshot verbatim: it never remembers what a
 * card *was* once the server stops showing it, never combines an older snapshot with a newer one, and
 * never reconstructs history.
 *
 * @param now the wall clock stamped onto each capture (overridable so a test can assert the exact value).
 */
public class GameStateCache(
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    private class Entry(
        val state: GameStateView,
        val capturedAtEpochMs: Long,
        val prompt: GamePrompt?,
    )

    /**
     * Access-ordered LRU, bounded at [MAX_GAMES] and synchronized because [observe] runs on the session
     * pump while [answer] runs on a socket coroutine.
     *
     * The bound is belt-and-braces rather than the real limit: a game's entry is dropped when the game
     * ends, so a session's live set is the handful of games it is actually in (a best-of-three match is
     * three ids, each freed as it finishes). The cap only matters if a game a session watched never
     * produces its `GAME_OVER` — a leak of one snapshot per such game, for the session's life, which
     * this turns into a fixed ceiling.
     */
    private val snapshots: MutableMap<String, Entry> =
        Collections.synchronizedMap(
            object : LinkedHashMap<String, Entry>(INITIAL_CAPACITY, LOAD_FACTOR, true) {
                override fun removeEldestEntry(eldest: Map.Entry<String, Entry>): Boolean = size > MAX_GAMES
            },
        )

    /**
     * Record [message] if it carries a game snapshot for this session, or drop the game's entry if it
     * ends the game. Everything else — a correlated reply, a session status, a lobby/table/chat frame,
     * a `WATCHGAME` (which carries no state) — passes through untouched.
     *
     * Called for **every** message the session relays, before it is queued to the app socket, so a
     * snapshot pushed while the session is parked (keeps the pump running with no socket
     * attached) is cached just the same. That is what makes the cache keep advancing while the app is
     * away rather than freezing at the moment the socket died.
     *
     * **The prompt half.** Only [GamePrompted] ever carries a `prompt` — every other
     * branch here passes `null`, which *clears* whatever was cached: a plain state push or narration
     * arriving after a prompt means the prompt is no longer the live truth (answered, superseded, or
     * the moment passed), so serving it again on a later resync would be wrong. `GamePrompted` is
     * therefore the only producer of a non-null cached prompt, and any other observed message is what
     * retires one.
     */
    public fun observe(message: ServerMessage) {
        // Diagnostic for the mulligan resync case. This traces every observation this cache makes for
        // a game, so a live repro's bridge log shows exactly what retired the cached prompt (if
        // anything did) rather than guessing from source alone. Remove once the root cause is fixed.
        val before = message.gameIdOrNull()?.let { snapshots[it]?.prompt }
        when (message) {
            is GameStarted -> put(message.gameId, message.state, prompt = null)
            is GameStateUpdated -> put(message.gameId, message.state, prompt = null)
            // The snapshot is optional on this one (0051 models it as nullable); the narration is not.
            is GameInformed -> message.state?.let { put(message.gameId, it, prompt = null) }
            is GamePrompted -> put(message.gameId, message.state, prompt = message.prompt)
            // The game is over: the cache must not outlive the thing it describes. The final snapshot is
            // deliberately not kept — a client that reconnects after the end is told there is no state
            // rather than being handed a board that no longer exists, and the `GameOver` push itself is
            // what tells it the game ended (it is buffered for a parked session like any other push).
            is GameOver -> snapshots.remove(message.gameId)
            else -> Unit
        }
        message.gameIdOrNull()?.let { gameId ->
            val after = snapshots[gameId]?.prompt
            if (before != after) {
                LOGGER.info(
                    "GameStateCache[{}] observed {} -> prompt {} (was {})",
                    gameId,
                    message::class.simpleName,
                    after,
                    before,
                )
            }
        }
    }

    /** The game id [message] carries, for messages this cache keys on — `null` for everything else. */
    private fun ServerMessage.gameIdOrNull(): String? =
        when (this) {
            is GameStarted -> gameId
            is GameStateUpdated -> gameId
            is GameInformed -> gameId
            is GamePrompted -> gameId
            is GameOver -> gameId
            else -> null
        }

    /**
     * The reply to [request]: a [GameStateSnapshot] carrying the last snapshot this session was sent for
     * that game, or a typed [GameStateUnavailable] with [GameStateUnavailableCode.NO_STATE_YET].
     *
     * A miss is **never** an empty [GameStateSnapshot]. An all-defaults [GameStateView] is a perfectly
     * legal snapshot — no players, no hand, nothing playable — so a client cannot tell it from a real
     * board and would show it, and let a player act on it, as though it were true.
     *
     * [GameStateSnapshot.prompt] is [Entry.prompt] verbatim — `null` unless the most
     * recent thing [observe] recorded for this game was a [GamePrompted] with nothing since to retire
     * it. See [observe]'s KDoc for why re-serving it is correct, not a guess.
     */
    public fun answer(request: GetGameState): ServerMessage {
        val entry = snapshots[request.gameId]
        // Temporary diagnostic (follow-up) — see observe()'s KDoc.
        LOGGER.info("GameStateCache[{}] answer -> prompt {}", request.gameId, entry?.prompt)
        if (entry == null) return unavailable(request.gameId)
        return GameStateSnapshot(
            gameId = request.gameId,
            state = entry.state,
            capturedAtEpochMs = entry.capturedAtEpochMs,
            prompt = entry.prompt,
        )
    }

    /** Drops every cached game. Called when the session's pump ends and when the session is evicted. */
    public fun clear() {
        snapshots.clear()
    }

    /** How many games are currently cached — for assertions about invalidation. */
    public fun size(): Int = snapshots.size

    private fun put(
        gameId: String,
        state: GameStateView,
        prompt: GamePrompt?,
    ) {
        snapshots[gameId] = Entry(state = state, capturedAtEpochMs = now(), prompt = prompt)
    }

    private fun unavailable(gameId: String): GameStateUnavailable =
        GameStateUnavailable(
            gameId = gameId,
            reason = GameStateUnavailableCode.NO_STATE_YET,
            detail = NO_SNAPSHOT_YET,
        )

    public companion object {
        /** The detail paired with [GameStateUnavailableCode.NO_STATE_YET]; the app renders its own wording. */
        public const val NO_SNAPSHOT_YET: String = "no snapshot has been received for this game on this session"

        /** The ceiling on simultaneously cached games per session (see [snapshots]). */
        internal const val MAX_GAMES: Int = 8

        private const val INITIAL_CAPACITY: Int = 16
        private const val LOAD_FACTOR: Float = 0.75f

        private val LOGGER = LoggerFactory.getLogger(GameStateCache::class.java)
    }
}
