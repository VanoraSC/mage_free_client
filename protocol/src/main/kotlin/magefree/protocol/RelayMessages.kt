package magefree.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Callback-relay and request/response messages layered onto the 0004 envelope (story 0006). These
 * extend the sealed ClientMessage/ServerMessage hierarchies — they do not fork them — so the
 * additive, forward-compatible versioning rules of ProtocolVersion continue to hold.
 *
 * `ChatEvent` is the first mapped server→client push: the bridge decompresses a `ClientCallback`,
 * maps the `mage.view.*` payload to app schema in its `mapping` boundary, and relays the result here.
 * `GetServerInfo`/`ServerInfo` are the first client→server request/response pair, correlated by
 * `requestId`; later `sendPlayer*`-style calls follow the same shape.
 */

/**
 * Bridge→app: a chat message relayed from the upstream server. This is the sample mapped push proving
 * the callback-relay pipeline; the full chat feature/UX is a later epic.
 *
 * [text] is the message body, [username] the sender (nullable for system messages), [timestampEpochMs]
 * the server-assigned time in epoch milliseconds, and [kind] the classified message category. [requestId]
 * is unused for spontaneous pushes and left null; it exists for symmetry with correlated replies.
 */
@Serializable
@SerialName("chat_event")
public data class ChatEvent(
    val text: String,
    val username: String?,
    val timestampEpochMs: Long,
    val kind: ChatKind,
    val requestId: String? = null,
) : ServerMessage

/**
 * The classified category of a [ChatEvent], mapped from the upstream message type. New kinds may be
 * **added** within a protocol major (additive-only); the app must treat an unrecognised kind
 * defensively (log and continue).
 */
public enum class ChatKind {
    /** Public room/table chat. */
    TALK,

    /** An incoming private (whisper) message. */
    WHISPER_IN,

    /** An outgoing private (whisper) message. */
    WHISPER_OUT,

    /** Game-log line. */
    GAME,

    /** A system status message. */
    STATUS,

    /** A user/system informational message. */
    USER,
}

/**
 * App→bridge: request the pinned upstream server's info (version + main room id). The reply is a
 * [ServerInfo] carrying the same [requestId] for correlation.
 */
@Serializable
@SerialName("get_server_info")
public data class GetServerInfo(
    val requestId: String? = null,
) : ClientMessage

/**
 * Bridge→app: the reply to a [GetServerInfo]. [serverVersion] is the upstream server's version string,
 * [mainRoomId] the lobby's main room id (null if unavailable), and [requestId] echoes the request's id.
 */
@Serializable
@SerialName("server_info")
public data class ServerInfo(
    val serverVersion: String,
    val mainRoomId: String?,
    val requestId: String? = null,
) : ServerMessage
