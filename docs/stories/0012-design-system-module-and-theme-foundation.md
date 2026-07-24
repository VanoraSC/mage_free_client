# 0012 — Design system module & theme foundation

- **Epic:** EPIC-03 — Design System & Theming
- **Depends on:** 0007
- **Status:** ready

## 1. Objective

Create the shared **`:core:designsystem`** module and the project's real **theme** — brand color
schemes (light + dark), typography, shape, and design tokens (spacing, elevation, sizing) — and
migrate `:app` off the minimal placeholder `MageTheme` from story 0007. This is the visual
foundation every later component and screen builds on. **Foundation only: no components beyond
the theme, no feature/screen restyling** (0013–0015 add components; screens adopt them as they
are built).

## 2. Context & background

- Story 0007 created `:app` with a **placeholder** `MageTheme` (`magefree.app.theme.MageTheme`)
  wrapping `MaterialTheme` with default M3 schemes. This story provides the real, branded theme
  and relocates it to a shared module so both the app and the design-system components consume it.
- Target module layout ([`AGENTS.md`](../../AGENTS.md)): **`:core:designsystem`** holds "theme,
  Material3 tokens, shared Composables." This is the first `:core:*` module.
- UX direction ([`../ux-principles.md`](../ux-principles.md)): **one coherent visual system**,
  deliberate (not defaulted) theme, full dark mode, adaptive layouts, accessibility (dynamic
  type, ≥48dp) — set the foundations here so the whole app inherits them.
- Because the migration is a theme swap, the existing screens (0008–0011) keep working and pick
  up the new theme automatically.

## 3. Scope

**In scope**
- A `:core:designsystem` **Android library** module (Compose enabled) in the monorepo.
- Brand `ColorScheme`s (light + dark), `Typography`, `Shapes`, and a **design-token** set
  (spacing, elevation, corner/size scales) as the single source of visual truth.
- The real `MageTheme` in `:core:designsystem` (replacing the `:app` placeholder), applying the
  schemes via `isSystemInDarkTheme()`; a documented decision on **dynamic color** (on/off).
- Migrate `:app` to depend on `:core:designsystem` and use its `MageTheme`; remove the
  placeholder.
- Light + dark theme previews demonstrating the palette/type/shape.

**Out of scope**
- Any reusable components beyond the theme (buttons, rows, prompt, cards) — **0013/0014**.
- Adaptive-layout helpers, the component catalog, dynamic-type tuning — **0015**.
- Restyling existing feature screens beyond what the theme swap does automatically.
- Real branding assets/app launcher icon (can be tracked separately; see review-follow-ups).

## 4. Prerequisites & toolchain

Deltas from the [Project toolchain baseline](README.md#project-toolchain-baseline) and 0007:

- Requires 0007 merged (the `:app` module, AGP/Compose/Hilt setup, version catalog entries).
- New module is an **Android library**: apply the **`magefree.android.library`** and
  **`magefree.android.compose`** convention plugins (`build-logic/`). Do **not** hand-write AGP /
  SDK / Compose config — that is exactly what the conventions exist to prevent (see the toolchain
  baseline). No `magefree.hilt` — the design system is pure UI.
- Reuse the existing catalog versions; SDK/Java/Kotlin and the Compose BOM come from the
  conventions. Android SDK + `compileSdk 36` as in `:app`.

## 5. Design & approach

```
settings.gradle.kts                    # + include(":core:designsystem")
core/designsystem/
├── build.gradle.kts                   # applies magefree.android.library + magefree.android.compose
└── src/main/kotlin/magefree/designsystem/theme/
    ├── Color.kt                       # brand light/dark ColorSchemes (+ palette)
    ├── Type.kt                        # Typography
    ├── Shape.kt                       # Shapes
    ├── Tokens.kt                      # spacing / elevation / size scales (Dp constants or a tokens object)
    └── MageTheme.kt                   # MaterialTheme wrapper (light/dark; dynamic-color decision)
app/…                                  # migrated: import magefree.designsystem.theme.MageTheme
```

- **`:core:designsystem`** applies the **`magefree.android.library`** + **`magefree.android.compose`**
  conventions (SDK/Java/Kotlin/Compose/BOM all come from there) and declares only `material3` (plus
  any other Compose libs it uses). No Hilt — pure UI. Namespace `magefree.designsystem`.
- **Theme**: `MageTheme(darkTheme = isSystemInDarkTheme(), dynamicColor = <decision>, content)`.
  Define real brand `lightColorScheme(...)`/`darkColorScheme(...)`, a `Typography`, and `Shapes`.
  Document the dynamic-color choice (default: **off**, for a deliberate, consistent brand look —
  the UX calls for a chosen theme, not defaulted).
- **Tokens**: a small, named set (e.g. `Spacing.small/medium/large`, elevation and size scales)
  exposed for components to use instead of magic numbers.
- **Migration**: `:app` adds `implementation(project(":core:designsystem"))`; every
  `magefree.app.theme.MageTheme` reference (MainActivity, previews across 0008–0011 screens) is
  repointed to `magefree.designsystem.theme.MageTheme`; the placeholder theme is deleted.

## 6. Implementation steps

1. Create `:core:designsystem` with a `build.gradle.kts` that applies the `magefree.android.library`
   + `magefree.android.compose` conventions, sets `namespace = "magefree.designsystem"`, and adds
   `material3` (the Compose BOM comes from the convention).
2. `include(":core:designsystem")` in `settings.gradle.kts`.
3. Implement `Color`, `Type`, `Shape`, `Tokens`, and `MageTheme` (branded light + dark; document
   dynamic-color decision); add light/dark theme previews.
4. Migrate `:app` to depend on the module and use the new `MageTheme`; delete the placeholder;
   update all imports (MainActivity + screen previews).
5. Verify the app still builds/renders and the existing screens inherit the new theme.
6. `./gradlew check` green; `./gradlew :app:assembleDebug` builds.

## 7. Testing & verification

- **Hermetic gate:** `./gradlew check` passes (`:core:designsystem` lint + any unit tests, `:app`,
  `:bridge`); no device.
- **Build:** `./gradlew :app:assembleDebug` produces a debug APK using the new theme.
- **Visual:** light + dark theme previews render the brand palette/type/shape. (Compose UI is
  preview-verified; on-device is opt-in.)

```bash
./gradlew check
./gradlew :app:assembleDebug
```

## 8. Acceptance criteria

- [ ] `:core:designsystem` exists as an Android library with Compose; namespace `magefree.designsystem`.
- [ ] Branded light + dark `ColorScheme`s, `Typography`, `Shapes`, and a design-token set are defined.
- [ ] The real `MageTheme` lives in `:core:designsystem`; the dynamic-color decision is documented.
- [ ] `:app` uses `magefree.designsystem.theme.MageTheme`; the `:app` placeholder theme is deleted;
      all references updated; existing screens still render (now themed).
- [ ] All versions pinned in the catalog; none hard-coded; `:bridge` untouched.
- [ ] `./gradlew check` passes hermetically; `:app:assembleDebug` builds.
- [ ] No reusable components (buttons/rows/prompt/cards), catalog, or adaptive helpers were added.

## 9. References

- [`AGENTS.md`](../../AGENTS.md) — `:core:designsystem`, Compose/Material 3, accessibility.
- [`../ux-principles.md`](../ux-principles.md) — one coherent visual system, deliberate theme, dark mode.
- [`0007-android-app-module-scaffold.md`](0007-android-app-module-scaffold.md) — the placeholder `MageTheme` this replaces and the module/toolchain setup to mirror.
- [Project toolchain baseline](README.md#project-toolchain-baseline).
