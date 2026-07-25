package magefree.protocol

import kotlinx.serialization.json.Json

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
 */
public object ProtocolJson {
    public val json: Json =
        Json {
            classDiscriminator = "type"
            ignoreUnknownKeys = true
            encodeDefaults = false
        }
}
