# :app

The **Android application** module — the phone-native client entry point. An Android Gradle Plugin
app with Jetpack Compose and Material 3, a single `Activity`, and the Koin container that assembles
the whole dependency graph.

It owns the **shell**, not the features. `AppRoot` hosts `AppShell` — the navigation host plus the
adaptive bottom-bar ↔ nav-rail chrome over the four top-level destinations (Home/Play, Decks,
Profile/Social, Settings) — and the type-safe navigation graph that wires each `:feature:*` module's
route in. Screens, game logic and networking live in `:feature:*` and `:core:*`; what is here is the
Activity, the graph, the routes, the connection status bar, the immersive game route and the debug
component catalog.

## Layout

```
app/
├── build.gradle.kts
├── proguard-rules.pro
└── src/
    ├── main/
    │   ├── AndroidManifest.xml            # <application android:name=".MageApp"> + MainActivity (launcher)
    │   ├── kotlin/magefree/app/
    │   │   ├── MageApp.kt                 # Application; starts Koin with the explicit module list
    │   │   ├── MainActivity.kt            # enableEdgeToEdge(), setContent { MageTheme { AppRoot() } }
    │   │   ├── AppRoot.kt                 # root composable → hosts AppShell()
    │   │   ├── di/                        # the module list itself, and the connection bindings
    │   │   ├── navigation/
    │   │   │   ├── TopLevelDestination.kt # @Serializable routes + the ordered destination model
    │   │   │   ├── AppNavHost.kt          # the root graph: connect flow, decks, cards, the shell
    │   │   │   ├── MageNavHost.kt         # the in-shell graph of top-level destinations
    │   │   │   └── AppShell.kt            # Scaffold: adaptive NavigationBar (compact) / NavigationRail
    │   │   ├── connection/                # session + connection status, and the status bar
    │   │   ├── game/                      # the immersive game route and its system-UI handling
    │   │   ├── catalog/                   # debug entry into the design system's component catalog
    │   │   └── screens/                   # the destinations the shell renders directly
    │   └── res/                           # app name string + launch-window themes (light + values-night)
    ├── test/                              # hermetic JVM unit tests
    ├── testDebug/                         # Robolectric + Compose tests (graph resolution, route wiring)
    └── androidTest/                       # Compose instrumented tests (device only)
```

## Prerequisites

- **JDK 17** — set `JAVA_HOME` to a JDK 17 install (Gradle runs via the committed `./gradlew`
  wrapper; no local Gradle needed).
- **Android SDK** — install the SDK and point Gradle at it via **either**:
  - `ANDROID_HOME` (or `ANDROID_SDK_ROOT`) environment variable, **or**
  - a `local.properties` file in the repo root: `sdk.dir=/path/to/Android/Sdk`.

  `local.properties` is git-ignored and must **not** be committed. The build targets
  `compileSdk`/`targetSdk` 36 and `minSdk` 26, so SDK **platform 36** and matching **build-tools**
  must be installed. Like the JDK, a missing or mismatched SDK is the most likely environment gap
  here — `assembleDebug` and instrumented tests fail without it.

## Build, run & test

```bash
# Build the debug APK -> app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleDebug

# The hermetic gate: ktlint + Android lint + JVM unit tests (NO device required)
./gradlew check

# JVM unit tests only
./gradlew :app:testDebugUnitTest

# Kotlin lint only / auto-fix
./gradlew :app:ktlintCheck
./gradlew :app:ktlintFormat
```

### On a device or emulator (opt-in — requires a running device)

`./gradlew check` never needs a device. The following do — start an emulator or attach a phone
first (`adb devices` to confirm), then:

```bash
./gradlew :app:installDebug                 # build + install the debug APK
./gradlew :app:connectedDebugAndroidTest    # run the Compose instrumented tests
```

Per [`../AGENTS.md`](../AGENTS.md), do not assume a device is attached; instrumented tests are
documented and opt-in, not part of the hermetic gate.
