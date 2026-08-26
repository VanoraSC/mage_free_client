package magefree.protocol

/**
 * The identity of the app-facing wire protocol.
 *
 * ## Versioning & compatibility rules
 *
 * The protocol has exactly one authoritative definition — this `:protocol` module — that both
 * the bridge and the app build against. Versioning follows a two-level scheme:
 *
 * - **Major ([MAJOR]) lives in the URL path** ([PATH_SEGMENT], e.g. `/v1/session`). A **breaking**
 *   change (removing or renaming a field, changing a type's meaning, removing a message type)
 *   requires a new major and therefore a new path. A client and bridge that disagree on the major
 *   cannot talk: the handshake rejects the mismatch with `PROTOCOL_VERSION_UNSUPPORTED`.
 * - **Within a major, changes are additive only** ([MINOR] increments): new **optional** fields
 *   (with defaults) and new message `type`s. Both sides configure their [kotlinx.serialization.json.Json]
 *   with `ignoreUnknownKeys = true`, so an older peer silently ignores fields it does not know, and
 *   both sides must tolerate an unknown message `type` gracefully — the bridge answers
 *   `UNKNOWN_MESSAGE_TYPE`; the app logs and ignores. Minor differences are therefore compatible in
 *   both directions.
 *
 * The handshake exchanges both numbers plus `bridgeVersion` so each side can log the peer's exact
 * build and, where useful, adapt behaviour to a known-newer or known-older minor.
 *
 * Downstream work **extend** the sealed [ClientMessage]/[ServerMessage] hierarchies with new
 * `@Serializable` subtypes; they never fork or duplicate the contract.
 */
public object ProtocolVersion {
    /** Breaking-change axis; carried in the URL path. Incremented only for incompatible changes. */
    public const val MAJOR: Int = 1

    /** Additive-change axis within a major. Incremented when optional fields / message types are added. */
    public const val MINOR: Int = 0

    /** The URL path segment encoding [MAJOR] (e.g. `v1` → `/v1/session`). */
    public const val PATH_SEGMENT: String = "v1"
}
