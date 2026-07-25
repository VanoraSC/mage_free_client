# :app

The **Android application** module — the phone-native client entry point. Established as the
scaffold (story 0007) and given its **navigation shell** in story 0008: an Android Gradle Plugin
app with Jetpack Compose + Material 3 and Hilt, a single `Activity` hosting a Navigation-Compose
host with the four Arena-style top-level destinations (Home/Play, Decks, Profile/Social,
Settings) behind an adaptive bottom-bar ↔ nav-rail, plus a passing lint/unit gate and a buildable
debug APK.

It is deliberately **shell only** — the destinations are placeholder screens with no real content,
no design system, no features, no networking. Those arrive later: the home hub (0009),
connection status (0010) and immersive game route (0011); the design system in EPIC-03; features
in their own epics. `AppRoot` now hosts `AppShell` (the nav host + adaptive chrome).

## Layout

```
app/
├── build.gradle.kts
├── proguard-rules.pro
└── src/
    ├── main/
    │   ├── AndroidManifest.xml            # <application android:name=".MageApp"> + MainActivity (launcher)
    │   ├── kotlin/magefree/app/
    │   │   ├── MageApp.kt                 # @HiltAndroidApp Application (DI root)
    │   │   ├── MainActivity.kt            # @AndroidEntryPoint, enableEdgeToEdge(), setContent { MageTheme { AppRoot() } }
    │   │   ├── AppRoot.kt                 # root composable → hosts AppShell()
    │   │   ├── navigation/
    │   │   │   ├── TopLevelDestination.kt # @Serializable routes + the ordered destination model
    │   │   │   ├── MageNavHost.kt         # NavHost with type-safe composable<Route> entries
    │   │   │   └── AppShell.kt            # Scaffold: adaptive NavigationBar (compact) / NavigationRail
    │   │   ├── screens/                   # four placeholder destination composables
    │   │   └── theme/MageTheme.kt         # minimal MaterialTheme wrapper (default M3 light + dark schemes)
    │   └── res/                           # app name string + minimal launch-window themes (light + values-night)
    ├── test/kotlin/magefree/app/          # TopLevelDestinationTest — hermetic JVM unit test
    └── androidTest/kotlin/magefree/app/   # AppShellNavigationTest — Compose instrumented nav test (device only)
```

## Prerequisites

- **JDK 17** — set `JAVA_HOME` to a JDK 17 install (Gradle runs via the committed `./gradlew`
  wrapper; no local Gradle needed).
- **Android SDK** — install the SDK and point Gradle at it via **either**:
  - `ANDROID_HOME` (or `ANDROID_SDK_ROOT`) environment variable, **or**
  - a `local.properties` file in the repo root: `sdk.dir=/path/to/Android/Sdk`.

  `local.properties` is git-ignored and must **not** be committed. The build targets
  `compileSdk`/`targetSdk` 36 and `minSdk` 26, so SDK **platform 36** and matching **build-tools**
  must be installed. As noted for the JDK in story 0001, a missing/mismatched SDK is the most
  likely environment gap here — `assembleDebug` and instrumented tests will fail without it.

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
./gradlew :app:installDebug                 # build + install the debug APK; launches to the placeholder
./gradlew :app:connectedDebugAndroidTest    # run the Compose instrumented nav test (AppShellNavigationTest)
```

Per [`../AGENTS.md`](../AGENTS.md), do not assume a device is attached; the instrumented smoke
test is documented and opt-in, not part of the hermetic gate.
