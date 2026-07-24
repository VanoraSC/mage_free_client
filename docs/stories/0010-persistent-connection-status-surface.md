# 0010 — Persistent connection-status surface

- **Epic:** EPIC-02 — App Shell & Navigation
- **Depends on:** 0008
- **Status:** ready

## 1. Objective

Give the app shell an **always-visible connection-status indicator** that reflects a
`ConnectionState`, present across every top-level destination. It is driven by a **state holder**
over a **stub source** now; the real wiring to the bridge session (EPIC-04/05) plugs into the
same seam later without touching the UI.

## 2. Context & background

- 0008 provides `AppShell` (the Scaffold hosting the nav chrome + NavHost) — the natural home for
  a shell-wide status surface.
- UX ([`../ux-principles.md`](../ux-principles.md)): the connection is the product on mobile;
  "am I connected?" must always be answerable at a glance. Interruptible sessions and reconnection
  are first-class.
- Conceptually mirrors the bridge's session states
  ([`../architecture.md`](../architecture.md); story 0005 `SessionStateCode`:
  CONNECTING/CONNECTED/AUTH_FAILED/VERSION_UNSUPPORTED/DISCONNECTED/RECONNECTING) but this is an
  **app-side** model. Do not import bridge/protocol types here; define an app `ConnectionState`.

## 3. Scope

**In scope**
- An app-side `ConnectionState` model and a `ConnectionStatusRepository`/holder exposing it as
  `StateFlow<ConnectionState>`, backed by a **stub/fake** source that can be driven in tests and
  (optionally) toggled in a debug affordance.
- A `ConnectionStatusBar`/chip composable rendered in `AppShell` so it is visible on all top-level
  destinations, with distinct, accessible presentation per state (color + text + content description).
- A `ConnectionStatusViewModel` (or equivalent) exposing immutable UI state to the composable.

**Out of scope**
- Any real connection to the bridge/server (EPIC-04 does the wiring; this story only defines the
  seam + stub).
- The in-game variant of the surface (0011 / game epics).
- Retry/reconnect actions with real effect (a button may exist but calls a stub).

## 4. Prerequisites & toolchain

Deltas from the [Project toolchain baseline](README.md#project-toolchain-baseline) and 0007/0008:

- Requires 0008 merged (`AppShell`).
- Uses Hilt (from 0007) to provide the repository/holder; add `androidx.lifecycle:lifecycle-viewmodel-compose`
  and Hilt-navigation-compose if not already present (pin in the catalog). For tests: Turbine +
  coroutines-test (baseline) for `Flow` assertions.

## 5. Design & approach

```
app/src/main/kotlin/magefree/app/connection/
├── ConnectionState.kt              # sealed/enum: Disconnected, Connecting, Connected, Reconnecting, Unsupported, AuthFailed
├── ConnectionStatusSource.kt       # interface: val state: StateFlow<ConnectionState>
├── StubConnectionStatusSource.kt   # emits/lets tests drive states (default source for now)
├── ConnectionStatusViewModel.kt    # maps ConnectionState -> immutable UI state (StateFlow)
└── ui/ConnectionStatusBar.kt       # composable: color + label + content description per state
app/src/main/kotlin/magefree/app/di/
└── ConnectionModule.kt             # Hilt: binds ConnectionStatusSource -> Stub (for now)
```

- **`ConnectionState`**: an app-owned type (not the bridge's). Include the states the shell needs
  to show; keep it independent so EPIC-04 maps the real session state into it.
- **Seam:** `ConnectionStatusSource` is the boundary EPIC-04 later re-implements against the real
  session; the Hilt binding swaps `Stub` → real without UI changes.
- **`ConnectionStatusViewModel`**: exposes `StateFlow<ConnectionStatusUiState>` (label, semantic
  color/role, whether a retry affordance shows). Uses an injected `DispatcherProvider` per AGENTS.
- **`ConnectionStatusBar`**: placed in `AppShell` (e.g. a slim strip under the top of content or
  in the app bar) so it persists across destinations. Each state is visually distinct **and**
  labelled with a content description (don't rely on color alone — accessibility).

## 6. Implementation steps

1. Define `ConnectionState`, `ConnectionStatusSource`, and `StubConnectionStatusSource` (driveable).
2. Add the Hilt `ConnectionModule` binding the stub source.
3. Implement `ConnectionStatusViewModel` mapping state → UI state.
4. Implement `ConnectionStatusBar` and mount it in `AppShell` so it shows on all destinations;
   distinct + accessible per state; light + dark previews.
5. Tests: a Turbine test over the ViewModel driving the stub through each state and asserting the
   UI state; a Compose UI test that the bar is visible across destinations and shows the right
   label/description for a couple of states.
6. `./gradlew check` green; `:app:assembleDebug` builds.

## 7. Testing & verification

- **Hermetic gate:** Turbine + coroutines-test over the ViewModel (all states); `./gradlew check` passes.
- **UI (opt-in/Robolectric):** the status bar is present on each top-level destination and reflects
  driven states with correct text + content description.

```bash
./gradlew check
```

## 8. Acceptance criteria

- [ ] An app-side `ConnectionState` and a `ConnectionStatusSource` seam exist, backed by a
      driveable stub bound via Hilt (no bridge/protocol types imported).
- [ ] A `ConnectionStatusBar` is visible across **all** top-level destinations and presents each
      state distinctly, with a content description (not color-only).
- [ ] A `ConnectionStatusViewModel` exposes immutable UI state; a Turbine test covers every state.
- [ ] `./gradlew check` passes hermetically; `:app:assembleDebug` builds; new deps pinned.
- [ ] No real connection logic is added — only the seam + stub (EPIC-04 wires reality).

## 9. References

- [`../ux-principles.md`](../ux-principles.md) — "the connection is the product," always-visible status.
- [`../architecture.md`](../architecture.md) — session states the app model conceptually mirrors.
- [`0008-navigation-shell-and-top-level-destinations.md`](0008-navigation-shell-and-top-level-destinations.md) — `AppShell` host.
- [`AGENTS.md`](../../AGENTS.md) — MVVM/StateFlow, DispatcherProvider, Turbine, accessibility.
