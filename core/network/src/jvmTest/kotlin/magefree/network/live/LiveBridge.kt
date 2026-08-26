package magefree.network.live

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import magefree.model.Credentials
import magefree.model.Session
import magefree.model.SessionEvent
import magefree.network.LobbyClient
import magefree.network.LobbyClientImpl
import magefree.network.game.GameClient
import magefree.network.game.GameClients
import magefree.network.ktor.KtorBridgeClient
import magefree.network.reconnect.AlwaysOnlineConnectivityObserver
import magefree.network.reconnect.ConnectivityObserver
import magefree.network.reconnect.FakeConnectivityObserver
import magefree.network.table.TableClient
import magefree.network.table.TableClients
import org.junit.Assume
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * One live app→bridge session for the  integration tests: a **real** [KtorBridgeClient]
 * (never a fake) connected to the running `bridge` compose service, plus the two production clients that
 * ride the same socket — [LobbyClientImpl] and the [TableClients] table client.
 *
 * This is deliberately thin. Everything it touches is production code assembled exactly the way
 * `NetworkModule` assembles it; the harness only owns the collection of the cold `connect` flow (which
 * is what keeps the session alive), the `Connected` hand-off, and teardown.
 *
 * **Failure reporting.** A connect that never reaches `Connected` fails with every [SessionEvent] the
 * flow emitted, because the interesting diagnostics (an `AuthFailed` message, a
 * `VersionUnsupported` detail, a transport error) live in those events rather than in a stack trace.
 */
