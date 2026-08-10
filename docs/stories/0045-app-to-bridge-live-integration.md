# 0045 — App↔bridge live integration

- **Epic:** Cross-cutting (foundation hardening)
- **Depends on:** 0022 (reference XMage server), 0039/0040 (live table coverage), 0028 (app bridge client), 0037 (table client)
- **Status:** ready

## 1. Objective

Prove the **app-side stack actually talks to a real bridge**. Today every live test stops at `:bridge`;
everything above it — `KtorBridgeClient`, the `PendingRequests` correlation, `SessionRelay`,
reconnect/resume, `LobbyClient`, `TableClient`, `TableEventFold` — has only ever run against its own
fakes. Close that gap with an env-gated integration test that drives the **real app client** against a
**real bridge** connected to the **real reference server**.

## 2. Context & background

- **The gap (measured, 2026-08-09):** a repo-wide search for live-gated tests outside `:bridge` returns
  **nothing**; the only instrumented tests are UI-only (`app/src/androidTest`, `feature/connect`). So the
  bridge is proven to speak the protocol correctly (0039's `TableRelayIT`, run against XMage), and the
  app is proven to speak it *as its fakes imagine it* — **the two have never been connected**.
- **Why this matters here specifically.** Every Epic 7 defect found in the 2026-08-07 audit was of
  exactly this shape: a fake agreeing with itself. `TableRoomViewModelTest` injected a finished
  `TableState` and passed while seats were unreachable; `FakeTableClient` ignored its `seed` while
  production emitted it. A fake cannot disagree with the thing it is standing in for. Only a real
  connection can.
- **What this would have caught.** The `SeatUpdated` fold had no producer — a live app↔bridge run
  reaches `READY_TO_START` or it does not. Likewise a `requestId` correlation mismatch, an
  `ignoreUnknownKeys` drift, a sealed-type discriminator rename, or a `ServerHello` version mismatch:
  all invisible to fakes, all fatal in production.
- **The transport is the app's own.** `:core:network` speaks pure WebSocket + JSON (`:protocol`); it has
  **no** `mage.*` dependency and must never gain one. So the test needs only a **URL** — it must not
  add `:bridge` as a dependency of an Android module (that would break the boundary in
  `docs/architecture.md`).

## 3. Scope

**In scope**
- **A runnable bridge in Compose.** `docker/docker-compose.yml` gains a `bridge` service (the bridge
  jar, `depends_on: xmage-server`, its upstream pointed at `xmage-server:17171`, its own port exposed),
  alongside the existing `build` and `xmage-server` services. Documented in
  [`../build-environment.md`](../build-environment.md) and reachable via `./scripts/dev up bridge`.
- **Env-gated app-side integration tests** in `:core:network` (mirroring the `XMAGE_SERVER` pattern:
  `@EnabledIfEnvironmentVariable`-equivalent on a `BRIDGE_URL` variable, so the default build stays
  hermetic and offline). Driving the **real** `KtorBridgeClient` — not a fake — through:
  1. **Connect + login** → a `Connected` session (proves the handshake, `ServerHello`/version check).
  2. **Lobby read** → `LobbyClient.tables()/roomUsers()/gameTypes()` return mapped domain types
     (proves request/response correlation over the live socket).
  3. **Host a table end to end** via `TableClient`: `createTable` → seat an AI → seat self → observe
     `observeTable` reach **seats populated** and **`isReadyToStart`** → `startMatch` → the
     `MatchStarting` signal (proves the whole 0037/0040/0041 path, including the `GetTable` read and
     the fold, against real server data).
  4. **Teardown** (`removeTable`) so runs are repeatable and leave no stuck table.
- A **resume/reconnect** case if it can be driven deterministically (drop the socket, confirm the
  session resumes and the table subscription re-syncs). If it cannot be made reliable, say so and skip
  it rather than shipping a flaky test.

**Out of scope**
- Running the **Android app itself** (instrumented/emulator) against the bridge — a bigger lift; this
  story validates the app's *networking stack*, which is where the protocol risk lives.
- Gameplay past `MatchStarting` (EPIC-11); tournaments (EPIC-08).
- Any production behavior change — this story is **test + infrastructure only**. If it uncovers a
  defect, that is a finding to report, not to fix here.
- Making these part of the default gate: they stay **opt-in** and env-gated.

## 4. Design & approach

```
docker/docker-compose.yml      # + bridge service (depends_on xmage-server)
core/network/src/test/…/live/  # env-gated app<->bridge tests over the real KtorBridgeClient
```

- **Mirror the existing live pattern** (`bridge/src/test/.../TableRelayIT.kt`, `XMageServerTarget`):
  gate on an env var, resolve the target from it, document the run command in KDoc, and tear down in a
  `finally` so a failed assertion still cleans up.
- **Unique identities per run** (`it_<uuid>`-style users and table names) so repeated runs and parallel
  execution don't collide on the long-lived server.
- **Assert the app's domain types**, not wire shapes — the point is that the app's *mapped* view is
  correct end to end.

## 5. Implementation steps

1. Add the `bridge` Compose service; verify `./scripts/dev up bridge` yields a reachable socket.
2. Env-gated harness in `:core:network` (target from `BRIDGE_URL`, unique identities, teardown).
3. Connect/login + lobby-read tests.
4. The full host→seat→ready→start→`MatchStarting` test over `TableClient`/`observeTable`.
5. Optional resume/reconnect case (or a documented reason it was skipped).
6. Verify hermetic (tests **skip** without `BRIDGE_URL`) and live (they run and pass, twice).

## 6. Testing & verification

- **Hermetic (default):** `:core:network:check` green with the new tests **skipped** — no network in
  the normal build.
- **Live (opt-in):** with `xmage-server` + `bridge` up, the suite runs green **twice in a row**
  (proving teardown and repeatability), and the existing `:bridge` ITs stay green in the same
  environment.

## 7. Acceptance criteria

- [ ] A `bridge` service runs in Compose against the reference server, documented and startable via
      `./scripts/dev`.
- [ ] Env-gated tests drive the **real** `KtorBridgeClient` (no fakes) through connect/login, a lobby
      read, and a full host → seat AI → seat self → **ready** → start → **`MatchStarting`** flow,
      asserting the app's **domain** types.
- [ ] The tests **skip** without the env var, so `:core:network:check` stays hermetic and offline.
- [ ] Runs are repeatable and leave no stuck table; the suite passes twice consecutively.
- [ ] `:core:network` gains **no** `mage.*` or `:bridge` dependency.
- [ ] Any defect uncovered is **reported**, not silently fixed inside this story.

## 8. References

- `bridge/src/test/kotlin/magefree/bridge/mapping/TableRelayIT.kt` — the live pattern to mirror; [`0039-live-table-action-coverage.md`](0039-live-table-action-coverage.md).
- [`0040-table-seat-state.md`](0040-table-seat-state.md) / [`0041-host-seating-flow.md`](0041-host-seating-flow.md) — the app-side flow this exercises for real.
- [`../build-environment.md`](../build-environment.md) — the Compose services this extends; [`../architecture.md`](../architecture.md) — the `mage.*` boundary this must not cross.
