package magefree.bridge.session

import magefree.protocol.ChatEvent
import magefree.protocol.ChatKind
import magefree.protocol.GameActionCode
import magefree.protocol.GameActionResult
import magefree.protocol.GameCardView
import magefree.protocol.GameInformed
import magefree.protocol.GameOver
import magefree.protocol.GamePlayableObject
import magefree.protocol.GamePlayerView
import magefree.protocol.GamePrompted
import magefree.protocol.GameStarted
import magefree.protocol.GameStateSnapshot
import magefree.protocol.GameStateUnavailable
import magefree.protocol.GameStateUnavailableCode
import magefree.protocol.GameStateUpdated
import magefree.protocol.GameStateView
import magefree.protocol.GetGameState
import magefree.protocol.SelectPrompt
import magefree.protocol.SessionStateCode
import magefree.protocol.SessionStatus
import magefree.protocol.WatchingGame
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit coverage of [GameStateCache] (story 0054): what fills it, what it answers, what empties it, and
 * — the property the whole story turns on — that two caches never see each other's snapshots.
 *
 * The end-to-end version of the same claims, through the real WebSocket + [SessionCoordinator] +
 * [LiveSession] pump, lives in [SessionCoordinatorTest]; these are the unit half.
 */
class GameStateCacheTest {
    private fun view(
        turn: Int = 1,
        viewer: String? = "p-1",
        hand: List<String> = emptyList(),
        playable: List<String> = emptyList(),
    ) = GameStateView(
        turn = turn,
        activePlayerId = "p-1",
        activePlayerName = "alice",
        viewerPlayerId = viewer,
        viewerHasPriority = playable.isNotEmpty(),
        players =
            listOf(
                GamePlayerView(playerId = "p-1", name = "alice", life = 20, viewer = viewer == "p-1"),
                GamePlayerView(playerId = "p-2", name = "bob", life = 20, viewer = viewer == "p-2"),
            ),
        hand = hand.map { GameCardView(id = it, name = "Forest", setCode = "M21", collectorNumber = "272") },
        playable = playable.map { GamePlayableObject(objectId = it, abilityIds = listOf("a-$it")) },
    )

    private fun snapshotOf(
        cache: GameStateCache,
        gameId: String,
    ): GameStateSnapshot = assertInstanceOf(GameStateSnapshot::class.java, cache.answer(GetGameState(gameId = gameId)))

    @Test
    fun `before any snapshot the answer is a typed no-state, never an empty board`() {
        // The acceptance criterion: an all-defaults GameStateView is a *legal* snapshot (no players, no
        // hand, nothing playable is exactly what a spectator of a not-yet-started game gets), so a
        // client cannot tell one from the truth. The absence has to be a different type entirely.
        val cache = GameStateCache()

        val reply = cache.answer(GetGameState(gameId = "g-1"))

        val unavailable = assertInstanceOf(GameStateUnavailable::class.java, reply)
        assertEquals("g-1", unavailable.gameId)
        assertEquals(GameStateUnavailableCode.NO_STATE_YET, unavailable.reason)
        assertEquals(GameStateCache.NO_SNAPSHOT_YET, unavailable.detail)
        assertEquals(0, cache.size())
    }

    @Test
    fun `every state-carrying game push fills the cache, and the newest one wins`() {
        // Reachability (standard 2): these five are the *only* producers, and they are exactly the
        // messages LiveSession's pump relays. Nothing else in the bridge writes a snapshot.
        val cache = GameStateCache(now = { 1_000L })

        cache.observe(GameStarted(gameId = "g-1", state = view(turn = 1)))
        assertEquals(1, snapshotOf(cache, "g-1").state.turn)

        cache.observe(GameStateUpdated(gameId = "g-1", state = view(turn = 2)))
        assertEquals(2, snapshotOf(cache, "g-1").state.turn)

        cache.observe(GameInformed(gameId = "g-1", message = "alice draws a card", state = view(turn = 3)))
        assertEquals(3, snapshotOf(cache, "g-1").state.turn)

        cache.observe(
            GamePrompted(gameId = "g-1", state = view(turn = 4), prompt = SelectPrompt(message = "Play a land")),
        )
        assertEquals(4, snapshotOf(cache, "g-1").state.turn, "the latest snapshot replaces the previous one wholesale")
        assertEquals(1, cache.size())
    }

