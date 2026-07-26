package magefree.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Session messages layered onto the 0004 envelope (story 0005). These extend the sealed
 * ClientMessage/ServerMessage hierarchies — they do not fork them — so the additive,
 * forward-compatible versioning rules of ProtocolVersion continue to hold: an older peer that does
 * not know a `type` tolerates it via `ignoreUnknownKeys` (see ProtocolJson).
 *
 * The app authenticates by sending a Login; the bridge opens a session to its pinned upstream XMage
 * server (the app never names a host/port) and relays live SessionStatus transitions back.
 */

/**
 * App→bridge: authenticate against the bridge's pinned upstream XMage server. The [password] is
 * optional (the local reference server runs with authentication disabled). [requestId] is echoed
 * onto the first [SessionStatus] the bridge emits for this login so the app can correlate the reply.
 */
@Serializable
@SerialName("login")
public data class Login(
    val username: String,
    val password: String? = null,
    val requestId: String? = null,
) : ClientMessage

/**
 * App→bridge: tear down the active upstream session (if any). [requestId] is the generic envelope
 * correlation id.
 */
@Serializable
@SerialName("logout")
public data class Logout(
    val requestId: String? = null,
) : ClientMessage

/**
 * Bridge→app: a live transition of the upstream XMage session's state. [message] carries an optional
 * human-readable detail (e.g. `"server=1.4.61 bridge=1.4.60"` for [SessionStateCode.VERSION_UNSUPPORTED]).
 * [requestId] is populated on the first status emitted in response to a [Login] and left null afterwards.
 */
@Serializable
@SerialName("session_status")
public data class SessionStatus(
    val state: SessionStateCode,
    val message: String? = null,
    val requestId: String? = null,
) : ServerMessage

/**
 * The closed set of upstream-session states the bridge surfaces to the app. New states may be
 * **added** within a protocol major (additive-only); the app must treat an unrecognised state
 * defensively (log and continue).
 */
public enum class SessionStateCode {
    /** The bridge has begun opening the upstream connection but is not yet authenticated. */
    CONNECTING,

    /** Authenticated and connected to the upstream server. */
    CONNECTED,

    /** The server rejected the credentials (upstream login returned failure). */
    AUTH_FAILED,

    /** Client/server version mismatch; [SessionStatus.message] carries `"server=<v> bridge=<v>"`. */
    VERSION_UNSUPPORTED,

    /** The upstream connection dropped and is not being restored (terminal for this session). */
    DISCONNECTED,

    /** The upstream connection dropped and a reconnect is in progress. */
    RECONNECTING,
}
