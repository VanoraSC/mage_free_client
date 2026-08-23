package magefree.model

/**
 * A handle to an established session: which [target] we authenticated against, as which [username],
 * and the bridge build string ([bridgeVersion], from the handshake) for logging/diagnostics.
 *
 * Produced only once a session reaches [ConnectionState.Connected]; it carries no wire types.
 */
data class Session(
    val target: ServerTarget,
    val username: String,
    val bridgeVersion: String? = null,
)
