# 0018 — Connect & sign-in UI

- **Epic:** EPIC-04 — Server Connection & Sign-In
- **Depends on:** 0017 (connection/server repositories), EPIC-03 (design system)
- **Status:** blocked on 0017

## 1. Objective

Build the **`:feature:connect`** module: the screens for choosing/adding a server, signing in,
and seeing live connection state (connecting / connected / auth-failed / version-unsupported /
disconnected). This is the player-facing front door to the app, built on the design system, with
the connect flow driven by the repositories from 0017.

## 2. Context & background

- 0017 provides the `ConnectionRepository` (session lifecycle + `ConnectionState`) and
  `ServerRepository` (persisted server list). This story is the UI over them.
- Design system (EPIC-03): use `MageTheme`, the button hierarchy, list rows, section chrome, state
  views, and the **`DecisionPrompt`** where a clear choice is needed. Don't hand-roll styles.
- UX ([`../ux-principles.md`](../ux-principles.md)): the connection is the product — connection
  state must be legible and reconnection graceful; thumb-reachable primary actions; ≥48dp.
- **Credential handling:** never log or persist passwords in plaintext. Passwords are entered per
  sign-in; token/secure storage (if any) is a deliberate later decision — flag, don't improvise.

## 3. Scope

**In scope** (all in `:feature:connect`, MVVM with immutable UI state):
- **Server list / add-server**: list persisted `ServerTarget`s (from 0017), add/edit/remove a
  server (name/host/port), pick one to connect to.
- **Sign-in**: username + password entry for the selected server; a prominent Connect action;
  form validation and disabled/loading states.
- **Connection-state UI**: reflect `ConnectionState` — a connecting/progress state, a success path
  (into the app), and clear, distinct failure surfaces for `AuthFailed` and `VersionUnsupported`
  (the latter first-class, with the server/bridge versions if available), plus reconnect/retry.
- ViewModels exposing `StateFlow<XxxUiState>`; events up via function calls; stateless previewable
  Composables (light + dark).

**Out of scope**
- Account **registration** and the deep auth-error/retry policy (**0019**).
- The bridge/network internals (owned by 0016/0017).
- Post-connect navigation targets (lobby etc.) beyond handing off to the existing shell.

## 4. Prerequisites & toolchain

Deltas from the [Project toolchain baseline](README.md#project-toolchain-baseline):

- Requires 0017 + EPIC-03 merged. New `:feature:connect` applies `magefree.android.library`
  + `magefree.android.compose` (+ `magefree.hilt`); depends on `:core:designsystem`, `:core:model`,
  and the connect repositories. No toolchain/version changes.

## 5. Design & approach

```
feature/connect/
├── ServerListScreen.kt + ServerListViewModel.kt    # list/add/remove/pick servers
├── AddServerScreen.kt (or dialog)                  # name/host/port entry + validation
├── SignInScreen.kt + SignInViewModel.kt            # credentials + Connect; drives ConnectionRepository.connect
└── ConnectionStatus*.kt                            # progress + auth-failed + version-unsupported surfaces
```

- Screens are stateless/previewable; ViewModels hold `StateFlow<UiState>` and call the 0017
  repositories. Sign-in's Connect calls `ConnectionRepository.connect(server, credentials)` and the
  UI renders the resulting `ConnectionState`.
- Failure surfaces use the design-system error/state views (and `DecisionPrompt` for a
  retry/cancel choice); `VersionUnsupported` gets its own clear message, distinct from `AuthFailed`.
- The already-shell-wide `ConnectionStatusBar` (0010, now real via 0017) continues to show status
  globally; this feature owns the dedicated connect/sign-in flow.

## 6. Implementation steps

1. Create `:feature:connect` (conventions; depends on designsystem/model/repositories).
2. Server list + add-server screens/VMs over `ServerRepository`.
3. Sign-in screen/VM driving `ConnectionRepository.connect`; validation + loading/disabled states.
4. Connection-state surfaces (progress / auth-failed / version-unsupported / retry) via the design system.
5. Compose UI tests (or fakes) for the key flows; ViewModel Turbine tests against fake repositories.
6. `./gradlew check` green; `./gradlew :app:assembleDebug` builds.

## 7. Testing & verification

- **Hermetic gate:** ViewModel tests (Turbine) over the connect/sign-in state machines using fake
  repositories (which use `FakeBridgeClient`); design-system previews render. `./gradlew check` green.
- **UI (opt-in):** Compose UI tests for server-add, sign-in submit, and each connection-state surface.

```bash
./gradlew check
```

## 8. Acceptance criteria

- [ ] `:feature:connect` provides server list / add-server, sign-in, and connection-state screens,
      MVVM with immutable `StateFlow` UI state and stateless previewable Composables (light + dark).
- [ ] Sign-in drives `ConnectionRepository.connect`; the UI renders `Connecting`/`Connected` and
      distinct `AuthFailed` vs `VersionUnsupported` surfaces with retry.
- [ ] Built on the design system (theme, components, `DecisionPrompt`); ≥48dp; accessible; passwords
      never logged/persisted in plaintext.
- [ ] Turbine ViewModel tests pass hermetically (fake repositories); `./gradlew check` +
      `:app:assembleDebug` green; toolchain unchanged.
- [ ] No registration flow, no bridge/network internals, no post-connect feature screens.

## 9. References

- `docs/stories/0017-connection-repository-and-live-status-wiring.md` — the repositories this UI drives.
- [`../ux-principles.md`](../ux-principles.md) — connection legibility, thumb reach, graceful reconnect.
- [`AGENTS.md`](../../AGENTS.md) — MVVM/StateFlow, stateless Composables, accessibility, no secrets.
