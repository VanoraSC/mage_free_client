# 0011 — Immersive in-game mode shell

- **Epic:** EPIC-02 — App Shell & Navigation
- **Depends on:** 0008
- **Status:** ready

## 1. Objective

Add the **in-game experience as a separate, full-screen, immersive route** — edge-to-edge with
system bars hidden and **no** tabbed nav chrome — distinct from the browsing shell. Entering shows
an immersive placeholder game surface; exiting (system back or an on-screen control) restores the
normal shell and system bars. This story is the **container/mode**, not the board or gameplay
(EPIC-11+).

## 2. Context & background

- 0008 built the tabbed `AppShell` (bottom bar / rail). The game is deliberately **not** a tab —
  it is a focused, immersive surface entered from the app (for now, a stub entry point; the real
  entry is via lobby/table flows in EPIC-06/07).
- UX ([`../ux-principles.md`](../ux-principles.md)): the board is a focus view; the in-game mode
  should maximize screen, favor landscape, and remove browsing chrome. Interruptible: rotation and
  backgrounding must not crash the container.
- 0007 already enabled edge-to-edge; this story adds the immersive (hidden system bars) behavior
  scoped to the game route only.

## 3. Scope

**In scope**
- A distinct game route (type-safe, e.g. `GameRoute`) outside the top-level tab set, hosted so it
  covers the full screen without the bottom bar/rail.
- Immersive system-UI behavior **only while on the game route**: hide status/nav bars (via
  `WindowInsetsControllerCompat`), restore them on exit; handle back to leave the mode.
- A placeholder immersive game composable (full-bleed surface + a visible "exit" affordance).
- A stub entry point to navigate into the game route (e.g. a debug/dev action), since the real
  entry comes later.

**Out of scope**
- The actual board, zones, decisions, or any `GameView`/game data (EPIC-11+).
- Landscape lock/orientation policy decisions beyond allowing/where-appropriate (can be minimal).
- Real entry from lobby/table (EPIC-06/07).

## 4. Prerequisites & toolchain

Deltas from the [Project toolchain baseline](README.md#project-toolchain-baseline) and 0007/0008:

- Requires 0008 merged (nav host + shell).
- Uses `androidx.core:core-ktx` `WindowCompat`/`WindowInsetsControllerCompat` (part of the
  AndroidX core already on the Android classpath); no significant new dependency expected.

## 5. Design & approach

```
app/src/main/kotlin/magefree/app/game/
├── GameRoute.kt              # @Serializable route for the immersive mode
├── ImmersiveGameScreen.kt    # full-bleed placeholder + exit control
└── ImmersiveSystemUi.kt      # DisposableEffect helper: hide bars on enter, restore on exit
app/src/main/kotlin/magefree/app/navigation/
└── MageNavHost.kt            # + composable<GameRoute> hosted full-screen (no AppShell chrome)
```

- **Routing:** the `GameRoute` is registered so it renders **outside** the `AppShell` Scaffold
  (full screen, no bottom bar/rail). Options: host it at the top-level `NavHost` above/around the
  shell, or a nested host — choose the approach that cleanly excludes the tab chrome and document it.
- **Immersive system UI:** an `ImmersiveSystemUi()` composable using a `DisposableEffect` that, on
  enter, calls `WindowInsetsControllerCompat(window, view).hide(systemBars())` with
  `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`, and on dispose **restores** the bars — so the immersive
  state is strictly scoped to the game route and cleaned up on exit/backgrounding.
- **`ImmersiveGameScreen`:** a full-bleed `Surface` placeholder with an obvious, accessible exit
  control (and system back also exits). Content is a stub.
- **Entry stub:** a temporary way to reach `GameRoute` (e.g. a dev button on Home or Settings)
  clearly marked as a placeholder; replaced by real entry in later epics.
- **Resilience:** rotating/backgrounding while on the route must not crash; the bars restore on exit.

## 6. Implementation steps

1. Define `GameRoute` and register it in navigation so it renders full-screen without the tab chrome.
2. Implement `ImmersiveSystemUi()` (hide on enter, restore on dispose).
3. Implement `ImmersiveGameScreen` (full-bleed placeholder + exit control) using the helper.
4. Add a clearly-marked stub entry point to navigate into `GameRoute`.
5. Tests: a UI test (instrumented, or Robolectric where feasible) that entering the route shows the
   immersive placeholder without the bottom bar and that back/exit returns to the shell; a check
   that leaving the route restores system bars.
6. `./gradlew check` green; `:app:assembleDebug` builds.

## 7. Testing & verification

- **UI (opt-in on device):** entering `GameRoute` shows the full-bleed placeholder with **no** tab
  chrome and hidden system bars; exit/back returns to the shell and restores the bars; rotation on
  the route does not crash.
- **Hermetic gate:** whatever is JVM-testable (route wiring / helper logic) runs in `./gradlew check`.

```bash
./gradlew check
```

## 8. Acceptance criteria

- [ ] A distinct, type-safe `GameRoute` renders full-screen **without** the bottom bar/rail.
- [ ] While on the route, system bars are hidden (immersive); on exit/back/backgrounding they are
      **restored** — the immersive state is scoped to the route only.
- [ ] An accessible exit control and system back both leave the mode; rotating on the route does
      not crash.
- [ ] A clearly-marked stub entry point navigates into the game route (real entry deferred to later epics).
- [ ] `./gradlew check` passes hermetically; `:app:assembleDebug` builds.
- [ ] No board/zones/game data were added — placeholder only.

## 9. References

- [`../ux-principles.md`](../ux-principles.md) — the board as an immersive focus view; interruptible sessions.
- [`0007-android-app-module-scaffold.md`](0007-android-app-module-scaffold.md) — edge-to-edge setup.
- [`0008-navigation-shell-and-top-level-destinations.md`](0008-navigation-shell-and-top-level-destinations.md) — the shell this route sits outside of.
