package magefree.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A message sent **from the app to the bridge**.
 *
 * A sealed, `@Serializable` hierarchy discriminated by a `type` field (see [ProtocolJson]). New
 * message types are added as additional `@Serializable` subtypes with a unique [SerialName]; this
 * is an additive, backward-compatible change within a protocol major (see [ProtocolVersion]).
 */
@Serializable
public sealed interface ClientMessage

/**
 * A message sent **from the bridge to the app**.
 *
 * The server-side counterpart of [ClientMessage]; same rules apply.
 */
@Serializable
public sealed interface ServerMessage

/**
 * First frame of the [ProtocolVersion.PATH_SEGMENT] handshake: the app announces the protocol
 * version it speaks. The bridge validates [protocolMajor] against [ProtocolVersion.MAJOR].
 */
@Serializable
@SerialName("client_hello")
public data class ClientHello(
    val protocolMajor: Int,
    val protocolMinor: Int,
) : ClientMessage

/**
 * The bridge's reply to a compatible [ClientHello]: it echoes the protocol version it will speak
 * and reports its own [bridgeVersion] build string for logging/adaptation.
 */
@Serializable
@SerialName("server_hello")
public data class ServerHello(
    val protocolMajor: Int,
    val protocolMinor: Int,
    val bridgeVersion: String,
) : ServerMessage

/**
 * App→bridge liveness probe. The optional [nonce] is echoed back in the matching [Pong] so the app
 * can correlate a specific probe; [requestId] is the generic correlation id carried on the envelope.
 */
@Serializable
@SerialName("ping")
public data class Ping(
    val nonce: String? = null,
    val requestId: String? = null,
) : ClientMessage

/**
 * Bridge→app reply to a [Ping], echoing its [nonce] and [requestId].
 */
@Serializable
@SerialName("pong")
public data class Pong(
    val nonce: String? = null,
    val requestId: String? = null,
) : ServerMessage
