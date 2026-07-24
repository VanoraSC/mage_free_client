# 0007 — Android app module scaffold

- **Epic:** EPIC-02 — App Shell & Navigation
- **Depends on:** none within Epic 2 (first Android story; builds on the existing monorepo Gradle setup from 0001)
- **Status:** ready

## 1. Objective

Bring the **Android application into existence** in the monorepo: a `:app` module using the
Android Gradle Plugin, Jetpack Compose + Material 3, and Hilt, with a single `Activity` hosting
a Compose root that renders a placeholder. It must build a debug APK and pass the lint/unit
gate. This is the Android analog of 0001 — **foundation only: no navigation, no design system,
no features, no networking** (those are 0008+, EPIC-03, and later epics).

## 2. Context & background

- Monorepo already has (from 0001): the root Gradle build, `settings.gradle.kts`, the version
  catalog `gradle/libs.versions.toml`, the Gradle wrapper, and the `:bridge` module. This story
  adds `:app` alongside `:bridge` — do not disturb `:bridge`.
- Stack defaults ([`AGENTS.md`](../../AGENTS.md)): **Kotlin + Jetpack Compose + Material 3**,
  **MVVM/MVI** unidirectional state, **Hilt** DI, Coroutines/Flow, version-catalog-pinned
  versions, ktlint/detekt clean, accessibility as a requirement (≥48dp targets, content
  descriptions). Target module layout there: `:app` is thin (DI wiring, nav host, entry);
  `:core:*` and `:feature:*` arrive with their features — **do not scaffold them empty here**.
- UX direction ([`../ux-principles.md`](../ux-principles.md)): touch-first, dark-mode support,
  adaptive layouts. Only the minimal theme is set up here.

## 3. Scope

**In scope**
- A `:app` Android application module: AGP, Kotlin, Compose (BOM + compiler via the Kotlin
  Compose plugin), Material 3, Hilt (KSP), edge-to-edge enabled.
- `@HiltAndroidApp` `Application`, a single `@AndroidEntryPoint` `MainActivity` with
  `setContent { … }`, a minimal `MageTheme` wrapping `MaterialTheme` (default M3 color schemes,
  light + dark), and a placeholder root composable.
- Catalog additions for AGP, Compose, Hilt, KSP, activity-compose, lifecycle, and test libs.
- A JVM unit test (trivial, proves the unit-test toolchain) and an instrumented Compose smoke
  test (proves the app renders on a device/emulator — documented, not part of the hermetic gate).
- App/module `README` notes for build/run/test.

**Out of scope**
- Navigation and any destinations (0008).
- The design system / real theme and components (EPIC-03).
- Any `:core:*`/`:feature:*` modules, ViewModels beyond none-needed, or networking.
- Play Store/signing/release config.

## 4. Prerequisites & toolchain

