# 0019 — Auth, version & network error handling

- **Epic:** EPIC-04 — Server Connection & Sign-In
- **Depends on:** 0018 (connect & sign-in UI)
- **Status:** ready (registration descoped — see below)

> **Registration is permanently deferred (2026-07-30, Pete).** The account-registration feature —
> including any email/token confirmation flow, the `:protocol` register contract, and bridge-side
> proxying of XMage's `authRegister` — is **out of scope indefinitely** and must not be built unless
> Pete explicitly reopens it. The primary connect + sign-in path (0016–0018) covers the real need;
> live registration would require an auth-on server variant plus email infrastructure (high cost,
> low value). This story is therefore rescoped to the **error-handling** half only.

## 1. Objective

Make the connect/sign-in failure paths clear, detailed, and recoverable: enrich story 0017's
repository seam so error **detail** (the server/bridge versions on a version mismatch, an auth
message, a transport reason) reaches the UI, and present distinct, actionable surfaces for
**invalid credentials**, **version-unsupported**, and **network/timeout** — each with retry/cancel.
This closes the diagnostic gap flagged by 0018 (where `ConnectionState` alone carried no detail).

## 2. Context & background

- 0018 built the connect/sign-in UI with `AuthFailed` and `VersionUnsupported` surfaces already
  able to *display* a detail string — but 0017's `ConnectionRepository` maps every
  `magefree.model.SessionEvent` down to a bare `ConnectionState` before it leaves `:core:network`,
  so the detail (e.g. `SessionEvent.VersionUnsupported.detail`, `AuthFailed.message`,
  `Error(ConnectionError.Transport)`) is dropped. **This story surfaces that detail.**
- The domain already models the taxonomy: `magefree.model.SessionEvent` distinguishes `AuthFailed`,
  `VersionUnsupported`, `Disconnected`, and `Error(ConnectionError.Transport | .Protocol)`, and
  `KtorBridgeClient` already emits `Transport` errors for socket/timeout failures. The work is to
  **carry** that through the repository seam and **render** it, not to invent new network plumbing.
- **Version-unsupported** stays first-class (from 0005/0016): it is presented as its own state with
  the server/bridge versions when available, never collapsed into a login error.

## 3. Scope

**In scope**
- **Enrich the 0017 seam:** `ConnectionRepository` exposes error **detail** to the feature — e.g. an
  added `StateFlow<ConnectionStatus>` (state + optional detail/error) alongside the existing bare
  `connectionState`. The existing `connectionState` **must keep working unchanged** so story 0010's
  status bar / `ConnectionStatusSourceImpl` are untouched.
- **UI error taxonomy** in `:feature:connect`: distinct, actionable surfaces for `InvalidCredentials`,
  `VersionUnsupported` (with `server=… bridge=…` detail when present), and `Network`/`Timeout`, each
  with retry/cancel via the design system's `DecisionPrompt`. Reuse/extend 0018's existing surfaces
  rather than replacing them.
- ViewModel state machines + Turbine tests over fake repositories for every error path.

**Out of scope / deferred**
- **Account registration — permanently deferred** (see the note above): no registration UI, no
  `:protocol` register messages, no bridge-side registration. Do not build it.
- Secure credential/token storage strategy (flag as its own decision; never hard-code/log secrets).
- Anything post-connect (lobby etc.).

## 4. Prerequisites & toolchain

Deltas from the [Project toolchain baseline](README.md#project-toolchain-baseline):

- Requires 0018 merged. Host (Android) build. No new modules expected. Changes span `:core:model`
  (if a small `ConnectionStatus`/detail type is added), `:core:network` (repository seam
  enrichment — additive, `connectionState` preserved), and `:feature:connect` (surfaces + VMs). No
  toolchain/version changes; any new lib is a pinned catalog addition (unlikely).

## 5. Design & approach

- **Seam enrichment:** add a detail-carrying status to `ConnectionRepository` (e.g.
  `ConnectionStatus(state: ConnectionState, detail: String? = null, error: ConnectionError? = null)`
  as a `StateFlow`), reduced from the same `SessionEvent`s the bare `connectionState` already
  reduces — so the two stay consistent and `connectionState` (0010's dependency) is unchanged.
- **Error taxonomy → UI:** map the enriched status onto distinct feature states —
  `InvalidCredentials`, `VersionUnsupported(detail)`, `Network`/`Timeout` (from
  `ConnectionError.Transport`) — each rendered via 0018's design-system surfaces with a
  `DecisionPrompt` retry/cancel. Wire the `VersionUnsupported` detail (`server=… bridge=…`) into the
  surface 0018 already built to accept it.
- Reuse the design system throughout; never log/persist secrets.

## 6. Implementation steps

1. Add the detail-carrying `ConnectionStatus` to `ConnectionRepository` (additive; keep bare
   `connectionState` for 0010) and reduce it from the bridge's `SessionEvent`s (incl. `Transport`
   error → network/timeout).
2. Extend `:feature:connect`'s VMs/`ConnectPhase` (or an error-state model) to consume the enriched
   status and expose the taxonomy to the surfaces.
3. Wire detail into the existing `AuthFailed`/`VersionUnsupported` surfaces and add a
   `Network`/`Timeout` surface; retry/cancel via `DecisionPrompt`.
4. Turbine ViewModel tests for invalid-credentials, version-unsupported (with detail), and
   network/timeout, using fake repositories.
5. `:feature:connect:check` + `:core:network:check` green; `:app:assembleDebug` builds; 0010's
   `ConnectionStatusViewModelTest` still passes.

## 7. Testing & verification

- **Hermetic gate:** ViewModel/state-machine tests (Turbine, fake repositories) for every error
  path, plus the seam-enrichment reduction; `./gradlew check` green with no live bridge.
- **Live (deferred):** once exercised end-to-end, a real bad-credential and a real version-mismatch
  produce the detailed surfaces — opt-in, not part of the gate.

```bash
./gradlew :feature:connect:check :core:network:check :app:assembleDebug
```

## 8. Acceptance criteria

- [ ] `ConnectionRepository` exposes error **detail** to the feature without changing the bare
      `connectionState` (0010's status bar / `ConnectionStatusSourceImpl` untouched, its test green).
- [ ] Distinct, actionable surfaces exist for invalid credentials, version-unsupported (with
      `server=… bridge=…` when available), and network/timeout — each with retry/cancel via the
      design system.
- [ ] `VersionUnsupported` is presented as its own first-class state, not a login error.
- [ ] Turbine tests cover every error path (fake repositories); `:feature:connect:check`,
      `:core:network:check`, and `:app:assembleDebug` green; toolchain unchanged; no secrets
      logged/persisted.
- [ ] **No registration** work of any kind; secure-storage strategy is flagged, not improvised.

## 9. References

- [`../architecture.md`](../architecture.md) — version-mismatch first-class.
- `docs/stories/0018-connect-and-sign-in-ui.md` — the sign-in flow + surfaces this enriches.
- `docs/stories/0017-connection-repository-and-live-status-wiring.md` — the `ConnectionRepository`
  seam this enriches (additively).
- [`AGENTS.md`](../../AGENTS.md) — error modeling as UI state, accessibility, no secrets.
