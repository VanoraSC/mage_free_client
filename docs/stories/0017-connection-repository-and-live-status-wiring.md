# 0017 — Connection repository & live status wiring

- **Epic:** EPIC-04 — Server Connection & Sign-In
- **Depends on:** 0016 (`:core:model` / `:core:network`), 0010 (`ConnectionStatusSource` seam)
- **Status:** blocked on 0016

## 1. Objective

Turn the raw `BridgeClient` (0016) into an app-level **connection/session repository** that owns
the lifecycle (connect, hold session, reconnect, disconnect) and exposes a single source of truth
for connection state — and **replace story 0010's stub** behind the `ConnectionStatusSource` seam
with a real implementation driven by the bridge. Also persist the user's **server list** so it
survives restarts.

## 2. Context & background

- Story 0010 introduced `ConnectionStatusSource` (an app seam) backed by a **stub**; its comment
  says "EPIC-04 re-implements this interface against the real bridge session and swaps the Hilt
  binding — the ViewModel and the `ConnectionStatusBar` UI never change." **This is that swap.**
- 0016 provides `BridgeClient` (real + fake) emitting mapped session events; this story consumes
  it, manages the session lifecycle, and maps events → the app `ConnectionState` the shell shows.
- Persistence via **DataStore** (per `AGENTS.md`: DataStore for prefs). Server list = a small set
  of `ServerTarget`s the user has added.

## 3. Scope

**In scope**
- A `ConnectionRepository` (in `:core:network` or a small `:core:data`) that: holds at most one
  active session via `BridgeClient`; exposes `StateFlow<ConnectionState>` (+ any session handle
  needed downstream); offers `connect(server, credentials)` / `disconnect()` / `retry()`; and
  reflects bridge reconnect state.
- A **real `ConnectionStatusSource`** implementation backed by the repository, and the Hilt binding
  swapped from the stub to it (the stub may remain for previews/tests).
- **Server-list persistence** (DataStore): add/remove/list `ServerTarget`s; a `ServerRepository`.
- Turbine tests using `FakeBridgeClient` driving the repository through every state, and DataStore
  round-trip tests.

**Out of scope**
- The connect/sign-in UI and server-management screens (**0018**).
- Registration (**0019**).
- Storing credentials/tokens securely (flag for 0018/0019; do not hard-code or log secrets).

## 4. Prerequisites & toolchain

Deltas from the [Project toolchain baseline](README.md#project-toolchain-baseline):

- Requires 0016 merged. Add `androidx.datastore:datastore-preferences` to the catalog (pinned);
  new Hilt-provided repositories use `magefree.hilt`. Do not change existing pinned versions.

## 5. Design & approach

```
core/network/ (or core/data/)
├── ConnectionRepository.kt     # owns the BridgeClient session; StateFlow<ConnectionState>; connect/disconnect/retry
├── ConnectionStatusSourceImpl.kt   # ConnectionStatusSource backed by ConnectionRepository
├── ServerTarget.kt / ServerRepository.kt   # DataStore-backed server list
└── di/ConnectionBindings.kt    # Hilt: bind ConnectionStatusSource -> real impl (replacing the 0010 stub)
```

- **`ConnectionRepository`** collects `BridgeClient` events on an injected `DispatcherProvider`,
  reduces them into `ConnectionState`, and exposes it as `StateFlow` (`WhileSubscribed`). It is the
  single owner of the session so the UI and the status bar read one consistent state.
- **Seam swap:** `ConnectionStatusSourceImpl` delegates to the repository's `StateFlow`; the Hilt
  module binds `ConnectionStatusSource` → this impl. Story 0010's `ConnectionStatusViewModel` /
  `ConnectionStatusBar` are unchanged — they now reflect the *real* connection.
- **Server list:** `ServerRepository` over DataStore (list of `ServerTarget{name, host, port}`),
  observable as `Flow`.

## 6. Implementation steps

1. Implement `ConnectionRepository` over `BridgeClient` (lifecycle + reduced `ConnectionState`).
2. Implement `ConnectionStatusSourceImpl`; swap the Hilt binding from the 0010 stub to it.
3. Implement `ServerRepository` over DataStore (add/remove/list) + its Hilt provision.
4. Turbine tests (repository state via `FakeBridgeClient`, all states incl. version-unsupported);
   DataStore round-trip tests.
5. Verify the existing `ConnectionStatusBar` now reflects repository state (via the fake).
6. `./gradlew check` green; `./gradlew :app:assembleDebug` builds.

## 7. Testing & verification

- **Hermetic gate:** repository reduction tests (Turbine + `FakeBridgeClient`) across every state;
  server-list DataStore round-trip; `./gradlew check` green with no live bridge.
- **Live (deferred):** once the bridge runs, the same repository drives the real status bar
  end-to-end (opt-in, not required here).

```bash
./gradlew check
```

## 8. Acceptance criteria

- [ ] A `ConnectionRepository` owns the `BridgeClient` session and exposes `StateFlow<ConnectionState>`
      with connect/disconnect/retry; reconnect + version-unsupported states are represented.
- [ ] The `ConnectionStatusSource` seam (story 0010) is bound to a **real** repository-backed impl;
      the existing status bar/ViewModel are unchanged and now reflect real state (verified via the fake).
- [ ] Server list persists via DataStore (add/remove/list), observable as `Flow`.
- [ ] Turbine + DataStore tests pass hermetically; `./gradlew check` + `:app:assembleDebug` green;
      toolchain versions unchanged (new deps are pinned catalog additions).
- [ ] No connect/sign-in UI, no registration, no secret hard-coding/logging.

## 9. References

- `docs/stories/0010-persistent-connection-status-surface.md` — the seam this replaces.
- `docs/stories/0016-app-network-layer-and-session-client.md` — the `BridgeClient` this owns.
- [`AGENTS.md`](../../AGENTS.md) — DataStore, MVVM/StateFlow, DispatcherProvider, no secrets in repo.
