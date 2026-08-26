package magefree.network.mapper

import magefree.model.ConnectionError
import magefree.model.ConnectionState
import magefree.model.ServerTarget
import magefree.model.Session
import magefree.model.SessionEvent
import magefree.protocol.ProtocolError
import magefree.protocol.ProtocolErrorCode
import magefree.protocol.ProtocolVersion
import magefree.protocol.ResumeRejected
import magefree.protocol.ServerHello
import magefree.protocol.ServerMessage
import magefree.protocol.SessionResumable
import magefree.protocol.SessionStateCode
import magefree.protocol.SessionStatus
import magefree.protocol.UnknownServerMessage

/**
 * The **single coupling point** between the `:protocol` wire contract and the `:core:model` domain.
 * Every `:protocol` import in `:core:network` lives here (plus the Ktor/fake clients that feed this
 * mapper); nothing above `:core:network` references a wire type.
 *
 * A version mismatch — whether detected locally in the handshake, reported as a
 * [ProtocolErrorCode.PROTOCOL_VERSION_UNSUPPORTED], or relayed as a
 * [SessionStateCode.VERSION_UNSUPPORTED] — always maps to [SessionEvent.VersionUnsupported], keeping
 * it a first-class outcome.
 */
object SessionMapper {
    /** Map a bridge session state code to the app's [ConnectionState] vocabulary. */
    fun SessionStateCode.toConnectionState(): ConnectionState =
        when (this) {
            SessionStateCode.CONNECTING -> ConnectionState.Connecting
            SessionStateCode.CONNECTED -> ConnectionState.Connected
            SessionStateCode.AUTH_FAILED -> ConnectionState.AuthFailed
            SessionStateCode.VERSION_UNSUPPORTED -> ConnectionState.Unsupported
            SessionStateCode.DISCONNECTED -> ConnectionState.Disconnected
            SessionStateCode.RECONNECTING -> ConnectionState.Reconnecting
        }

    /**
     * Validate a [ServerHello] against the protocol major this app speaks. Returns a
     * [SessionEvent.VersionUnsupported] when the majors disagree (the handshake is terminal), or
     * `null` when the peer is compatible and the session may proceed.
     */
    fun handshakeResult(serverHello: ServerHello): SessionEvent.VersionUnsupported? =
        if (serverHello.protocolMajor != ProtocolVersion.MAJOR) {
            SessionEvent.VersionUnsupported(
                detail =
                    "protocol major mismatch: app=${ProtocolVersion.MAJOR} " +
                        "bridge=${serverHello.protocolMajor} (${serverHello.bridgeVersion})",
            )
        } else {
            null
        }

    /**
     * The bridge-issued resume handle carried by a [SessionResumable], or `null` for any
     * other message. The relay captures this so a later reconnect can `Resume` the parked session; it is
     * not a lifecycle transition, so [toSessionEvent] deliberately ignores [SessionResumable].
     */
    fun resumeIdOrNull(message: ServerMessage): String? = (message as? SessionResumable)?.resumeId

    /**
     * `true` when [message] is the bridge rejecting a `Resume` ([ResumeRejected]) — the parked session
     * is unknown/expired/inconsistent and the app must fall back to a fresh `Login`. Like
     * [SessionResumable] this is flow-control, not a lifecycle event, so [toSessionEvent] ignores it.
     */
    fun isResumeRejected(message: ServerMessage): Boolean = message is ResumeRejected

    /**
     * `true` when [message] is the [UnknownServerMessage] sentinel — a `type` this build does not know,
     * decoded by `ProtocolJson`'s polymorphic default. The app **ignores** it (no
     * lifecycle event, and — crucially — no reconnect): honouring [ProtocolVersion]'s minor-tolerance
     * promise for additive new server messages. [toSessionEvent] also maps it to `null`.
     */
    fun isUnknown(message: ServerMessage): Boolean = message is UnknownServerMessage

    /**
     * Map a server→app [ServerMessage] to a domain [SessionEvent], or `null` for messages that do
     * not affect the session lifecycle (handshake echoes, pings, chat, server-info, and the resume
     * flow-control frames [SessionResumable]/[ResumeRejected] handled out-of-band — the app logs
     * and ignores those here). [target]/[credentials-derived username] and the negotiated
     * [bridgeVersion] furnish the [Session] handle on a successful connect.
     */
    fun toSessionEvent(
        message: ServerMessage,
        target: ServerTarget,
        username: String,
        bridgeVersion: String?,
    ): SessionEvent? =
        when (message) {
            is SessionStatus -> message.toSessionEvent(target, username, bridgeVersion)
            is ProtocolError -> message.toSessionEvent()
            else -> null
        }

    private fun SessionStatus.toSessionEvent(
        target: ServerTarget,
        username: String,
        bridgeVersion: String?,
    ): SessionEvent =
        when (state) {
            SessionStateCode.CONNECTING -> SessionEvent.Connecting
            SessionStateCode.CONNECTED ->
                SessionEvent.Connected(Session(target, username, bridgeVersion))
            SessionStateCode.AUTH_FAILED -> SessionEvent.AuthFailed(message)
            SessionStateCode.VERSION_UNSUPPORTED -> SessionEvent.VersionUnsupported(message)
            SessionStateCode.DISCONNECTED -> SessionEvent.Disconnected(message)
            SessionStateCode.RECONNECTING -> SessionEvent.Reconnecting
        }

    private fun ProtocolError.toSessionEvent(): SessionEvent =
        when (code) {
            ProtocolErrorCode.PROTOCOL_VERSION_UNSUPPORTED ->
                SessionEvent.VersionUnsupported(message)
            else -> SessionEvent.Error(ConnectionError.Protocol(code.name, message))
        }
}
