package magefree.model

/**
 * A bridge endpoint the app can connect to.
 *
 * The app only ever names the **bridge** (host/port); the bridge itself pins the upstream XMage
 * server, so no XMage host/port appears here. [secure] selects `wss` over `ws`; [displayName] is an
 * optional human label for a saved server (persistence arrives in story 0017).
 */
data class ServerTarget(
    val host: String,
    val port: Int,
    val secure: Boolean = false,
    val displayName: String? = null,
)