internal class LiveBridge(
    private val scope: CoroutineScope,
    private val target: BridgeTarget,
    /**
     * The reachability source the production reconnect loop watches. Defaulting to
     * "always online" keeps every existing test unchanged; the reconnect proof passes a
     * [magefree.network.reconnect.FakeConnectivityObserver] so it can take the **radio** away rather
     * than the collector — see [dropRadio].
     */
    private val connectivity: ConnectivityObserver = AlwaysOnlineConnectivityObserver,
) {
    /** The production client under test. Its `connectionState` also drives the table client's re-sync. */
    val client: KtorBridgeClient = KtorBridgeClient(connectivity = connectivity)

    /** Every session event the connect flow emitted, in order — the diagnostic record for a failure. */
    val events: MutableList<SessionEvent> = CopyOnWriteArrayList()

    /** The production lobby client over this socket. */
    val lobby: LobbyClient by lazy { LobbyClientImpl(client) }

    /** The production table client over this socket. */
    val tables: TableClient by lazy { TableClients.overBridge(client, client.connectionState) }

    /**
     * The production game client over this same socket — assembled through the public,
     * `:protocol`-free [GameClients] factory exactly as `NetworkModule` does, so the live game test
     * drives the real client rather than a double.
     */
    val games: GameClient by lazy { GameClients.overBridge(client, client.connectionState) }

    private var collector: Job? = null

    /**
     * Opens a session as [username] and suspends until the client reports [SessionEvent.Connected] —
     * i.e. the `ClientHello`/`ServerHello` handshake passed the version check and the bridge relayed a
     * `CONNECTED` status for the `Login` it forwarded upstream.
     */
    suspend fun connect(username: String): Session {
        val connected = CompletableDeferred<Session>()
        collector =
            scope.launch {
                client.connect(target.toServerTarget(), Credentials(username)).collect { event ->
                    events += event
                    when (event) {
                        is SessionEvent.Connected -> connected.complete(event.session)
                        is SessionEvent.AuthFailed ->
                            connected.completeExceptionally(AssertionError("login rejected: ${event.message}"))
                        is SessionEvent.VersionUnsupported ->
                            connected.completeExceptionally(AssertionError("version rejected: ${event.detail}"))
                        else -> Unit
                    }
                }
            }
        return try {
            withTimeout(CONNECT_TIMEOUT_MS) { connected.await() }
        } catch (timeout: TimeoutCancellationException) {
            throw AssertionError(
                "no Connected session from ${target.host}:${target.port} within ${CONNECT_TIMEOUT_MS}ms; " +
                    "events were $events",
                timeout,
            )
        }
    }

    /** Tears the session down: a clean `disconnect` (terminal, so no reconnect) then stop collecting. */
    suspend fun close() {
        runCatching { client.disconnect() }
        collector?.let { runCatching { it.cancelAndJoin() } }
        collector = null
    }

    /**
     * Signs out **deliberately**: the production `signOut` puts a `Logout` on the wire
     * before closing, so the bridge disconnects the upstream XMage session now instead of parking it for
     * the resume TTL. Then stops collecting, as [close] does.
     */
    suspend fun signOut() {
        client.signOut()
        collector?.let { runCatching { it.cancelAndJoin() } }
        collector = null
    }

    /**
     * Ends the session the way a **lost connection or a backgrounded app** does: cancel the collection of
     * the cold `connect` flow, which closes the socket with no `Logout` on it. Nothing here calls
     * `disconnect`/`signOut` — this is the shape the bridge must read as "park it, they may be back"
     *.
     *
     * Note what this does **not** do: cancelling the flow discards the `ResumeHandle` with it, so a later
     * `connect` is a fresh `Login`, not a `Resume`. For the reconnect-and-resume shape — the one an app
     * that stays alive across a network hand-off actually performs — use [dropRadio]/[restoreRadio].
     */
    suspend fun dropWithoutSigningOut() {
        collector?.let { runCatching { it.cancelAndJoin() } }
        collector = null
    }

    /**
     * Takes the **radio** away without touching the session flow (the reconnect proof).
     *
     * This is the production drop, exactly: `ReconnectingSession` watches [connectivity] and ends a
     * running attempt the moment the network goes, so the socket closes with no
     * `Logout` on it — the bridge parks the session — while the reconnect loop stays alive holding its
     * `ResumeHandle`. It is literally the shape `KtorBridgeClient`'s KDoc records from the on-device
     * smoke: Android tearing down a lingering network after a WIFI/CELLULAR hand-off.
     *
     * [dropWithoutSigningOut] cannot stand in for it — cancelling the collector throws the resume handle
     * away, so what follows is a fresh `Login` and a **new** bridge session.
     *
     * Requires a [magefree.network.reconnect.FakeConnectivityObserver] to have been passed in; fails
     * loudly rather than silently doing nothing if it was not.
     */
    fun dropRadio() {
        driveableConnectivity().set(false)
    }

    /** Brings the radio back: the loop's back-off is cut short and the next attempt sends `Resume`. */
    fun restoreRadio() {
        driveableConnectivity().set(true)
    }

    private fun driveableConnectivity(): FakeConnectivityObserver =
        connectivity as? FakeConnectivityObserver
            ?: error("LiveBridge was built with $connectivity; pass a FakeConnectivityObserver to drive the radio")

    companion object {
        /** How long a first connect + login may take (the upstream connect/auth handshake is not free). */
        const val CONNECT_TIMEOUT_MS: Long = 60_000L

        /**
         * How long to keep re-reading a lobby/table view before giving up. `GamesRoomImpl` rebuilds the
         * room's snapshot on a **two-second** timer, so a read straight after a change legitimately still
         * shows the previous one; this is the same eventual consistency `:bridge`'s `TableRelayIT` waits
         * out, not flakiness padding.
         */
        const val SETTLE_TIMEOUT_MS: Long = 30_000L

        /** How often to re-read while waiting for the server's snapshot to catch up. */
        const val POLL_INTERVAL_MS: Long = 500L

        /**
         * A username satisfying XMage's `[a-z0-9_]`, length 3..14 rule: `it_` + 8 hex = 11 chars. Unique
         * per run so repeated and parallel runs never collide on the long-lived reference server.
         */
        fun uniqueUsername(): String {
            val hex = UUID.randomUUID().toString().filter { it != '-' }
            return "it_" + hex.take(8)
        }

        /**
         * The live gate: the parsed [BridgeTarget], or a JUnit **assumption failure** (reported as a
         * *skipped* test) when [BridgeTarget.ENV_VAR] is unset — which is what keeps
         * `:core:network:check` hermetic and offline by default.
         */
        fun requireTarget(): BridgeTarget {
            val target = BridgeTarget.fromEnv()
            Assume.assumeTrue(
                "${BridgeTarget.ENV_VAR} is not set; skipping the live app<->bridge integration test",
                target != null,
            )
            return target!!
        }

        /**
         * Polls [read] until [condition] holds, returning that value, or fails after [SETTLE_TIMEOUT_MS]
         * naming [what] and the last value seen.
         */
        suspend fun <T> awaitValue(
            what: String,
            timeoutMs: Long = SETTLE_TIMEOUT_MS,
            read: suspend () -> T,
            condition: (T) -> Boolean,
        ): T {
            var last: T? = null
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val value = read()
                last = value
                if (condition(value)) return value
                delay(POLL_INTERVAL_MS)
            }
            throw AssertionError("timed out after ${timeoutMs}ms waiting for $what; last value was $last")
        }
    }
}
