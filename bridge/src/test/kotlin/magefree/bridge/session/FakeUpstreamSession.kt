package magefree.bridge.session

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import magefree.protocol.ServerInfo
import magefree.protocol.ServerMessage
import java.util.concurrent.atomic.AtomicInteger

/**
 * A scripted [UpstreamSession] for hermetic tests — no XMage server needed. [connect] emits the
 * given [script] in order, then relays any messages later pushed via [emit], staying open until the
 * collection is cancelled (socket close / [disconnect]). This lets a test drive **every**
 * `SessionStateCode` path (and the mapped-push/`GetServerInfo` paths) through the real WebSocket +
 * [SessionCoordinator] plumbing, and — for story 0023 — prove a parked session survives an app-socket
 * drop and continues on the resumed socket **with no second `connect`**.
 *
 * The scripted messages are the *upstream* view; the coordinator stamps the login's `requestId` onto
 * the first status, so scripts should carry a null `requestId`. [serverInfo] backs `GetServerInfo`.
 */
public class FakeUpstreamSession(
    private val script: List<ServerMessage>,
    private val scriptedServerInfo: ServerInfo? = null,
    private val scriptedSessionId: String? = "fake-session-id",
) : UpstreamSession {
    private val disconnectSignal = CompletableDeferred<Unit>()
    private val extra = Channel<ServerMessage>(capacity = Channel.UNLIMITED)

    /** How many times [connect] has been collected — a resume must reuse the session, so this stays 1. */
    public val connectCount: AtomicInteger = AtomicInteger(0)

    /** How many times the parked keepalive [ping] has been invoked. */
    public val pingCount: AtomicInteger = AtomicInteger(0)

    /** The credentials the coordinator connected with, captured for assertions. */
    @Volatile
    public var lastCredentials: Credentials? = null
        private set

    /** True once [disconnect] has been invoked (e.g. by socket close, `Logout`, or eviction). */
    public val disconnectCalled: Boolean
        get() = disconnectSignal.isCompleted

    override fun connect(credentials: Credentials): Flow<ServerMessage> =
        flow {
            connectCount.incrementAndGet()
            lastCredentials = credentials
            script.forEach { emit(it) }
            // Stay hot like a real connected session: relay any later-pushed messages until cancelled.
            for (message in extra) emit(message)
        }

    /** Pushes an additional upstream message onto a live [connect] stream (post-script), e.g. after resume. */
    public suspend fun emit(message: ServerMessage) {
        extra.send(message)
    }

    override suspend fun serverInfo(): ServerInfo? = scriptedServerInfo

    override suspend fun ping(): Boolean {
        pingCount.incrementAndGet()
        return !disconnectSignal.isCompleted
    }

    override suspend fun sessionId(): String? = scriptedSessionId

    override suspend fun disconnect() {
        disconnectSignal.complete(Unit)
    }

    /** Suspends until [disconnect] is called, for asserting teardown after a socket close/eviction. */
    public suspend fun awaitDisconnect(): Unit = disconnectSignal.await()
}
