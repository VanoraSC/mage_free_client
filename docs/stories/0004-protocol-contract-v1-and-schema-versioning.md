# 0004 — Bridge↔app protocol contract v1 & schema versioning

- **Epic:** EPIC-01 — Bridge & Server Integration
- **Depends on:** 0001
- **Status:** ready

## 1. Objective

Define the **app-facing wire protocol**: a versioned JSON message envelope, a WebSocket
endpoint, a handshake, liveness, and an error model — plus the versioning/compatibility rules
the whole project builds on. Deliver it as a **shared `:protocol` module** (single source of
truth for the contract) and a **WebSocket endpoint skeleton** in `:bridge` that speaks it.
This is transport + framing only: **no XMage session wiring and no domain/game messages yet**
(those are 0005 and downstream epics).

## 2. Context & background

- The monorepo decision ([`../architecture.md`](../architecture.md), Decisions #2) calls for
  "a versioned contract **both** [bridge and app] build against." So the serializable message
  types live in one shared module, not duplicated per side. A plain **Kotlin/JVM**
  (`java-library`) module can be consumed by both `:bridge` (JVM) and, later, the Android app
  modules (Android can depend on pure-Kotlin/JVM libraries).
- This is **protocol axis #3** from the versioning model
  ([`../architecture.md` → Versioning & upstream updates](../architecture.md)): ours to keep
  **stable** so the app rarely moves. The XMage server/`mage-common` axes are handled by the
  session stories.
- Stack: Ktor `WebSockets` plugin with a kotlinx.serialization converter; JSON with a
  `type` class-discriminator and `ignoreUnknownKeys = true` (the basis of additive
  forward-compatibility). Stack defaults and version-catalog rule are in
  [`AGENTS.md`](../../AGENTS.md).
- 0001 already added `:bridge` (Ktor + kotlinx.serialization) and the version catalog.

## 3. Scope

**In scope**
- A new `:protocol` shared Kotlin/JVM module with the `@Serializable` envelope: handshake,
  ping/pong, and protocol errors, plus a configured `Json` instance and version constants.
- The documented **versioning & compatibility rules** (major in the URL path; additive-only
  within a major).
- A `:bridge` WebSocket endpoint (`/v1/session`) implementing the handshake, app-level
  ping/pong, and error/close behavior, using `:protocol`.
- Hermetic tests: serialization round-trips + a Ktor `testApplication` WebSocket flow.

**Out of scope**
- Any `SessionImpl`/XMage wiring, login, or per-user session lifecycle (**0005** — it adds the
  session-state and upstream-version-mismatch messages on top of this envelope).
- Any domain/game/lobby/deck messages (their epics).
- Authentication and authorization (0005+).
- Protobuf (JSON only for now; the envelope is designed so a swap stays possible).

## 4. Design & approach

```
protocol/                                   # new shared Kotlin/JVM module
├── build.gradle.kts                        # kotlin("jvm") + kotlinx-serialization plugin/json
└── src/
    ├── main/kotlin/magefree/protocol/
    │   ├── ProtocolVersion.kt              # MAJOR=1, MINOR=0, PATH_SEGMENT="v1"
    │   ├── Messages.kt                     # sealed ClientMessage / ServerMessage (+ requestId)
    │   ├── Errors.kt                       # ProtocolError, ProtocolErrorCode
    │   └── ProtocolJson.kt                 # configured Json (classDiscriminator="type", ignoreUnknownKeys=true)
    └── test/kotlin/magefree/protocol/
        └── SerializationTest.kt            # envelope round-trips + unknown-field tolerance

bridge/ (additions)
├── build.gradle.kts                        # + ktor-server-websockets, + project(":protocol")
└── src/
    ├── main/kotlin/magefree/bridge/ws/
    │   └── SessionWebSocket.kt             # Route.sessionWebSocket(): /v1/session handshake+ping+error
    └── test/kotlin/magefree/bridge/ws/
        └── SessionWebSocketTest.kt         # testApplication WS: handshake, ping/pong, malformed→error
```

**Message model** (`:protocol`), all `@Serializable`, discriminated by a `type` field:

```kotlin
object ProtocolVersion { const val MAJOR = 1; const val MINOR = 0; const val PATH_SEGMENT = "v1" }

sealed interface ClientMessage
sealed interface ServerMessage

@Serializable @SerialName("client_hello")
data class ClientHello(val protocolMajor: Int, val protocolMinor: Int) : ClientMessage

@Serializable @SerialName("server_hello")
data class ServerHello(val protocolMajor: Int, val protocolMinor: Int, val bridgeVersion: String) : ServerMessage

@Serializable @SerialName("ping")
data class Ping(val nonce: String? = null, val requestId: String? = null) : ClientMessage

@Serializable @SerialName("pong")
data class Pong(val nonce: String? = null, val requestId: String? = null) : ServerMessage

@Serializable @SerialName("protocol_error")
data class ProtocolError(val code: ProtocolErrorCode, val message: String, val requestId: String? = null) : ServerMessage

enum class ProtocolErrorCode { PROTOCOL_VERSION_UNSUPPORTED, MALFORMED_MESSAGE, UNKNOWN_MESSAGE_TYPE, INTERNAL }
```

- `requestId` is an **optional correlation id** carried on the envelope now so later
  request/response (app→bridge call) messages can correlate without a protocol change.
- `ProtocolJson.json`: `Json { classDiscriminator = "type"; ignoreUnknownKeys = true; encodeDefaults = false }`.

**Endpoint & handshake** (`:bridge`):
- Install Ktor `WebSockets` with a `KotlinxWebsocketSerializationConverter(ProtocolJson.json)`
  so handlers use `sendSerialized`/`receiveDeserialized`.
- Route `GET /v1/session` (the `v1` path segment = protocol **major**). On open:
  1. Expect the first frame to be a `ClientHello`. If not deserializable →
     `ProtocolError(MALFORMED_MESSAGE)` then close.
  2. If `clientHello.protocolMajor != ProtocolVersion.MAJOR` →
     `ProtocolError(PROTOCOL_VERSION_UNSUPPORTED)` then close.
  3. Otherwise reply `ServerHello(MAJOR, MINOR, bridgeVersion)`. (Minor differences are
     tolerated — additive compat.)
- After the handshake, handle `Ping` → `Pong` (echo `nonce`/`requestId`). Unknown/unhandled
  message types → `ProtocolError(UNKNOWN_MESSAGE_TYPE)` (do not close). Rely on Ktor's built-in
  `pingPeriod`/`timeout` for transport-level keepalive; the app-level ping/pong is for
  end-to-end liveness.

**Versioning & compatibility rules** (document in the `:protocol` module's KDoc/README):
- **Major** version lives in the URL path (`/v1/…`). A breaking change → new major/path.
- **Within a major, additive only:** new optional fields and new message `type`s. Both sides
  set `ignoreUnknownKeys = true` and must tolerate an unknown message `type` gracefully (the
  bridge answers `UNKNOWN_MESSAGE_TYPE`; the app logs and ignores).
- `ServerHello.protocolMinor` and `bridgeVersion` let each side log/adapt.
- This is the "versioned schema contract" of [`../architecture.md`](../architecture.md) —
  0005 and downstream epics **extend** the sealed hierarchies; they do not fork them.

## 5. Implementation steps

1. Add `ktor-server-websockets` and `ktor-client-websockets` (test) to the version catalog.
2. Create the `:protocol` module (`kotlin("jvm")` + kotlinx-serialization plugin, `kotlinx-serialization-json`);
   register it in `settings.gradle.kts`.
3. Implement `ProtocolVersion`, `Messages`, `Errors`, `ProtocolJson` as above; document the
   versioning rules in KDoc/README.
4. Add `SerializationTest`: round-trip each message; assert `type` discriminators; assert a
   payload with an unknown extra field still deserializes (forward-compat).
5. In `:bridge`, add `implementation(project(":protocol"))` and `install(WebSockets)` with the
   kotlinx converter; implement `SessionWebSocket` and register the route in `Application.module()`.
6. Add `SessionWebSocketTest` using `testApplication` + a WS client: (a) happy handshake, (b)
   `PROTOCOL_VERSION_UNSUPPORTED` on major mismatch, (c) ping→pong, (d) malformed first frame →
   `MALFORMED_MESSAGE`.
7. `./scripts/dev gradle check` green.

## 6. Testing & verification

- **Unit (`:protocol`):** serialization round-trips and unknown-field tolerance.
- **Integration (`:bridge`, hermetic):** `testApplication` drives the WebSocket through the
  handshake, ping/pong, version-mismatch, and malformed-message paths. **No live XMage server
  needed** — this story is fully hermetic and runs in the normal `./scripts/dev gradle check` gate.

```bash
./scripts/dev gradle check
```

## 7. Acceptance criteria

- [ ] `:protocol` module exists (pure Kotlin/JVM), builds, and holds the `@Serializable`
      envelope + configured `Json` + version constants; versions pinned via the catalog.
- [ ] JSON uses a `type` discriminator and `ignoreUnknownKeys = true`; round-trip and
      unknown-field tests pass.
- [ ] `:bridge` serves a WebSocket at `/v1/session`: a `ClientHello`/`ServerHello` handshake
      succeeds; a major-version mismatch yields `PROTOCOL_VERSION_UNSUPPORTED` and closes; a
      `Ping` yields a matching `Pong`; a malformed first frame yields `MALFORMED_MESSAGE`.
- [ ] The versioning/compatibility rules are documented in the `:protocol` module.
- [ ] `./scripts/dev gradle check` passes and is hermetic (no XMage server, no `org.mage` dependency in
      `:protocol`).
- [ ] Sealed `ClientMessage`/`ServerMessage` are structured so later stories **extend** them.

## 8. References

- [`../architecture.md`](../architecture.md) — monorepo contract decision; Versioning &
  upstream updates (protocol is axis #3).
- [`AGENTS.md`](../../AGENTS.md) — stack defaults, module rules, version catalog.
- [`0001-bridge-module-scaffold.md`](0001-bridge-module-scaffold.md) — the `:bridge` module and build this extends.
- Ktor docs: Server `WebSockets` plugin + kotlinx serialization converter (`sendSerialized`/`receiveDeserialized`).