Deltas from the [Project toolchain baseline](README.md#project-toolchain-baseline):

- **Android SDK required.** Set `ANDROID_HOME` (or `sdk.dir` in `local.properties`, which must
  **not** be committed — add to `.gitignore`). Install the platform for the chosen `compileSdk`
  and build-tools. Note in the story README that `assembleDebug`/instrumented tests need the SDK,
  and that this may surface an environment gap the way the JDK did for 0001.
- **JDK 17** (baseline) — AGP requires a compatible JDK; 17 is fine.
- **Pin explicit versions in the catalog** (confirm current stable at implementation time), e.g.:
  AGP (e.g. 8.x), `compileSdk`/`targetSdk` = current stable (e.g. 36), `minSdk` = 26, Compose
  via `androidx.compose:compose-bom`, the Kotlin Compose compiler plugin (matches the Kotlin
  version, 2.4.10 from baseline), Hilt (e.g. 2.5x) + KSP (matching Kotlin), activity-compose,
  lifecycle-runtime-compose. Avoid "latest" — pin.
- An emulator or device is needed only for the instrumented smoke test; the default
  `./gradlew check` must not require one.

## 5. Design & approach

```
settings.gradle.kts        # + include(":app")
gradle/libs.versions.toml  # + AGP, compose-bom, kotlin-compose plugin, hilt, ksp, activity/lifecycle, test libs
app/
├── build.gradle.kts       # com.android.application + kotlin.android + kotlin.compose + hilt + ksp
├── README.md              # SDK prereqs, build/run/test commands
├── src/main/AndroidManifest.xml           # <application android:name=".MageApp"> + MainActivity (launcher)
├── src/main/kotlin/magefree/app/
│   ├── MageApp.kt                          # @HiltAndroidApp Application
│   ├── MainActivity.kt                     # @AndroidEntryPoint, enableEdgeToEdge(), setContent { MageTheme { AppRoot() } }
│   ├── AppRoot.kt                          # placeholder root composable (replaced by nav host in 0008)
│   └── theme/MageTheme.kt                  # minimal MaterialTheme wrapper (light + dark default M3 schemes)
├── src/test/kotlin/magefree/app/           # a trivial JVM unit test
└── src/androidTest/kotlin/magefree/app/    # Compose smoke test: AppRoot placeholder renders
```

- **Gradle plugins** applied in `:app`: `com.android.application`, `org.jetbrains.kotlin.android`,
  `org.jetbrains.kotlin.plugin.compose`, `com.google.dagger.hilt.android`,
  `com.google.devtools.ksp`. All versions via the catalog; register plugin ids in the root
  `build.gradle.kts` with `apply false` as needed.
- **Hilt** wired minimally: `MageApp` annotated `@HiltAndroidApp`; `MainActivity`
  `@AndroidEntryPoint`. No modules/injections needed yet — this just proves the DI graph builds.
- **`MageTheme`**: `MaterialTheme` with `lightColorScheme()`/`darkColorScheme()` chosen by
  `isSystemInDarkTheme()`; dynamic color optional. This is a placeholder for EPIC-03.
- **`AppRoot`**: a `Surface`/`Scaffold` showing placeholder text (e.g. "Mage Free Client"). It
  is intentionally the seam 0008 replaces with the navigation host.
- **Edge-to-edge** enabled in `MainActivity` (`enableEdgeToEdge()`), consistent with the later
  immersive game mode (0011).

## 6. Implementation steps

1. Add the Android/Compose/Hilt/KSP entries to `gradle/libs.versions.toml`; register plugin ids
   in the root `build.gradle.kts` (`apply false`).
2. `include(":app")` in `settings.gradle.kts`; add `.gitignore` entries for `local.properties`
   and Android build outputs.
3. Create `app/build.gradle.kts` (application id `magefree.app`, `compileSdk`/`minSdk`/`targetSdk`,
   Compose enabled, Hilt+KSP, Java/Kotlin 17, `buildFeatures.compose = true`, dependencies from
   the catalog, `testInstrumentationRunner` for Compose tests).
4. Add `AndroidManifest.xml`, `MageApp`, `MainActivity`, `AppRoot`, `MageTheme`.
5. Add a trivial unit test and a Compose instrumented smoke test (asserts the placeholder text).
6. Write `app/README.md` (Android SDK prereq, `assembleDebug`, `installDebug`, `check`,
   instrumented-test note).
7. Verify `./gradlew :app:assembleDebug` builds and `./gradlew check` (lint + unit, both `:app`
   and `:bridge`) passes with no device.

## 7. Testing & verification

- **Hermetic gate:** `./gradlew check` passes (ktlint/lint + JVM unit tests) with **no device**.
- **Build:** `./gradlew :app:assembleDebug` produces a debug APK.
- **On device (documented, opt-in):** `./gradlew :app:installDebug` launches the app showing the
  placeholder; the Compose instrumented smoke test passes on a running emulator/device
  (`./gradlew :app:connectedDebugAndroidTest`). Per [`AGENTS.md`](../../AGENTS.md), do not assume
  a device is attached.

```bash
./gradlew check
./gradlew :app:assembleDebug
```

## 8. Acceptance criteria

- [ ] `:app` builds: `./gradlew :app:assembleDebug` produces a debug APK.
- [ ] `./gradlew check` passes hermetically (lint + unit for `:app` and `:bridge`, no device).
- [ ] The app launches to a placeholder root composable inside `MageTheme` (light + dark), with
      edge-to-edge enabled; Hilt graph builds (`@HiltAndroidApp` + `@AndroidEntryPoint`).
- [ ] All new versions are pinned in `gradle/libs.versions.toml`; none hard-coded; `local.properties`
      and Android build outputs are git-ignored.
- [ ] No `:core:*`/`:feature:*` modules, no navigation, no design-system work, no networking were added.
- [ ] `app/README.md` documents SDK prereqs and build/run/test.

## 9. References

- [`AGENTS.md`](../../AGENTS.md) — Android stack, module rules, accessibility, git workflow.
- [`../ux-principles.md`](../ux-principles.md) — touch-first, dark mode, adaptive layouts.
- [`0001-bridge-module-scaffold.md`](0001-bridge-module-scaffold.md) — the JVM analog + the existing Gradle setup this extends.
- [Project toolchain baseline](README.md#project-toolchain-baseline).
