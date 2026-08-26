package magefree.bridge.session

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import magefree.protocol.GameCardView
import magefree.protocol.GameOver
import magefree.protocol.GamePlayerView
import magefree.protocol.GameStarted
import magefree.protocol.GameStateSnapshot
import magefree.protocol.GameStateUnavailable
import magefree.protocol.GameStateUnavailableCode
import magefree.protocol.GameStateUpdated
import magefree.protocol.GameStateView
import magefree.protocol.GetGameState
import magefree.protocol.SessionStateCode
import magefree.protocol.SessionStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

/**
 * The wiring half: a [LiveSession]'s cache is fed by its outbound pump,
 * that it survives (and keeps advancing across) a park, and that it survives the *session itself*
 * ending too — outliving eviction for the same username, though never crossing between usernames.
 *
 * [GameStateCacheTest] proves the cache's own rules; this proves the thing that actually fills it in
 * production. Standard 2 in test form: the producer named in the design is the producer under test.
 */
class SessionGameStateCacheTest {
    private fun view(
        turn: Int,
        viewer: String = "p-1",
        hand: List<String> = emptyList(),
    ) = GameStateView(
        turn = turn,
        activePlayerId = "p-1",
        activePlayerName = "alice",
        viewerPlayerId = viewer,
        players = listOf(GamePlayerView(playerId = viewer, name = "alice", life = 20, viewer = true)),
        hand = hand.map { GameCardView(id = it, name = "Forest", setCode = "M21", collectorNumber = "272") },
    )

    /** Polls until [condition] holds, so a test never depends on the pump's scheduling. */
    private suspend fun awaitCache(
        live: LiveSession,
        what: String,
        condition: (LiveSession) -> Boolean,
    ) {
        val settled =
            withTimeoutOrNull(PUMP_TIMEOUT) {
                while (!condition(live)) delay(POLL_MS)
                true
            }
        if (settled != true) throw AssertionError("timed out waiting for $what; cached=${live.cachedGameCount()}")
    }

    private fun snapshotOf(
        live: LiveSession,
        gameId: String,
    ): GameStateSnapshot = assertInstanceOf(GameStateSnapshot::class.java, live.gameState(GetGameState(gameId = gameId)))

    private fun registryScenario(block: suspend (SessionRegistry) -> Unit) {
        val registry = SessionRegistry(ResumeConfig())
        try {
            runBlocking { block(registry) }
        } finally {
            registry.shutdown()
        }
    }

    @Test
    fun `a snapshot relayed by the session's pump is cached on that session`() =
        registryScenario { registry ->
            val fake = FakeUpstreamSession(listOf(SessionStatus(SessionStateCode.CONNECTED)))
            val live = registry.createSession(fake, Credentials("alice", null))

            assertInstanceOf(
                GameStateUnavailable::class.java,
                live.gameState(GetGameState(gameId = GAME)),
                "before any push the session must answer a typed no-state, never an empty board",
            )

            fake.emit(GameStarted(gameId = GAME, state = view(turn = 1, hand = listOf("c-1"))))
            awaitCache(live, "the pumped GAME_INIT snapshot to reach the cache") { it.cachedGameCount() == 1 }

            val snapshot = snapshotOf(live, GAME)
            assertEquals(1, snapshot.state.turn)
            assertEquals(listOf("c-1"), snapshot.state.hand.map { it.id })
        }

    @Test
    fun `a snapshot pushed while the session is parked is cached just the same`() =
        registryScenario { registry ->
            // The pump keeps running with no socket attached, which is exactly the window this
            // exists for: the cached board must go on advancing while the app is away, not freeze
            // at the moment the socket died. If the cache were fed from the *socket* forwarder instead of
            // the pump, this is the test that would fail — and only a real reconnect would reveal it.
            val fake = FakeUpstreamSession(listOf(SessionStatus(SessionStateCode.CONNECTED)))
            val live = registry.createSession(fake, Credentials("alice", null))
            val resumeId = registry.register(live)

            fake.emit(GameStateUpdated(gameId = GAME, state = view(turn = 1)))
            awaitCache(live, "the pre-park snapshot") { it.cachedGameCount() == 1 }

            registry.park(resumeId)

            fake.emit(GameStateUpdated(gameId = GAME, state = view(turn = 6)))
            awaitCache(live, "the snapshot pushed while parked") { snapshotOf(it, GAME).state.turn == 6 }

            assertEquals(6, snapshotOf(live, GAME).state.turn)
        }

