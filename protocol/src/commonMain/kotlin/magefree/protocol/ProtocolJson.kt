package magefree.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic

/**
 * The single, canonical [Json] configuration for the wire protocol. **Both** the bridge and the app
 * must serialize/deserialize envelope messages with this exact instance so the framing stays
 * identical on both ends.
 *
 * - `classDiscriminator = "type"` — polymorphic [ClientMessage]/[ServerMessage] frames carry their
 *   subtype in a `type` field (matching each message's `@SerialName`).
 * - `ignoreUnknownKeys = true` — the basis of additive forward-compatibility: an older peer silently
 *   ignores fields added by a newer one (see [ProtocolVersion]).
 * - `encodeDefaults = false` — optional fields left at their default (e.g. a null `requestId`) are
 *   omitted from the wire form, keeping frames compact.
 * - polymorphic default deserializers — the other half of additive forward-compatibility (
 *   F1): `ignoreUnknownKeys` tolerates an unknown *field*, but an unknown sealed-subtype **`type`**
 *   would still throw. A `polymorphicDefaultDeserializer` on both [ClientMessage] and [ServerMessage]
 *   decodes any unrecognised `type` to a deserialize-only [UnknownClientMessage]/[UnknownServerMessage]
 *   sentinel (carrying the raw discriminator), which each consumer logs and ignores — honouring
 *   [ProtocolVersion]'s promise that "both sides must tolerate an unknown message `type` gracefully".
 *   The same default is registered for [GamePrompt]: the in-game prompt set is nested
 *   *inside* a [GamePrompted] frame, so an unknown prompt subtype would otherwise throw even though the
 *   envelope's own `type` is known — an app that cannot decode the prompt must still receive the state.
 */
public object ProtocolJson {
    public val json: Json =
        Json {
            classDiscriminator = "type"
            ignoreUnknownKeys = true
            encodeDefaults = false
            serializersModule =
                SerializersModule {
                    polymorphic(ClientMessage::class) {
                        defaultDeserializer { type -> UnknownClientMessage.deserializerFor(type) }
                    }
                    polymorphic(ServerMessage::class) {
                        defaultDeserializer { type -> UnknownServerMessage.deserializerFor(type) }
                    }
                    polymorphic(GamePrompt::class) {
                        defaultDeserializer { type -> UnknownGamePrompt.deserializerFor(type) }
                    }
                }
        }
}