    @Test
    fun `a GamePrompted's prompt is cached and served on the next answer (story 0074)`() {
        // The root cause this story fixes: GamePrompted already carries the prompt alongside the
        // state, but the cache used to discard it. Prove it now survives into the reply.
        val cache = GameStateCache()
        val prompt = SelectPrompt(message = "Play a land")

        cache.observe(GamePrompted(gameId = "g-1", state = view(turn = 4), prompt = prompt))

        assertEquals(prompt, snapshotOf(cache, "g-1").prompt)
    }

    @Test
    fun `a later state-only push retires a previously cached prompt (story 0074)`() {
        // A plain state push or narration after a prompt means the prompt is no longer live truth --
        // re-serving it on a later resync would tell a rejoining client to answer a question that is
        // no longer outstanding.
        val cache = GameStateCache()
        cache.observe(GamePrompted(gameId = "g-1", state = view(turn = 4), prompt = SelectPrompt(message = "Play a land")))
        assertNotEquals(null, snapshotOf(cache, "g-1").prompt, "sanity check: the prompt is cached first")

        cache.observe(GameStateUpdated(gameId = "g-1", state = view(turn = 5)))

        assertEquals(null, snapshotOf(cache, "g-1").prompt)
    }

    @Test
    fun `the cached state is the pushed one verbatim, not a rebuild of it`() {
        // Story 0053 is where accumulation and inference live; this story replays. Asserting identity
        // (not just equality) is what pins that: a cache that rebuilt, merged or normalised a snapshot
        // would still be `equals`, and would still be wrong.
        val cache = GameStateCache()
        val pushed = view(turn = 7, hand = listOf("c-1", "c-2"), playable = listOf("c-1"))

        cache.observe(GameStateUpdated(gameId = "g-1", state = pushed))

        assertSame(pushed, snapshotOf(cache, "g-1").state)
    }

    @Test
    fun `a message that carries no snapshot leaves the cache alone`() {
        val cache = GameStateCache()

        // A GameInformed *can* carry a snapshot (0051 models it as nullable) — one without must not be
        // treated as "the board is now empty".
        cache.observe(GameStateUpdated(gameId = "g-1", state = view(turn = 5)))
        cache.observe(GameInformed(gameId = "g-1", message = "You lost the roll", personal = true))
        cache.observe(WatchingGame(gameId = "g-1", tableId = "t-1"))
        cache.observe(GameActionResult(action = GameActionCode.SEND_BOOLEAN, ok = true, requestId = "r-1"))
        cache.observe(SessionStatus(state = SessionStateCode.CONNECTED))
        cache.observe(ChatEvent(text = "hi", username = "alice", timestampEpochMs = 0L, kind = ChatKind.TALK))

        assertEquals(5, snapshotOf(cache, "g-1").state.turn)
        assertEquals(1, cache.size())
    }

    @Test
    fun `each game is cached separately and answered by its own id`() {
        val cache = GameStateCache()

        cache.observe(GameStateUpdated(gameId = "g-1", state = view(turn = 1)))
        cache.observe(GameStateUpdated(gameId = "g-2", state = view(turn = 9)))

        assertEquals(1, snapshotOf(cache, "g-1").state.turn)
        assertEquals(9, snapshotOf(cache, "g-2").state.turn)
        assertInstanceOf(GameStateUnavailable::class.java, cache.answer(GetGameState(gameId = "g-3")))
    }

    @Test
    fun `game over drops that game, leaving the others`() {
        // The cache must not outlive the thing it describes: a client reconnecting after the end is told
        // there is no state rather than handed a board that no longer exists.
        val cache = GameStateCache()
        cache.observe(GameStateUpdated(gameId = "g-1", state = view(turn = 4)))
        cache.observe(GameStateUpdated(gameId = "g-2", state = view(turn = 4)))

        cache.observe(GameOver(gameId = "g-1", message = "alice has won the game", state = view(turn = 5)))

        val reply = assertInstanceOf(GameStateUnavailable::class.java, cache.answer(GetGameState(gameId = "g-1")))
        assertEquals(GameStateUnavailableCode.NO_STATE_YET, reply.reason)
        assertEquals(4, snapshotOf(cache, "g-2").state.turn, "the other game is untouched")
        assertEquals(1, cache.size())
    }

