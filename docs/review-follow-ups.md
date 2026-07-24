# Review Follow-ups

Non-blocking findings surfaced during code review of accepted work — things worth a **second
look** that did **not** block accepting the PR. This is a living backlog: add items as they
arise; when one is addressed, note the resolving PR and remove it.

Each item records **where it surfaced**, **what** it is, **why it's non-blocking**, and
**when/where to revisit**.

---

## Toolchain

### Kotlin 2.4.10 baseline is ahead of KSP / AGP support
- **Surfaced:** story 0007.
- **What:** The pinned Kotlin `2.4.10` has no matching KSP release yet. This forced
  `hilt { enableAggregatingTask = false }` in `app/build.gradle.kts` — routing Hilt aggregation
  through KSP because the legacy javac aggregating task reads Kotlin `@Metadata` via a bundled
  `kotlin-metadata-jvm` that only supports ≤ Kotlin 2.2. The same version chain also caps **AGP
  at 8.13.2** (AGP 9.x needs Gradle 9.5+, but the shared wrapper is 9.3.1) and androidx at the
  **pre-API-37** wave (`compose-bom 2025.09.01`, `core-ktx 1.16.0`, etc.).
- **Why non-blocking:** everything builds; `enableAggregatingTask = false` is a documented Hilt
  option, not a hack.
- **Revisit:** when KSP ships a Kotlin-2.4.x build — re-evaluate re-enabling the aggregating
  task and unblocking AGP 9.x / newer androidx / `compileSdk 37`. Also reconsider whether the
  Kotlin baseline should track KSP availability rather than lead it.

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

_Note: several items converge on the **EPIC-03 design pass** and the **Kotlin/KSP toolchain
version chain** — worth handling as themed cleanups rather than one-offs._
