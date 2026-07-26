package magefree.bridge.session

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import magefree.protocol.ServerInfo
import magefree.protocol.ServerMessage

/**
 * A scripted [UpstreamSession] for hermetic tests — no XMage server needed. [connect] emits the
 * given [script] in order, then stays open (suspends) so that cancelling the collection (socket
 * close) or a [disconnect] call models a live session ending. This lets a test drive **every**
 * `SessionStateCode` path (and the mapped-push/`GetServerInfo` paths) through the real WebSocket +
 * [SessionCoordinator] plumbing.
 *
 * The scripted messages are the *upstream* view; the coordinator stamps the login's `requestId` onto
 * the first status, so scripts should carry a null `requestId`. [serverInfo] backs `GetServerInfo`.
 */
public class FakeUpstreamSession(
    private val script: List<ServerMessage>,
    private val scriptedServerInfo: ServerInfo? = null,
) : UpstreamSession {
    private val disconnectSignal = CompletableDeferred<Unit>()

    /** The credentials the coordinator connected with, captured for assertions. */
    @Volatile
    public var lastCredentials: Credentials? = null
        private set

    /** True once [disconnect] has been invoked (e.g. by socket close or `Logout`). */
    public val disconnectCalled: Boolean
        get() = disconnectSignal.isCompleted

    override fun connect(credentials: Credentials): Flow<ServerMessage> =
        flow {
            lastCredentials = credentials
            script.forEach { emit(it) }
            // Stay hot like a real connected session until the collector is cancelled.
            awaitCancellation()
        }

    override suspend fun serverInfo(): ServerInfo? = scriptedServerInfo

    override suspend fun disconnect() {
        disconnectSignal.complete(Unit)
    }

    /** Suspends until [disconnect] is called, for asserting teardown after a socket close. */
    public suspend fun awaitDisconnect(): Unit = disconnectSignal.await()
}