    @Test
    fun `clear empties the cache`() {
        val cache = GameStateCache()
        cache.observe(GameStateUpdated(gameId = "g-1", state = view(turn = 4)))

        cache.clear()

        assertEquals(0, cache.size())
        assertInstanceOf(GameStateUnavailable::class.java, cache.answer(GetGameState(gameId = "g-1")))
    }

    @Test
    fun `the capture time is stamped from the clock so staleness is knowable`() {
        var clock = 1_700_000_000_000L
        val cache = GameStateCache(now = { clock })

        cache.observe(GameStarted(gameId = "g-1", state = view(turn = 1)))
        assertEquals(1_700_000_000_000L, snapshotOf(cache, "g-1").capturedAtEpochMs)

        clock += 45_000L
        cache.observe(GameStateUpdated(gameId = "g-1", state = view(turn = 2)))
        assertEquals(
            1_700_000_045_000L,
            snapshotOf(cache, "g-1").capturedAtEpochMs,
            "a fresh push re-stamps the capture time; the client can then reason about the gap",
        )
    }

    @Test
    fun `two caches never see each other's snapshots — the per-session guarantee`() {
        // **The test that fails if the cache is ever made shared.** A `GameView` is built for a specific
        // player: `viewerPlayerId`, that player's own hand, and `canPlayObjects` populated only for
        // whoever holds priority. Alice and Bob are in the *same game*, so a bridge-wide cache keyed by
        // game id alone would answer both from one entry — handing one player the other's hand — and
        // every single-client test would still be green.
        val alice = GameStateCache()
        val bob = GameStateCache()

        alice.observe(
            GameStateUpdated(
                gameId = SHARED_GAME,
                state = view(turn = 3, viewer = "p-1", hand = listOf("a-1", "a-2"), playable = listOf("a-1")),
            ),
        )
        bob.observe(
            GameStateUpdated(
                gameId = SHARED_GAME,
                state = view(turn = 3, viewer = "p-2", hand = listOf("b-1")),
            ),
        )

        val forAlice = snapshotOf(alice, SHARED_GAME).state
        val forBob = snapshotOf(bob, SHARED_GAME).state

        assertNotSame(forAlice, forBob, "the two sessions must not be answered from one entry")
        assertEquals("p-1", forAlice.viewerPlayerId)
        assertEquals("p-2", forBob.viewerPlayerId)
        assertEquals(listOf("a-1", "a-2"), forAlice.hand.map { it.id })
        assertEquals(listOf("b-1"), forBob.hand.map { it.id }, "Bob must never be served Alice's hand")
        assertTrue(forAlice.playable.isNotEmpty(), "only the priority holder's view carries canPlayObjects")
        assertTrue(forBob.playable.isEmpty(), "and the other seat's view must not acquire it")
    }

    @Test
    fun `the per-session cache is bounded so an unfinished game cannot leak forever`() {
        // Games are freed at GAME_OVER, so the live set is normally the handful a session is in. The cap
        // is the ceiling for the case where a watched game never reports one.
        val cache = GameStateCache()

        repeat(GameStateCache.MAX_GAMES + 4) { index ->
            cache.observe(GameStateUpdated(gameId = "g-$index", state = view(turn = index)))
        }

        assertEquals(GameStateCache.MAX_GAMES, cache.size())
        assertInstanceOf(GameStateUnavailable::class.java, cache.answer(GetGameState(gameId = "g-0")))
        val newest = GameStateCache.MAX_GAMES + 3
        assertEquals(newest, snapshotOf(cache, "g-$newest").state.turn)
    }

    private companion object {
        /** One game id, two sessions — the shape the per-session guarantee is about. */
        const val SHARED_GAME = "g-shared"
    }
}
