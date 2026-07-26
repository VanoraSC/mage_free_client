package magefree.bridge.xmage

import mage.players.net.UserData
import mage.remote.Connection
import java.util.UUID

/**
 * Builds the [Connection] POJO that `SessionImpl.connectStart(...)` consumes.
 *
 * The connection carries host/port/credentials plus two things the server-side connect path
 * requires for a non-admin login: a stable per-connection `userIdStr` (a random UUID) and a
 * non-null [UserData]. `connectStart` calls `connectSetUserData(...)` for non-admin users, so a
 * null there would be rejected — we supply XMage's own default profile. `adminPassword` is left
 * null (non-admin login).
 */
public object XMageConnection {
    /**
     * Assembles a [Connection] for [host]:[port] authenticating as [username]/[password].
     *
     * @return a fully-populated [Connection] ready for `connectStart`.
     */
    public fun build(
        host: String,
        port: Int,
        username: String,
        password: String,
    ): Connection =
        Connection().apply {
            setHost(host)
            setPort(port)
            setUsername(username)
            setPassword(password)
            // Stable per-connection identity the server associates with this login.
            setUserIdStr(UUID.randomUUID().toString())
            // Non-null profile; connectStart's connectSetUserData rejects a null for non-admin users.
            setUserData(UserData.getDefaultUserDataView())
            // No proxy. The default Connection() leaves proxyType null, and connectStart calls
            // getProxyType().ordinal() during setup -> NPE; the desktop client sets this too.
            setProxyType(Connection.ProxyType.NONE)
        }
}
