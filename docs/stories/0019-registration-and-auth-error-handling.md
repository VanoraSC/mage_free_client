# 0019 — Registration & auth error handling

- **Epic:** EPIC-04 — Server Connection & Sign-In
- **Depends on:** 0018 (connect & sign-in UI)
- **Status:** blocked on 0018

## 1. Objective

Complete Epic 4: **account registration** where the server supports it, and a robust
**auth/version error-handling and retry** experience across the connect flow. This makes a new
user able to start without a desktop client, and makes the common failure paths clear and
recoverable.

## 2. Context & background

- The bridge proxies XMage's own auth ([`../architecture.md`](../architecture.md), Decision #5).
  The server contract exposes registration (`authRegister`, `authSendTokenToEmail`,
  `authResetPassword`) — but only when the server has `authenticationActivated` (registration is
  server-dependent). The UI must **discover/adapt** to whether registration is available.
- Builds on 0018's sign-in flow and the design-system prompt/error surfaces.
- **Version-unsupported** is a first-class state (from 0005/0016): registration/sign-in must present
  it distinctly (the client can't proceed until the bridge matches the server), not as a login error.

## 3. Scope

**In scope**
- A **registration** flow in `:feature:connect` (username / password / email as the server
  requires), driven through the bridge's proxied registration — gated on the server actually
  supporting it (shown only when available; otherwise a clear "registration not available here").
- **Auth error handling:** distinct, actionable surfaces for invalid credentials, registration
  failures (e.g. name taken), and network/timeout, using the design-system error/state views and
  `DecisionPrompt` for retry/cancel.
- **Version-unsupported handling:** a dedicated, legible state (server X / bridge Y) with the right
  guidance, wired through the connect flow.
- ViewModel state machines + Turbine tests over fake repositories for each path.

**Out of scope**
- Password reset UX beyond wiring the existing server capability (can be a thin flow or deferred —
  document the choice).
- Secure credential/token storage strategy (flag as its own decision; do not hard-code/log secrets).
- Anything post-connect (lobby etc.).

## 4. Prerequisites & toolchain

Deltas from the [Project toolchain baseline](README.md#project-toolchain-baseline):

- Requires 0018 merged. No new modules expected; registration methods are added to the bridge
  client / repositories (0016/0017) if not already present, and surfaced in `:feature:connect`. No
  toolchain/version changes; new libs (if any) are pinned catalog additions.

## 5. Design & approach

- **Capability-aware UI:** the connect flow asks whether the selected server allows registration
  (from server state relayed via the bridge) and shows the Register entry only when it does; a clear
  message otherwise.
- **Registration screen/VM:** collects the required fields, validates them, calls the bridge's
  proxied registration, and routes success back into sign-in/connect; failures render via the
  design-system error surfaces with specific messages.
- **Error taxonomy:** map the connect/auth outcomes to distinct UI states —
  `InvalidCredentials`, `RegistrationFailed(reason)`, `VersionUnsupported`, `Network`/`Timeout` —
  each with an appropriate retry/cancel affordance (`DecisionPrompt`).
- Reuse the design system throughout; never log/persist secrets.

## 6. Implementation steps

1. Surface server registration capability through the repositories (0016/0017) → connect flow.
2. Registration screen + ViewModel (fields, validation, submit, success → sign-in), capability-gated.
3. Implement the auth/version/network error taxonomy and its design-system surfaces + retry.
4. Turbine ViewModel tests for register success/failure, invalid credentials, version-unsupported,
   and network/timeout, using fake repositories.
5. `./gradlew check` green; `./gradlew :app:assembleDebug` builds.

## 7. Testing & verification

- **Hermetic gate:** ViewModel/state-machine tests (Turbine, fake repositories) for every
  registration and error path; `./gradlew check` green with no live bridge.
- **Live (deferred):** once the bridge + a local server run, exercise real registration/sign-in
  (opt-in), including a `authenticationActivated=false` server (no registration) vs one with it on.

```bash
./gradlew check
```

## 8. Acceptance criteria

- [ ] Registration is available in `:feature:connect` **only when the server supports it**, with a
      clear message when it doesn't; success routes back into sign-in/connect.
- [ ] Distinct, actionable surfaces exist for invalid credentials, registration failure,
      version-unsupported, and network/timeout — each with retry/cancel via the design system.
- [ ] `VersionUnsupported` is presented as its own first-class state, not a login error.
- [ ] Turbine tests cover every registration/error path (fake repositories); `./gradlew check` +
      `:app:assembleDebug` green; toolchain unchanged; no secrets logged/persisted.
- [ ] No post-connect feature work; secure-storage strategy is flagged, not improvised.

## 9. References

- [`../architecture.md`](../architecture.md) — proxied XMage auth; version-mismatch first-class.
- `docs/stories/0018-connect-and-sign-in-ui.md` — the sign-in flow this extends.
- `../mage/Mage.Common/src/main/java/mage/interfaces/MageServer.java` — `authRegister` / `authResetPassword` (server-side, relayed by the bridge).
- [`AGENTS.md`](../../AGENTS.md) — error modeling as UI state, accessibility, no secrets.
