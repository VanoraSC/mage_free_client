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
 * Bridge→app: the **resume handle** for the session just established. Emitted right after the first
 * [SessionStatus]`(`[SessionStateCode.CONNECTED]`)` (and again as the positive ack of a successful
 * [Resume]), it carries the bridge-issued [resumeId] the app presents on a fresh socket to re-attach
 * to this still-live upstream session (story 0023, Epic 5). The id is the **bridge's own** handle —
 * not the XMage server-side session id — so the app never sees upstream internals.
 *
 * Delivered as a dedicated server message (rather than mutating [SessionStatus]) so the 0005 status
 * frame stays byte-for-byte unchanged; an older app that does not know this `type` ignores it via
 * `ignoreUnknownKeys` and simply never resumes. [requestId] is null on the connect-time emission and
 * echoes the [Resume]'s id on a resume ack.
 */
@Serializable
@SerialName("session_resumable")
public data class SessionResumable(
    val resumeId: String,
    val requestId: String? = null,
) : ServerMessage

/**
 * App→bridge: re-attach a freshly-(re)connected socket to a parked upstream session instead of
 * logging in again (story 0023). Sent **after** the 0004 `ClientHello`/`ServerHello` handshake, in
 * place of a [Login]; [resumeId] is the handle the bridge previously delivered via [SessionResumable].
 *
 * On a hit the bridge re-binds its outbound stream to this socket and continues streaming the same
 * live session — **no** second upstream connect/login. On a miss (unknown/expired/inconsistent handle)
 * it replies [ResumeRejected] and the app falls back to [Login] (app-side logic is story 0024).
 * [requestId] is echoed onto the resume ack/rejection for correlation.
 */
@Serializable
@SerialName("resume")
public data class Resume(
    val resumeId: String,
    val requestId: String? = null,
) : ClientMessage

/**
 * Bridge→app: a [Resume] could not be honoured — the [resumeId] is unknown, its grace window (TTL)
 * expired, or the parked session is no longer healthy. [reason] is a human-readable detail; the app
 * should fall back to a fresh [Login] (story 0024). [requestId] echoes the rejected [Resume]'s id.
 */
@Serializable
@SerialName("resume_rejected")
public data class ResumeRejected(
    val reason: String,
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
