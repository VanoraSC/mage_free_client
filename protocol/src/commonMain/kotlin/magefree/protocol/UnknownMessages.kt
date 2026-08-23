package magefree.protocol

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder

/*
 * Additive, **deserialize-only** sentinels for an unknown message `type` (story 0026, F1).
 *
 * `ProtocolVersion` promises that "both sides must tolerate an unknown message `type` gracefully".
 * `ignoreUnknownKeys` only covers unknown *fields*, not an unknown sealed-subtype discriminator, so
 * decoding an additive new `type` would otherwise throw `SerializationException`. `ProtocolJson`
 * registers a `polymorphicDefaultDeserializer` for both `ClientMessage` and `ServerMessage` that maps
 * any unrecognised `type` to the matching sentinel below (capturing the raw discriminator for logging)
 * instead of throwing.
 *
 * These sentinels are never emitted: their [KSerializer.serialize] throws, so an accidental
 * `encodeToString` of one fails loudly rather than putting a fabricated frame on the wire. Consumers
 * ignore them — the bridge `SessionCoordinator` logs + drops an [UnknownClientMessage] (no
 * `ProtocolError`, no close); the app `SessionMapper`/`SessionRelay` map an [UnknownServerMessage] to
 * `null` (log + ignore, no reconnect).
 */

/** App→bridge frame whose `type` this build does not recognise (see the file header). */
@Serializable(with = UnknownClientMessage.Serializer::class)
public data class UnknownClientMessage(
    /** The unrecognised discriminator value, when the JSON carried one; `null` otherwise. */
    val type: String?,
) : ClientMessage {
    internal object Serializer : KSerializer<UnknownClientMessage> {
        override val descriptor: SerialDescriptor =
            buildClassSerialDescriptor("magefree.protocol.UnknownClientMessage")

        override fun serialize(
            encoder: Encoder,
            value: UnknownClientMessage,
        ): Nothing = throw SerializationException(DESERIALIZE_ONLY)

        override fun deserialize(decoder: Decoder): UnknownClientMessage = deserializerFor(null).deserialize(decoder)
    }

    public companion object {
        /**
         * A [DeserializationStrategy] that consumes the whole unknown frame and yields an
         * [UnknownClientMessage] carrying [type]. Used by `ProtocolJson`'s polymorphic default.
         */
        internal fun deserializerFor(type: String?): DeserializationStrategy<UnknownClientMessage> =
            object : DeserializationStrategy<UnknownClientMessage> {
                override val descriptor: SerialDescriptor = Serializer.descriptor

                override fun deserialize(decoder: Decoder): UnknownClientMessage {
                    // Consume the entire polymorphic object (discriminator + any additive fields).
                    (decoder as JsonDecoder).decodeJsonElement()
                    return UnknownClientMessage(type)
                }
            }
    }
}

/** Bridge→app frame whose `type` this build does not recognise (see the file header). */
@Serializable(with = UnknownServerMessage.Serializer::class)
public data class UnknownServerMessage(
    /** The unrecognised discriminator value, when the JSON carried one; `null` otherwise. */
    val type: String?,
) : ServerMessage {
    internal object Serializer : KSerializer<UnknownServerMessage> {
        override val descriptor: SerialDescriptor =
            buildClassSerialDescriptor("magefree.protocol.UnknownServerMessage")

        override fun serialize(
            encoder: Encoder,
            value: UnknownServerMessage,
        ): Nothing = throw SerializationException(DESERIALIZE_ONLY)

        override fun deserialize(decoder: Decoder): UnknownServerMessage = deserializerFor(null).deserialize(decoder)
    }

    public companion object {
        /**
         * A [DeserializationStrategy] that consumes the whole unknown frame and yields an
         * [UnknownServerMessage] carrying [type]. Used by `ProtocolJson`'s polymorphic default.
         */
        internal fun deserializerFor(type: String?): DeserializationStrategy<UnknownServerMessage> =
            object : DeserializationStrategy<UnknownServerMessage> {
                override val descriptor: SerialDescriptor = Serializer.descriptor

                override fun deserialize(decoder: Decoder): UnknownServerMessage {
                    (decoder as JsonDecoder).decodeJsonElement()
                    return UnknownServerMessage(type)
                }
            }
    }
}

private const val DESERIALIZE_ONLY: String =
    "Unknown* sentinels are deserialize-only (story 0026 F1) and must never be encoded onto the wire."