    @Test
    fun `the game ending drops that game from the session's cache`() =
        registryScenario { registry ->
            val fake = FakeUpstreamSession(listOf(SessionStatus(SessionStateCode.CONNECTED)))
            val live = registry.createSession(fake, Credentials("alice", null))

            fake.emit(GameStateUpdated(gameId = GAME, state = view(turn = 3)))
            awaitCache(live, "the snapshot") { it.cachedGameCount() == 1 }

            fake.emit(GameOver(gameId = GAME, message = "alice has won the game", state = view(turn = 4)))
            awaitCache(live, "the cache to be invalidated by GAME_OVER") { it.cachedGameCount() == 0 }

            val reply = assertInstanceOf(GameStateUnavailable::class.java, live.gameState(GetGameState(gameId = GAME)))
            assertEquals(GameStateUnavailableCode.NO_STATE_YET, reply.reason)
        }

    @Test
    fun `evicting the session preserves its cache for the same username's next session`() =
        registryScenario { registry ->
            // the defect, reproduced: an eviction is not necessarily the game ending, or even
            // the same person leaving — a resume-TTL expiry, a sign-out, or (the live case) an upstream
            // keepalive failure all evict, and XMage gives a rejoining player no way to ask for a fresh
            // state (GameController.join's rejoin path only logs; watch refuses a seated player). So the
            // cache must survive to hand to whichever session is next for that username, not die here.
            val firstUpstream = FakeUpstreamSession(listOf(SessionStatus(SessionStateCode.CONNECTED)))
            val first = registry.createSession(firstUpstream, Credentials("alice", null))
            val resumeId = registry.register(first)

            firstUpstream.emit(GameStateUpdated(gameId = GAME, state = view(turn = 3)))
            awaitCache(first, "the snapshot") { it.cachedGameCount() == 1 }

            registry.evict(resumeId)

            // A brand-new session for the same username — not a resume of the evicted one — must still
            // answer from the preserved snapshot rather than starting blank.
            val secondUpstream = FakeUpstreamSession(listOf(SessionStatus(SessionStateCode.CONNECTED)))
            val second = registry.createSession(secondUpstream, Credentials("alice", null))

            assertEquals(3, snapshotOf(second, GAME).state.turn)
        }

    @Test
    fun `a fresh session for a different username never inherits another user's cache`() =
        registryScenario { registry ->
            // The falsifying test for "per username, not bridge-wide": alice's evicted cache must not
            // leak into a brand-new session for a different person, including one that never played GAME.
            val aliceUpstream = FakeUpstreamSession(listOf(SessionStatus(SessionStateCode.CONNECTED)))
            val alice = registry.createSession(aliceUpstream, Credentials("alice", null))
            val resumeId = registry.register(alice)

            aliceUpstream.emit(GameStateUpdated(gameId = GAME, state = view(turn = 3)))
            awaitCache(alice, "alice's snapshot") { it.cachedGameCount() == 1 }
            registry.evict(resumeId)

            val bobUpstream = FakeUpstreamSession(listOf(SessionStatus(SessionStateCode.CONNECTED)))
            val bob = registry.createSession(bobUpstream, Credentials("bob", null))

            assertInstanceOf(GameStateUnavailable::class.java, bob.gameState(GetGameState(gameId = GAME)))
        }

    @Test
    fun `two sessions in the same game hold their own views, never each other's`() =
        registryScenario { registry ->
            // **The falsifying test for "per session".** Both sessions are in the *same* game, so a
            // bridge-wide cache keyed by game id would answer both from one entry — and because a
            // `GameView` carries the viewer's own hand, that means handing one player the other's cards.
            // A single-client test cannot see this at all.
            val aliceUpstream = FakeUpstreamSession(listOf(SessionStatus(SessionStateCode.CONNECTED)))
            val bobUpstream = FakeUpstreamSession(listOf(SessionStatus(SessionStateCode.CONNECTED)))
            val alice = registry.createSession(aliceUpstream, Credentials("alice", null))
            val bob = registry.createSession(bobUpstream, Credentials("bob", null))

            aliceUpstream.emit(
                GameStateUpdated(gameId = GAME, state = view(turn = 3, viewer = "p-1", hand = listOf("a-1", "a-2"))),
            )
            bobUpstream.emit(
                GameStateUpdated(gameId = GAME, state = view(turn = 3, viewer = "p-2", hand = listOf("b-1"))),
            )
            awaitCache(alice, "alice's snapshot") { it.cachedGameCount() == 1 }
            awaitCache(bob, "bob's snapshot") { it.cachedGameCount() == 1 }

            val forAlice = snapshotOf(alice, GAME).state
            val forBob = snapshotOf(bob, GAME).state

            assertEquals("p-1", forAlice.viewerPlayerId)
            assertEquals("p-2", forBob.viewerPlayerId)
            assertEquals(listOf("a-1", "a-2"), forAlice.hand.map { it.id })
            assertEquals(listOf("b-1"), forBob.hand.map { it.id }, "bob must never be served alice's hand")
        }

    private companion object {
        /** One game, two seats — the shape the per-session guarantee is about. */
        const val GAME = "g-1"

        val PUMP_TIMEOUT = 10.seconds
        const val POLL_MS = 10L
    }
}
