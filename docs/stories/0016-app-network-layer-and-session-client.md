# 0016 — App network layer & session client

- **Epic:** EPIC-04 — Server Connection & Sign-In
- **Depends on:** 0004 (`:protocol` shared module), 0007 (`:app` / build setup)
- **Status:** ready (0004 `:protocol` merged)

## 1. Objective

Build the app's networking foundation: `:core:model` (the app's connection/session domain
types) and `:core:network` (a **WebSocket bridge client** that speaks the shared `:protocol`
contract, maps its DTOs into `:core:model`, and exposes connection/session state as `Flow`s),
plus a **`FakeBridgeClient`** so the app can be built and tested without a live bridge. UI never
sees `:protocol` wire types — only mapped `:core:model` types.

## 2. Context & background

- **Dependency:** the bridge↔app wire contract lives in the shared **`:protocol`** module (Epic 1
  story **0004**). This story consumes it, so **0004 must be implemented first.** If `:protocol`
  does not yet exist, STOP and flag it — do not fork a parallel copy of the contract.
- Architecture ([`../architecture.md`](../architecture.md)): the app talks only to the bridge over
  WebSocket+JSON; data flows network → mapped domain model → ViewModel. `mage.view.*` never
  appears on device; the app is decoupled behind the `:protocol` schema.
- **Fakes are recordings:** the `FakeBridgeClient` scripts real `:protocol` message sequences
  (ideally captured from a running bridge once 0005 exists), not invented shapes.
- Target module layout ([`AGENTS.md`](../../AGENTS.md)): `:core:model` (pure Kotlin domain),
  `:core:network` (bridge client, DTOs, mappers → `:core:model`).

## 3. Scope

**In scope**
- `:core:model` (pure Kotlin/JVM library): app domain types for connection & session —
  `ConnectionState` (align with story 0010's app enum; consider consolidating here),
  `ServerTarget`, `Credentials`, a `Session` handle, and result/error types.
- `:core:network` (Android or Kotlin/JVM library): a `BridgeClient` interface + a WebSocket
  implementation (Ktor client or OkHttp + kotlinx.serialization) that performs the `:protocol`
  handshake, sends `Login`, and exposes `SessionStatus`/relayed events as `Flow`/`StateFlow`,
  **mapped** into `:core:model`. Reconnection-aware.
- A `FakeBridgeClient` (same interface) driving scripted `:protocol` sequences for tests.
- Unit tests (Turbine) over the mappers and the client state machine using the fake.

**Out of scope**
- Wiring into the app UI / the 0010 status seam (that is **0017**).
- The connect/sign-in screens (**0018**) and registration (**0019**).
- Any game/lobby/deck message handling beyond session-level status (later epics).

## 4. Prerequisites & toolchain

Deltas from the [Project toolchain baseline](README.md#project-toolchain-baseline):

- Requires **0004 (`:protocol`)** merged; `:core:network` depends on `project(":protocol")`.
- New modules apply the convention plugins: `:core:model` is a plain Kotlin/JVM library (or
  `magefree.android.library` if it must be Android); `:core:network` applies
  `magefree.android.library` (+ `magefree.hilt` if it provides injected clients). Add the WebSocket
  client + kotlinx-serialization deps **to the catalog** (pinned) — if a genuinely new library is
  needed, that is a legitimate catalog addition (unlike a version workaround); still, prefer the
  Ktor client already implied by the stack. Do not change existing pinned versions.

## 5. Design & approach

```
core/model/                     # pure Kotlin: ConnectionState, ServerTarget, Credentials, Session, errors
core/network/
├── BridgeClient.kt             # interface: connect(server, credentials): Flow<SessionEvent>; disconnect()
├── ktor/KtorBridgeClient.kt    # real WebSocket impl over :protocol; maps DTOs -> :core:model
├── mapper/                     # :protocol <-> :core:model mappers (the single coupling point)
└── fake/FakeBridgeClient.kt    # scripted :protocol sequences for tests
```

- **`BridgeClient`** is the seam the rest of the app depends on; the Ktor impl and the fake are
  interchangeable (fakes over mocks, per `AGENTS.md`).
- The client opens the `:protocol` WebSocket (`/v1/session`), performs the handshake, sends
  `Login`, and emits mapped session events (`Connecting`/`Connected`/`AuthFailed`/
  `VersionUnsupported`/`Disconnected`/`Reconnecting`). **Version-mismatch is a first-class state**
  (mirrors story 0005) — surface it distinctly, never as a generic error.
- All wire (`:protocol`) types are mapped to `:core:model` in `mapper/`; nothing above
  `:core:network` imports `:protocol`.

## 6. Implementation steps

1. Create `:core:model` with the connection/session domain types; register in `settings.gradle.kts`.
2. Create `:core:network` (convention plugins; depends on `:protocol` + `:core:model`); add the
   WebSocket client + serialization deps to the catalog if not present.
3. Implement `BridgeClient` + the Ktor WebSocket impl + the `:protocol`→`:core:model` mappers.
4. Implement `FakeBridgeClient` with scripted sequences covering every session state.
5. Turbine unit tests: mapper correctness; the client state machine driven by the fake.
6. `./gradlew check` green.

## 7. Testing & verification

- **Hermetic gate:** mapper + client-state-machine unit tests via `FakeBridgeClient` (Turbine). No
  live bridge required — `./gradlew check` passes.
- **Live (deferred):** once the bridge (0005) runs, add an opt-in integration test that connects a
  real `KtorBridgeClient` to the bridge and observes `Connecting`→`Connected` (env-gated, mirroring
  the bridge stories' pattern). This story does **not** require it to pass.

```bash
./gradlew check
```

## 8. Acceptance criteria

- [ ] `:core:model` holds the app connection/session domain types (pure Kotlin, no `:protocol` imports).
- [ ] `:core:network` provides a `BridgeClient` interface, a Ktor WebSocket impl speaking `:protocol`
      (handshake + `Login` + mapped session events), and a `FakeBridgeClient`.
- [ ] `:protocol` wire types are confined to `:core:network` mappers; nothing above imports them.
- [ ] Version-mismatch surfaces as a distinct, first-class state.
- [ ] Turbine unit tests over mappers + the fake-driven state machine pass; `./gradlew check` is
      hermetic and green; toolchain versions unchanged (new libs, if any, are pinned catalog additions).
- [ ] No UI, no 0010-seam wiring, no domain (lobby/game) messages beyond session status.

## 9. References

- [`../architecture.md`](../architecture.md) — the app↔bridge boundary; version-mismatch as first-class.
- `docs/stories/0004-protocol-contract-v1-and-schema-versioning.md` and `0005-...` — the contract + session this consumes.
- `docs/stories/0010-persistent-connection-status-surface.md` — the app `ConnectionState` to align with (0017 wires the seam).
- [`AGENTS.md`](../../AGENTS.md) — module layout, fakes over mocks, Flow/StateFlow, DispatcherProvider.
