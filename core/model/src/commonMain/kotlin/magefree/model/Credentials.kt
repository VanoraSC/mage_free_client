package magefree.model

/**
 * The sign-in credentials the app presents to the bridge (which relays them to the pinned upstream
 * server). [password] is optional — the local reference server runs with authentication disabled.
 */
data class Credentials(
    val username: String,
    val password: String? = null,
)
