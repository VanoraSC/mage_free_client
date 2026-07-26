package magefree.bridge.xmage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mage.remote.Connection
import mage.remote.SessionImpl
import java.util.UUID

/**
 * A thin wrapper around XMage's [SessionImpl] that exposes the connect / query-main-room /
 * disconnect surface the bridge needs, with the blocking JBoss-remoting calls confined to
 * [Dispatchers.IO] (never the caller's event thread; see AGENTS.md — no blocking I/O on the main
 * thread).
 *
 * By reusing `SessionImpl` the bridge inherits XMage's connect/auth/keepalive/reconnect and
 * Java-serialization handling correct-by-construction. Story 0005 will own one [XMageSession] per
 * connected app client behind the WebSocket; here it is a plain library surface.
 *
 * @property client the callback sink `SessionImpl` drives; exposed so callers can observe the
 *   connection lifecycle it records.
 */
public class XMageSession(
    public val client: BridgeMageClient = BridgeMageClient(),
) {
    private val session: SessionImpl = SessionImpl(client)

    /** True while the underlying `SessionImpl` reports an active connection. */
    public val isConnected: Boolean
        get() = session.isConnected

    /**
     * Connects and authenticates against the server described by [connection], running the blocking
     * `connectStart` on [Dispatchers.IO]. `connectStart` performs the mandatory version handshake
     * and, for non-admin users, sets the user data.
     *
     * @return `true` if the connection was established, `false` otherwise.
     */
    public suspend fun connect(connection: Connection): Boolean =
        withContext(Dispatchers.IO) {
            session.connectStart(connection)
        }

    /**
     * The lobby's main room id, fetched from the server via `SessionImpl.getMainRoomId()`. Non-null
     * once connected; may be `null` if called while disconnected.
     */
    public fun mainRoomId(): UUID? = session.mainRoomId

    /**
     * Disconnects cleanly via `connectStop(false, false)` — no reconnect prompt, do not keep the
     * server-side session alive. Runs on [Dispatchers.IO].
     */
    public suspend fun disconnect() {
        withContext(Dispatchers.IO) {
            session.connectStop(false, false)
        }
    }
}
