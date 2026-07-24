# Review Follow-ups

Non-blocking findings surfaced during code review of accepted work — things worth a **second
look** that did **not** block accepting the PR. This is a living backlog: add items as they
arise; when one is addressed, note the resolving PR and remove it.

Each item records **where it surfaced**, **what** it is, **why it's non-blocking**, and
**when/where to revisit**.

---

## Toolchain

### Toolchain baseline — locked & documented ✅ (corrected diagnosis)
- **Surfaced:** stories 0007–0010; **resolved** by the toolchain-alignment pass (PR #12).
- **Correction:** the earlier "Kotlin 2.4.10 has no matching KSP" framing was **wrong**. KSP has
  been **version-independent of Kotlin since 2.3.0** and supports Kotlin 2.2+ (incl. 2.4.10), so
  the KSP 2.3.10 / Kotlin 2.4.10 pairing is correct. `hilt { enableAggregatingTask = false }` is
  only about Hilt's *legacy* aggregating task's old metadata reader (≤ Kotlin 2.2) and is a
  standard, recommended Hilt setting — not a temporary workaround.
- **Outcome:** the toolchain is coherent (a clean `check + assembleDebug` builds green) and is now
  the **locked, authoritative baseline** with a story guardrail — see *Project toolchain baseline*
  in [`stories/README.md`](stories/README.md).
- **Remaining (deliberate future pass, not a story):** moving to AGP 9.x / newer androidx /
  `compileSdk 37` requires bumping the Gradle wrapper (9.3.1 → ≥ 9.5) and re-verifying the whole
  set together — do it as its own toolchain pass when there's a reason.

---

## Android UI / Compose

### Inset double-counting between screens and the shell
- **Surfaced:** story 0009.
- **What:** `HomeScreen` applies `safeDrawingPadding()` while the compact-width `AppShell` also
  passes the Scaffold's `innerPadding` to the nav host, so bottom insets can double-count in
  bottom-bar mode (slightly excess bottom padding).
- **Why non-blocking:** cosmetic only.
- **Revisit:** EPIC-03 design pass — establish a single **inset-ownership convention** (shell
  owns chrome insets; screens own content insets) and apply it consistently across screens.

### Window size class uses the older experimental API
- **Surfaced:** story 0008.
- **What:** `AppShell` uses `WindowSizeClass.calculateFromSize`
  (`ExperimentalMaterial3WindowSizeClassApi`). Newer Compose favors
  `currentWindowAdaptiveInfo()` / the `material3-adaptive` artifacts.
- **Why non-blocking:** works with the pinned Compose BOM.
- **Revisit:** on a Compose BOM bump — consider migrating to the `material3-adaptive` API.

### Nav items use a single icon (no selected/unselected pair)
- **Surfaced:** story 0008.
- **What:** the story mentioned selected/unselected icon pairs; the implementation uses one
  icon per destination (Material 3 nav items have a single icon slot).
- **Why non-blocking:** functional and accessible.
- **Revisit:** EPIC-03 — add filled/outlined icon pairs if the visual design calls for it.

### Missing application launcher icon
- **Surfaced:** story 0007.
- **What:** Android lint `MissingApplicationIcon` — the app uses the default system launcher icon.
- **Why non-blocking:** lint warning only.
- **Revisit:** EPIC-03 design system (branding / iconography).

---

## Build & dependencies

### Manual `concurrent-futures` version constraint
- **Surfaced:** story 0010.
- **What:** `app/build.gradle.kts` adds a `constraints { implementation(libs.androidx.concurrent.futures) }`
  (pinned 1.2.0) to align classpaths — the main runtime pulls `concurrent-futures 1.1.0`
  transitively (navigation → profileinstaller) while `androidx.test:core` (androidTest) needs
  1.2.0, and AGP's consistent resolution then makes the two conflict.
- **Why non-blocking:** the constraint resolves both classpaths cleanly and is documented inline.
- **Revisit:** as dependencies grow and on AGP/androidx bumps — the constraint may become
  unnecessary or need adjusting. If more of these appear, consider a shared dependency-alignment
  strategy rather than one-off constraints.

---

## Architecture

### `DispatcherProvider` lives in `:app`, not `:core:common`
- **Surfaced:** story 0010.
- **What:** `magefree.app.core.DispatcherProvider` was introduced in the `:app` module. The target
  module layout (`AGENTS.md`) puts shared utilities like dispatchers in `:core:common`.
- **Why non-blocking:** correct for now — modules are introduced as features need them, not
  scaffolded early; `:app` is the only consumer today.
- **Revisit:** when `:core:common` is introduced, relocate `DispatcherProvider` (and any other
  shared utils that accumulate in `:app/core`) there.

### Nested navigation hosts (root graph around the shell)
- **Surfaced:** story 0011.
- **What:** the root `AppNavHost` hosts `ShellRoute` (the tabbed `AppShell`, which has its **own**
  inner nav controller) plus `GameRoute` — two nested `NavHost`s, so the immersive game route can
  render outside the shell chrome.
- **Why non-blocking:** the correct, well-documented way to render the game with no bar/rail; it
  builds and the entry/exit flow is covered by tests.
- **Revisit:** when real game entry is wired (EPIC-06/07 from lobby/table) and if deep links or
  process-death state restoration are added — nested hosts need care for the cross-host back stack,
  deep-link routing, and saved state. Re-verify entry/exit and state behavior then.

---

## Bridge (server-side)

### Bridge binds to a hardcoded `0.0.0.0`
- **Surfaced:** story 0001 (reviewed retroactively; PR #3, already merged).
- **What:** `bridge/src/main/kotlin/magefree/bridge/Application.kt` starts Netty with
  `host = "0.0.0.0"` (all interfaces). The port is config-driven (`application.conf` /
  `BRIDGE_PORT`), but the bind address is a literal.
- **Why non-blocking:** correct/convenient for a local dev scaffold whose only route is `/health`.
- **Revisit:** before the bridge carries real traffic — make the bind address configurable and
  make network exposure a deliberate decision, and pair it with the WebSocket endpoint's
  transport security (TLS) and auth as those land (EPIC-01 stories 0004–0005 / deployment).

### Hand-rolled `main()` instead of Ktor `EngineMain`
- **Surfaced:** story 0001 (PR #3, already merged).
- **What:** `main()` manually loads `application.conf`, reads the port, and calls
  `embeddedServer(Netty, port) { module() }`, rather than using Ktor's config-driven `EngineMain`.
- **Why non-blocking:** works and is explicit; chosen to satisfy the story's simultaneous asks
  (hand-written `main()` + `application.conf` port + `mainClass`).
- **Revisit:** low priority — consider migrating to `EngineMain` for idiomatic, config-driven
  startup as bridge configuration grows (engines, connectors, modules declared in config).

---

_Note: several items converge on the **EPIC-03 design pass** and the **Kotlin/KSP toolchain
version chain** — worth handling as themed cleanups rather than one-offs._
