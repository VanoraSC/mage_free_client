# `:protocol` — Bridge↔app wire contract (v1)

The **single source of truth** for the app-facing wire protocol. Both `:bridge` (JVM) and the
Android app build against this one pure-Kotlin/JVM module, so the serializable message types are
never duplicated per side. Transport is a WebSocket carrying a versioned JSON message envelope.

This module is **transport + framing only**: the handshake, app-level liveness (ping/pong), and a
protocol-error model. No XMage/session wiring and no domain/game messages live here.

## Message envelope

All messages are `@Serializable` and belong to one of two sealed hierarchies, discriminated by a
`type` field:

- `ClientMessage` (app → bridge): `ClientHello`, `Ping`.
- `ServerMessage` (bridge → app): `ServerHello`, `Pong`, `ProtocolError`.

`ProtocolJson.json` is the one canonical `Json` instance both sides use:
`classDiscriminator = "type"`, `ignoreUnknownKeys = true`, `encodeDefaults = false`.

Every message may carry an optional `requestId` correlation id, present in the envelope now so later
request/response call messages can correlate **without** a protocol change.

## Versioning & compatibility rules

The protocol is versioned on two axes (`ProtocolVersion`):

- **Major (`MAJOR`, in the URL path `PATH_SEGMENT` — e.g. `/v1/session`).** A **breaking** change
  (removing/renaming a field, changing a meaning, removing a message type) requires a **new major**
  and a new path. A client/bridge disagreement on the major is fatal: the handshake answers
  `PROTOCOL_VERSION_UNSUPPORTED` and closes.
- **Minor (`MINOR`) — additive only within a major.** Allowed additive changes: new **optional**
  fields (with defaults) and new message `type`s. Both sides set `ignoreUnknownKeys = true`, so an
  older peer ignores unknown fields, and both sides must tolerate an unknown message `type`
  gracefully — the bridge answers `UNKNOWN_MESSAGE_TYPE` (non-terminal); the app logs and ignores.
  Minor differences are therefore compatible in both directions.

The handshake exchanges both numbers plus `bridgeVersion`, letting each side log the peer's exact
build and adapt where useful.

## Handshake (served by `:bridge` at `/v1/session`)

1. The app sends `ClientHello(protocolMajor, protocolMinor)` as the first frame.
2. If the frame is not a deserializable `ClientHello` → `ProtocolError(MALFORMED_MESSAGE)`, close.
3. If `protocolMajor != ProtocolVersion.MAJOR` → `ProtocolError(PROTOCOL_VERSION_UNSUPPORTED)`, close.
4. Otherwise the bridge replies `ServerHello(MAJOR, MINOR, bridgeVersion)`. Minor differences are
   tolerated (additive compat).

After the handshake, `Ping` → `Pong` (echoing `nonce`/`requestId`); any unknown/unhandled message
→ `ProtocolError(UNKNOWN_MESSAGE_TYPE)` **without** closing.

## Extending the contract

Downstream work **extends** the sealed `ClientMessage`/`ServerMessage` hierarchies with
new `@Serializable` subtypes — they do not fork or duplicate this module. Keep changes additive
within a major; a breaking change is a deliberate, project-wide new-major decision.
