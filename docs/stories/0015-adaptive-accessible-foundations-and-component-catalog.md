# 0015 — Adaptive & accessible foundations + component catalog

- **Epic:** EPIC-03 — Design System & Theming
- **Depends on:** 0013, 0014
- **Status:** ready

## 1. Objective

Complete the design system with **adaptive-layout foundations** (window-size-class helpers and a
shared **inset-ownership convention**), **accessibility foundations** (dynamic type / content
scaling verified across the system), and a developer **component catalog** — a gallery screen
that renders every design-system component across light/dark and window sizes for fast visual QA.
This closes out EPIC-03 and resolves several logged design-pass follow-ups.

## 2. Context & background

- Builds on 0012 (theme/tokens), 0013 (foundational components), 0014 (card components).
- UX direction ([`../ux-principles.md`](../ux-principles.md)): adaptive layouts (phone ↔
  tablet/foldable), respect dynamic type, one coherent system.
- **Resolves logged follow-ups** ([`review-follow-ups.md`](../review-follow-ups.md)):
  - *Inset double-counting between screens and the shell* — establish and document a single
    inset-ownership convention (shell owns chrome insets; screens own content insets) and provide
    a helper so screens apply it consistently. Fix the known `HomeScreen`/`AppShell` case.
  - *Window size class uses the older experimental API* — centralize size-class access here so a
    later migration to `currentWindowAdaptiveInfo()` is a one-place change.
  - *Nav items single vs paired icons* / *missing app launcher icon* — provide the design system's
    iconography/branding guidance/assets (or explicitly note which are deferred).

## 3. Scope

**In scope**
- **Adaptive helpers** in `:core:designsystem`: a single wrapper/utility for obtaining the
  `WindowSizeClass` (so callers don't each call the experimental API), and layout helpers/scaffold
  variants keyed to width class.
- A documented, reusable **inset-ownership convention** + a helper modifier; apply it to fix the
  `HomeScreen`/`AppShell` inset double-count.
- **Accessibility foundations**: verify components honor dynamic type / font scaling (no clipping
  at large scales); document the ≥48dp + content-description expectations as design-system rules;
  add font-scale previews.
- A dev **component catalog** screen (a debug destination or a separate debug entry) that lists
  every component in its states, across light/dark and simulated sizes.

**Out of scope**
- New components (0013/0014 delivered them) beyond what the catalog composes.
- Real app launcher icon artwork (may be tracked as its own branding task if not done here).
- Migrating away from the experimental window-size-class API (this story only **centralizes** it
  to make that a one-place change later).

## 4. Prerequisites & toolchain

Deltas from the [Project toolchain baseline](README.md#project-toolchain-baseline) and 0012–0014:

- Requires 0013 and 0014 merged.
- Uses the window-size-class artifact already in the catalog (from story 0008); no new deps
  expected. The catalog screen may live behind a debug entry in `:app` (reuse the 0011 dev-stub
  pattern) or as a preview-only surface in `:core:designsystem` — pick one and document it.

## 5. Design & approach

```
core/designsystem/src/main/kotlin/magefree/designsystem/
├── layout/
│   ├── WindowSize.kt        # single access point for WindowSizeClass / width class (wraps the experimental API)
│   ├── Insets.kt            # inset-ownership convention: a helper modifier (e.g. Modifier.contentInsets())
│   └── AdaptiveScaffold.kt  # optional layout helpers keyed to width class
└── catalog/
    └── ComponentCatalog.kt  # gallery composable rendering every component × states (light/dark, sizes)
app/…                        # a debug-only entry to the catalog (if hosted in :app); HomeScreen inset fix
```

- **`WindowSize`**: the one place that touches `WindowSizeClass` (currently the experimental
  `calculateFromSize`), so `AppShell` and any future caller obtain the size class from here — a
  later swap to `currentWindowAdaptiveInfo()` is then a single edit.
- **`Insets`**: define the convention — the **shell** consumes the system-bar/chrome insets (it
  already applies Scaffold `innerPadding`), and **screens** apply only content insets via a helper
  (`Modifier.contentInsets()` or similar). Update `HomeScreen` to use the convention instead of
  its own `safeDrawingPadding()` so bottom insets stop double-counting under the bottom bar.
- **Accessibility**: add font-scale (e.g. 1.0×/1.3×/2.0×) previews for representative components;
  ensure text/containers grow without clipping; write the design-system a11y rules into the module
  README/KDoc (≥48dp, content descriptions, no color-only state, dynamic type).
- **Component catalog**: a scrollable gallery grouping buttons, list rows, section chrome, state
  views, the decision prompt, the card tile, and the full card view — each shown across its main
  states; a light/dark toggle and size simulation. Reachable via a clearly-marked debug entry.

## 6. Implementation steps

1. Add `WindowSize` (single size-class access point) and refactor `AppShell` to use it.
2. Add the `Insets` convention + helper; fix `HomeScreen`'s inset handling; document the convention.
3. Add font-scale previews and verify components don't clip at large scales; write the a11y rules
   into the module docs.
4. Build the `ComponentCatalog` gallery and a debug-only entry point to it.
5. Update [`review-follow-ups.md`](../review-follow-ups.md): mark the inset, window-size-class, and
   icon items resolved (or note what remains).
6. `./gradlew check` green; `./gradlew :app:assembleDebug` builds.

## 7. Testing & verification

- **Hermetic gate:** `./gradlew check` passes; any pure logic (size-class thresholds, inset helper)
  unit-tested; no device.
- **Visual:** the catalog renders every component across light/dark and font scales without
  clipping; the `HomeScreen` bottom padding no longer double-counts under the bottom bar (preview
  or on-device).

```bash
./gradlew check
./gradlew :app:assembleDebug
```

## 8. Acceptance criteria

- [ ] A single `WindowSize` access point exists and `AppShell` uses it (size-class API centralized).
- [ ] An inset-ownership convention + helper is documented and applied; the `HomeScreen`/`AppShell`
      inset double-count is fixed.
- [ ] Components honor dynamic type / font scaling without clipping; design-system accessibility
      rules are documented; font-scale previews exist.
- [ ] A developer **component catalog** renders every design-system component across states and
      light/dark, reachable via a clearly-marked debug entry.
- [ ] The corresponding items in `review-follow-ups.md` are updated (resolved / remaining noted).
- [ ] `./gradlew check` passes hermetically; `:app:assembleDebug` builds; `:bridge` untouched.

## 9. References

- [`../ux-principles.md`](../ux-principles.md) — adaptive layouts, dynamic type, one coherent system.
- [`review-follow-ups.md`](../review-follow-ups.md) — the design-pass items this story resolves.
- [`0013-foundational-components.md`](0013-foundational-components.md), [`0014-card-forward-components.md`](0014-card-forward-components.md) — the components the catalog showcases.
- [`AGENTS.md`](../../AGENTS.md) — accessibility requirements, adaptive layouts.
