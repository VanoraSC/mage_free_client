# 0024 — App reconnect & lifecycle-aware session

- **Epic:** EPIC-05 — Session Resilience & Notifications
- **Depends on:** 0023 (bridge session hold & resume), 0016 (`:core:network`), 0017 (`ConnectionRepository`)
- **Status:** ready

## 1. Objective

Make the **app** survive network drops, rotation, and backgrounding: automatically reconnect to the
bridge and **resume** the held session (story 0023) — without re-entering credentials — with sane
back-off and network/lifecycle awareness. This is the app-side half of resilience; the bridge holds
the session (0023) and the UX is 0025.

## 2. Context & background

- Story 0016's `KtorBridgeClient` already reconnects, but crudely: a fixed `ReconnectPolicy`
  (3 attempts, 1s) and it **re-sends `Login`** on every attempt. With 0023 the bridge now parks the
  session and issues a `resumeId`, so a reconnect should **`Resume`** the same session, not re-auth.
- Story 0017's `ConnectionRepository` is `@Singleton` on an `@ApplicationScope` `CoroutineScope`, so
  it already **survives Activity rotation** (the session isn't tied to a ViewModel). What's missing
  is *network-* and *lifecycle-*driven reconnection and the resume handshake.
- `ConnectionState`/`SessionEvent` (0016) and the enriched `ConnectionStatus` (0019) already model
  `Reconnecting`; this story drives those transitions for real and adds a "restoring" distinction.
- **Do not fight XMage's version lockstep:** a `VERSION_UNSUPPORTED`/`ResumeRejected` for
  incompatibility is terminal, not a reconnect loop.

## 3. Scope

**In scope**
- **Resume-aware reconnect** in `:core:network`: capture the `resumeId` from 0023 on connect; on an
  unexpected socket drop, reconnect and send `Resume(resumeId)`; on `ResumeRejected` (expired/unknown)
  **fall back to a fresh `Login`** with the retained credentials for the session.
- **Back-off policy:** replace the fixed policy with bounded exponential back-off + jitter and a cap;
  stop on terminal outcomes (`AuthFailed`, `VersionUnsupported`, explicit disconnect).
- **Network awareness:** a `ConnectivityObserver` (Android `ConnectivityManager`) that triggers an
  immediate reconnect attempt when connectivity returns (rather than waiting out a back-off delay).
- **Lifecycle awareness:** reconnect/verify on app **foreground**; while backgrounded, stop
  spending battery on aggressive retries (the *bridge* holds the session per 0023, so the app can go
  quiet and resume on return, within the bridge's TTL). Survives rotation (already, via the
  repository scope) — verify and keep it that way.
- Turbine tests over a fake `BridgeClient` + fake connectivity/lifecycle driving every path.

**Out of scope**
- The bridge-side hold/resume protocol (**0023**).
- Reconnecting/restoring **UX/surfaces** (**0025**).
- A persistent background foreground-service connection and push (later EPIC-05 slice; the design
  here is "bridge holds; app resumes on return," not "app stays connected in the background").
- Secure credential storage (still flagged, not built — credentials retained only in memory for the
  active session's resume-fallback).

## 4. Design & approach

- **`BridgeClient`/`KtorBridgeClient`:** expose/capture the `resumeId`; a reconnect attempt sends
  `Resume(resumeId)` when one is held, else `Login`; a `ResumeRejected` frame clears the handle and
  retries with `Login`. The reconnect loop uses `ReconnectPolicy` = initial delay, multiplier, max
  delay, optional max attempts (a `WhileSubscribed` connection stays cheap when nothing observes).
- **`ConnectivityObserver`** (new, `:core:network`, Android): a `Flow<Boolean>` from
  `ConnectivityManager.NetworkCallback`; the reconnect loop collects it and “kicks” a waiting back-off
  when the network returns. A no-op/always-available fake for hermetic tests.
- **`ConnectionRepository`** (0017): thread the reconnect/back-off through the existing single-session
  `flatMapLatest`/`shareIn` seam; keep `connectionState` and `connectionStatus` semantics — a resume
  in progress surfaces as `Reconnecting` (and `Restoring`, see 0025) rather than `Disconnected`.
- **Lifecycle:** a small `ProcessLifecycleOwner`-based hook (in `:app` or `:core:network`) that
  nudges a reconnect/health-check on foreground; backgrounding relaxes retry cadence.
- Everything runs on injected dispatchers; no hard-coded `Dispatchers.*`.

## 5. Implementation steps

1. Capture the `resumeId` (0023) in `KtorBridgeClient`; send `Resume` on reconnect, `Login`
   otherwise; handle `ResumeRejected` → `Login` fallback.
2. Implement exponential back-off + jitter `ReconnectPolicy`; stop on terminal outcomes.
3. Add `ConnectivityObserver` (+ fake) and wire it to kick the back-off on network return.
4. Add the foreground hook (reconnect/verify on `ON_START`); confirm rotation survival.
5. Turbine tests (fake bridge + fake connectivity/lifecycle): drop → auto-reconnect + `Resume` →
   `Connected`; `ResumeRejected` → `Login` fallback; terminal `AuthFailed`/`VersionUnsupported` stop
   the loop; network-return kicks an immediate attempt; back-off growth is bounded.
6. `:core:network:check` + `:app:testDebugUnitTest` green (host toolchain); `:app:assembleDebug` builds.

## 6. Testing & verification

- **Hermetic gate:** ViewModel/repository state-machine tests with fakes for every reconnect/resume/
  fallback/terminal path and the connectivity/lifecycle triggers; no live bridge.
- **Live (opt-in):** with the bridge + reference server, drop the device network briefly and confirm
  the app reconnects and **resumes** (no re-auth) within the bridge's TTL; and that a drop longer than
  the TTL cleanly falls back to `Login`.

## 7. Acceptance criteria

- [ ] After an unexpected drop, the app reconnects automatically and **`Resume`s the same session**
      (no credential re-entry) when within the bridge's window; a `ResumeRejected` falls back to a
      fresh `Login`.
- [ ] Reconnection uses bounded exponential back-off + jitter and **stops** on terminal outcomes
      (`AuthFailed`, `VersionUnsupported`, explicit disconnect).
- [ ] Connectivity return triggers an immediate reconnect; app foreground re-verifies; the session
      survives rotation.
- [ ] `connectionState`/`connectionStatus` report `Reconnecting` (not a bare `Disconnected`) during
      recovery; 0010's status bar reflects it.
- [ ] Turbine tests cover every path with fakes; `:core:network:check` + `:app:testDebugUnitTest` +
      `:app:assembleDebug` green; no secrets logged/persisted.
- [ ] No bridge-side changes, UX surfaces, or notifications here.

## 8. References

- [`0023-bridge-session-hold-and-resume.md`](0023-bridge-session-hold-and-resume.md) — the resume handle + protocol this consumes.
- [`0016-app-network-layer-and-session-client.md`](0016-app-network-layer-and-session-client.md) — `BridgeClient`/`KtorBridgeClient`/`ReconnectPolicy`.
- [`0017-connection-repository-and-live-status-wiring.md`](0017-connection-repository-and-live-status-wiring.md) — the `@ApplicationScope` single-session seam.
- [`AGENTS.md`](../../AGENTS.md) — injected dispatchers, no secrets, error-as-state.
