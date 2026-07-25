package magefree.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The closed set of protocol-level error conditions the bridge can report to the app.
 *
 * These describe faults in the **transport/framing envelope**, not domain/game errors (those arrive
 * in later epics as their own message types). New codes may be **added** within a protocol major;
 * because both sides deserialize with `ignoreUnknownKeys = true` an older app tolerates unknown
 * message fields, and it must treat an unrecognised code defensively (log and continue).
 */
public enum class ProtocolErrorCode {
    /** The client's [ClientHello.protocolMajor] does not match [ProtocolVersion.MAJOR]. Terminal. */
    PROTOCOL_VERSION_UNSUPPORTED,

    /** A frame could not be deserialized into a known message (bad JSON / missing envelope). */
    MALFORMED_MESSAGE,

    /** A well-formed message carried a `type` the bridge does not handle. Non-terminal. */
    UNKNOWN_MESSAGE_TYPE,

    /** An unexpected bridge-side failure while processing a message. */
    INTERNAL,
}

/**
 * A protocol-level error surfaced to the app. [code] is machine-readable, [message] is a
 * human-readable diagnostic, and [requestId] correlates the error with the offending request when
 * one is known.
 */
@Serializable
@SerialName("protocol_error")
public data class ProtocolError(
    val code: ProtocolErrorCode,
    val message: String,
    val requestId: String? = null,
) : ServerMessage
