# 0005 — Session bridge: connect / login / reconnect over WebSocket

- **Epic:** EPIC-01 — Bridge & Server Integration
- **Depends on:** 0003, 0004
- **Status:** ready

## 1. Objective

Wire the app-facing WebSocket protocol (0004) to a per-client XMage session (0003): an app
connects to the bridge, completes the protocol handshake, sends **Login**, and the bridge
opens a `SessionImpl` to the **pinned** XMage server, authenticates, and **relays live session
state** — connecting, connected, auth-failed, disconnected, reconnecting, and
**version-unsupported** — back to the app. One upstream session per socket, cleaned up when
the socket closes. Still **no game/lobby/deck domain data and no `mage.view.*` mapping** (0006+).

## 2. Context & background

- 0004 defined the sealed `ClientMessage`/`ServerMessage` envelope and the `/v1/session`
  handshake. This story **extends** those sealed types (per 0004's "extend, don't fork" rule)
  with session messages — it does not create a parallel protocol.
- 0003 gave `XMageSession` (wraps `SessionImpl`), `BridgeMageClient` (the `MageClient` sink),
  and `XMageConnection`. This story drives them from the socket and turns the
  `MageClient`/connection-listener events into protocol messages.
- **Pinned-server posture** ([`../architecture.md`](../architecture.md), Decision #6): the
  bridge targets **its own configured** XMage server; the app does **not** send host/port. The
  bridge reads its upstream target from config (env `XMAGE_UPSTREAM`, default `xmage-server:17171`).
- **Version mismatch is first-class** (side-conversation decision): `connectStart` throws
  `MageVersionException(clientVersion, serverVersion)` on a version gap. The bridge must catch
  it and emit a specific `VERSION_UNSUPPORTED` status carrying both versions — never a generic
  failure. With a pinned server this should not normally happen, but it stays legible.
- Relevant upstream event sources (from 0003's grounding):
  - `connectStart` returns `true` → authenticated; `MageClient.connected(msg)` fires.
  - `connectStart` returns `false` → bad credentials (auth failure).
  - `connectStart` throws `MageVersionException` → version mismatch.
  - `MageClient.disconnected(askToReconnect, keepMySessionActive)` and the connection
    listener's reconnect path signal drop/reconnect.

## 3. Scope

**In scope**
- Protocol additions in `:protocol`: `Login`/`Logout` (client) and `SessionStatus` +
  `SessionStateCode` (server).
- A testable **`UpstreamSession` seam** (interface) with a real impl over 0003 and a fake for
  hermetic tests.
- Per-socket **`SessionCoordinator`**: after the handshake, await `Login`, drive the upstream
  session, stream `SessionStatus` updates, and tear down on socket close.
- Thread-safe hand-off from `SessionImpl`'s callback threads to the Ktor coroutine.
- Hermetic tests (fake upstream) + an env-gated live integration test (real login end-to-end).

**Out of scope**
- App↔bridge socket **reconnection/session-resume** across a dropped WebSocket, and "your
  turn"/background concerns — those are **EPIC-05**. Here the bridge only surfaces the
  *upstream* connection's reconnect state and cleans up on socket close.
- Any lobby/table/deck/game payloads and `mage.view.*` mapping (**0006** and downstream epics).
- Multiple concurrent upstream sessions on one socket (one per socket for now).

## 4. Design & approach

**Protocol additions** (`:protocol`, extend the 0004 sealed types):

```kotlin
@Serializable @SerialName("login")
data class Login(val username: String, val password: String? = null, val requestId: String? = null) : ClientMessage

@Serializable @SerialName("logout")
data class Logout(val requestId: String? = null) : ClientMessage

@Serializable @SerialName("session_status")
data class SessionStatus(
    val state: SessionStateCode,
    val message: String? = null,      // human-readable detail (e.g. "server=1.4.61 bridge=1.4.60")
    val requestId: String? = null,
) : ServerMessage

enum class SessionStateCode { CONNECTING, CONNECTED, AUTH_FAILED, VERSION_UNSUPPORTED, DISCONNECTED, RECONNECTING }
```

**Bridge structure**

```
bridge/src/main/kotlin/magefree/bridge/session/
├── UpstreamSession.kt        # interface + Credentials; emits SessionStatus, supports disconnect()
├── XMageUpstreamSession.kt   # real impl: wraps 0003 XMageSession/BridgeMageClient
└── SessionCoordinator.kt     # per-socket orchestration
bridge/src/main/kotlin/magefree/bridge/ws/
└── SessionWebSocket.kt       # (extended) after handshake -> SessionCoordinator
```

- **`UpstreamSession`** (the test seam):
  ```kotlin
  data class Credentials(val username: String, val password: String?)
  interface UpstreamSession {
      /** Connects and emits status transitions until disconnected. */
      fun connect(credentials: Credentials): Flow<SessionStatus>
      suspend fun disconnect()
  }
  ```
  Real impl `XMageUpstreamSession` builds a `Connection` (via `XMageConnection`, using the
  configured upstream host/port), runs `connectStart` on `Dispatchers.IO`, and translates
  events → `SessionStatus`:
  - emit `CONNECTING` before `connectStart`;
  - `true` → `CONNECTED`; `false` → `AUTH_FAILED`;
  - `MageVersionException` → `VERSION_UNSUPPORTED` (message = `"server=<serverVer> bridge=<clientVer>"`);
  - connection-listener reconnect → `RECONNECTING`; `MageClient.disconnected(...)` → `DISCONNECTED`.
  - **Thread hand-off:** `BridgeMageClient` (called on remoting threads) pushes events into a
    `Channel`/`MutableSharedFlow`; the `Flow` returned by `connect` collects from it. Never
    call Ktor `send` directly from a remoting thread.
- **`SessionCoordinator`** (one per socket):
  - Assumes the 0004 handshake already completed.
  - Reads frames; the first meaningful one must be `Login` (a `Ping` is still answered with
    `Pong`; a second `Login` while active is rejected with a `ProtocolError` or ignored — pick
    one and document).
  - On `Login`: launch `upstream.connect(Credentials(...))` and forward each `SessionStatus`
    (with the login's `requestId` on the first emission) to the socket via `sendSerialized`.
  - On `Logout` or socket close/cancel: `upstream.disconnect()` and cancel the collection
    (structured concurrency — tie scopes to the WebSocket session).
- **`SessionWebSocket`** delegates to `SessionCoordinator` after `ServerHello`.

**Config:** the upstream target comes from `XMAGE_UPSTREAM` (`host:port`, default
`xmage-server:17171`); credentials come from the app's `Login`.

## 5. Implementation steps

1. Add `Login`/`Logout`/`SessionStatus`/`SessionStateCode` to `:protocol`; extend the
   serialization round-trip tests for them.
2. Define `UpstreamSession` + `Credentials` and a `FakeUpstreamSession` (test util) that emits
   a scripted `SessionStatus` sequence.
3. Implement `XMageUpstreamSession` over 0003 (`XMageSession`/`BridgeMageClient`), including the
   `MageVersionException` → `VERSION_UNSUPPORTED` mapping and the channel-based thread hand-off.
   Adjust `BridgeMageClient` (from 0003) to publish its events to a channel/flow.
4. Implement `SessionCoordinator`; wire it into `SessionWebSocket` after the handshake; read the
   upstream target from config.
5. **Hermetic tests** (`FakeUpstreamSession` + `testApplication`): handshake → `Login` →
   expect `CONNECTING` then `CONNECTED`; a fake emitting a version gap → `VERSION_UNSUPPORTED`
   with both versions in `message`; bad-credentials fake → `AUTH_FAILED`; socket close →
   `disconnect()` invoked.
6. **Live integration test** (env-gated `XMAGE_SERVER`, mirrors 0003): real WS client → handshake
   → `Login` with a throwaway username → observe `CONNECTING`/`CONNECTED` against the pinned
   local server; close and confirm clean teardown.
7. `./scripts/dev gradle check` green (hermetic tests run; live test skipped without the env var).

## 6. Testing & verification

- **Hermetic (default gate):** `FakeUpstreamSession` drives every `SessionStateCode` path
  through the real WebSocket/coordinator plumbing — no XMage needed.
- **Live (opt-in):**
  ```bash
  ./scripts/dev up xmage-server
  XMAGE_SERVER=xmage-server:17171 ./scripts/dev gradle :bridge:test --tests '*SessionBridgeIT'
  ```
  A WebSocket client logs in through the bridge to the real server and observes
  `CONNECTING` → `CONNECTED`.

## 7. Acceptance criteria

- [ ] `:protocol` gains `Login`/`Logout`/`SessionStatus`/`SessionStateCode`, added by
      **extending** the 0004 sealed types; round-trip tests pass.
- [ ] `UpstreamSession` seam exists with a real (`XMageUpstreamSession`) and a fake impl;
      remoting-thread events reach the socket via a channel/flow, never a direct cross-thread send.
- [ ] Over `/v1/session`: handshake → `Login` yields `CONNECTING` then `CONNECTED` against the
      pinned server; `Logout`/socket-close disconnects the upstream cleanly.
- [ ] Bad credentials → `AUTH_FAILED`; a version gap → `VERSION_UNSUPPORTED` with both versions
      in `message` (verified with the fake).
- [ ] Upstream drop surfaces `RECONNECTING`/`DISCONNECTED` (verified with the fake; observed
      live if feasible).
- [ ] Hermetic tests cover all state paths; the live test is env-gated and skipped by default;
      `./scripts/dev gradle check` stays green.
- [ ] No domain/game payloads and no `mage.view.*` decoding are introduced here.

## 8. References

- [`0003-embed-client-session-connect-authenticate.md`](0003-embed-client-session-connect-authenticate.md) — `XMageSession`, `BridgeMageClient`, `XMageConnection`, `MageVersionException`.
- [`0004-protocol-contract-v1-and-schema-versioning.md`](0004-protocol-contract-v1-and-schema-versioning.md) — the envelope and handshake this extends.
- [`../architecture.md`](../architecture.md) — pinned-server posture and version-mismatch-as-first-class-state.
- `../mage/Mage.Common/src/main/java/mage/remote/SessionImpl.java` — connect/reconnect/disconnect behavior and `MageVersionException`.
