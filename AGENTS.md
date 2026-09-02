# Engineering Standards & Agent Instructions

This is the canonical guidance for anyone — human or agent — writing code in this repo.
`CLAUDE.md` imports this file. Read [`README.md`](README.md),
[`docs/architecture.md`](docs/architecture.md), and [`docs/ux-principles.md`](docs/ux-principles.md)
before making non-trivial changes.

**What this project is:** a native Android client for the XMage MTG engine. The server is
authoritative and enforces all rules; this app is a networked view + controller. **We do
not implement Magic rules on device.**

---

## Golden rules

1. **Kotlin only.** No Java in app modules. Idiomatic, null-safe Kotlin.
2. **Jetpack Compose only** for UI. No XML layouts, no `Fragment`-based View UI, no Swing
   thinking carried over from the Desktop client.
3. **Don't port the Desktop UI.** Feature parity, not UI parity. See
   [`docs/ux-principles.md`](docs/ux-principles.md).
4. **UI never touches the network or `mage.view.*` shapes directly.** Data flows
   network → repository → mapped domain model → ViewModel → immutable UI state → Composable.
5. **No blocking the main thread.** All I/O on `Dispatchers.IO`; expose results as
   `Flow`/`StateFlow`. Structured concurrency only — no rogue `GlobalScope`.
6. **Every change builds and passes `./gradlew check` before it's "done."** State test
   results honestly; if something is skipped or failing, say so.

---

## Tech stack (the defaults — deviate only with a reason)

| Concern            | Choice                                                        |
|--------------------|---------------------------------------------------------------|
| Language           | Kotlin (latest stable)                                        |
| UI                 | Jetpack Compose + Material 3                                   |
| Architecture       | MVVM with unidirectional data flow (MVI-flavored state)       |
| Async / reactive   | Coroutines + Flow / StateFlow                                 |
| DI                 | Hilt                                                          |
| Navigation         | Navigation-Compose (type-safe routes)                         |
| Networking         | OkHttp/Ktor WebSocket + kotlinx.serialization (JSON) to the bridge (see architecture.md) |
| Image loading      | Coil (with a real disk-cache strategy for card art)           |
| Local persistence  | DataStore for prefs; Room only if a real relational need appears |
| Build              | Gradle Kotlin DSL + version catalog (`libs.versions.toml`)    |
| Testing            | JUnit4/5, Turbine (Flow), MockK, Compose UI test, Espresso for instrumented |

Pin versions in a Gradle **version catalog**. No hard-coded dependency versions scattered in
build files.

---

## Project structure (target)

Multi-module, feature-oriented. Introduce modules as features arrive; don't scaffold empty
ones prematurely.

```
:app                     # thin: DI wiring, navigation host, app entry
:core:model              # our domain models (NOT mage.view.*) — pure Kotlin
:core:network            # bridge client, DTOs, mappers → :core:model
:core:designsystem       # theme, Material3 tokens, shared Composables
:core:common             # utils, Result types, dispatchers
:feature:connect         # server list / login / connection state
:feature:lobby           # rooms, tables, tournaments
:feature:deckbuilder     # deck build/import/export (DeckCardLists-compatible)
:feature:game            # the in-game board & decision loop
```

Rules:
- `:feature:*` modules depend on `:core:*`, never on each other.
- DTOs and `mage.view.*`-shaped types stay inside `:core:network` and are mapped to
  `:core:model` at the boundary. Nothing above `:core:network` knows the wire format.

---

## Repository & git workflow

**Monorepo on GitHub** at `VanoraSC/mage_free_client`; the JVM bridge and the Android app
live here together (see [`docs/architecture.md`](docs/architecture.md)). The remote is used
over HTTPS. `docs/` holds the planning set; `bridge/`, `app/`, `core/`, and `feature/`
modules arrive as work lands.

**Branch model.** `main` is the default branch and the protected baseline. All work happens
on branches off `main`.

**`main` is protected — every change lands via a pull request:**

- Branch off `main`, commit, open a PR, and merge it there. **No one pushes directly to
  `main`** (enforced on admins too); direct pushes are rejected.
- No approvals are required, so the maintainer self-merges their own PRs.
- Force-pushes and branch deletion on `main` are blocked; PR conversations must be resolved
  before merging.
- Pushing to a feature branch — including one with an open PR — is normal and updates that
  PR.
- **A story PR references its issue as `Story: #N`, never `Closes #N`.** A closing keyword
  closes the issue on merge; a story's issue closes after the lead has confirmed it on a
  device. See [`docs/stories/README.md`](docs/stories/README.md#issue-tracking).

---

## Coding conventions

- **State:** UI state is a single immutable `data class` per screen, exposed as
  `StateFlow<XxxUiState>` from the ViewModel. Events flow up via function calls or a sealed
  `UiEvent`. No mutable state leaking into Composables beyond `remember`.
- **Composables:** stateless and preview-able. Hoist state. Provide `@Preview`s (light +
  dark) for anything visual. Pass data in, send events out — Composables don't fetch.
- **Coroutines:** inject a `DispatcherProvider`; never hard-code `Dispatchers.*` in
  business logic. `viewModelScope` for VM work. Cancellation-aware.
- **Errors:** model network/connection failures explicitly (a `Result`/sealed type), render
  them as UI state. Never swallow exceptions silently. Reconnection is expected behavior,
  not an error path to ignore.
- **Immutability:** `val` over `var`; `data class`; persistent/immutable collections in
  state.
- **Naming & style:** follow the official Kotlin style guide; ktlint/detekt clean.
- **Documentation states current behaviour.** KDoc and comments say what the code does and why it
  must be that way, in the present tense, as if it had always been so. **No story or epic numbers,
  no plan-section pointers (`§7.4`), no dated attributions ("found live, 2026-08-16"), and no
  narration about how the code came to be.** Git and the story documents already hold that, and a
  second copy in the source rots — a sentence like "those are stories 0004–0006" describes a future
  that has since happened, and nothing rechecks it. Keep the rationale, which is the valuable half:
  "`orEmpty()` is load-bearing because upstream only allocates the list here" is a current fact
  about the code and must survive. Referring to a project-wide engineering rule by name
  ("verification standard 2, reachability") is fine — that is a live cross-reference, not history.
- **Accessibility:** content descriptions on interactive elements; min 48dp touch targets;
  respect dynamic type. This is a requirement, not a nice-to-have.
- **No secrets in the repo.** No hard-coded server credentials or tokens.

---

## Portability rules

The logic modules stay free of Android so a second target is a build change rather than a rewrite.
These cost nothing applied as work lands and are expensive to retrofit; the evidence behind each is
in [`docs/ui-modernization-plan.md`](docs/ui-modernization-plan.md) §9.2, and this list is what a
story is checked against.

- **No `android.*` or `androidx.*` in `:protocol`, `:core:model`, `:core:network`, `:core:decks`,
  or the non-UI half of `:core:cards`.** They need only Kotlin, coroutines, serialization and Ktor.
  The rule is about platform APIs, not the `androidx` prefix: `androidx.room`, `androidx.sqlite` and
  `androidx.datastore` are multiplatform artifacts and belong in `commonMain` — an `android.content`
  or `android.database` import is what it is aimed at.
  **`java.*` in `commonMain` is not caught by the build, so a reviewer is the guard.** While every
  target is JVM-family (`androidTarget()` + `jvm()`), Kotlin disables the shared-source-set metadata
  compilation, so `commonMain` is only ever compiled with the JDK on the classpath and a `java.*`
  import resolves silently. There are none today; keep it that
  way. The Stable replacements, none of which need an opt-in on the pinned Kotlin: `kotlin.uuid.Uuid`,
  `kotlin.time.Clock`, `kotlin.concurrent.Volatile`, `kotlin.coroutines.cancellation.CancellationException`,
  `okio.IOException` (a typealias **to** `java.io.IOException` on the JVM), the no-arg
  `lowercase()`/`uppercase()`, and — for the `java.util.concurrent` types — an `expect class` with an
  `actual typealias` per target, which keeps the identical JDK class rather than trading it for a lock.
  The KMP port story document has the full table and the rejected alternatives.
  **Check an API's stability in the docs for the pinned version before assuming it needs an opt-in** —
  `Uuid` and `Clock` are Stable as of 2.4 and 2.3.
  `:protocol` and `:core:model` hold this today and must keep holding it — they are the two modules
  a second client consumes first.
- **Device-specific needs sit behind an interface at the module boundary** — storage paths,
  notifications, secure storage, bundled assets. `ConnectivityObserver` with
  `AndroidConnectivityObserver` behind it is the shape to copy: `ConnectivityManager` never leaks
  past that boundary, and that boundary is where an `expect`/`actual` would go.
- **Check multiplatform support before adding a dependency to a `:core:*` module.** One
  Android-only library there turns a mechanical port into a rewrite. Choosing one anyway is fine —
  knowingly, and above the logic layers.
- **Insets are handled in exactly one place:** `:core:designsystem/layout/Insets.kt`.
- **Hardware Back is never the only path.** Every cancel affordance also exists on screen.

---

## Testing & running on device

Testing happens on an **Android Emulator (AVD)** or on **the maintainer's physical phone**
over ADB. Optimize for both.

- **Unit tests** (`test/`): ViewModels, mappers, repositories, pure logic. Fast, no
  Android framework. Use Turbine for `Flow`, MockK for fakes, a `TestDispatcher`.
- **Instrumented / UI tests** (`androidTest/`): Compose UI tests + critical-path flows.
  These run on the emulator or the physical device.
- **Prefer fakes over mocks** at module boundaries (e.g. a `FakeBridgeClient`) so the app
  can run and be UI-tested without a live XMage server/bridge.

### Common commands (once the Gradle project exists)

```bash
./gradlew check                       # lint + unit tests — the pre-"done" gate
./gradlew test                        # unit tests only
./gradlew connectedAndroidTest        # instrumented tests on a running emulator/device
./gradlew installDebug                # build + install debug APK to the connected device
adb devices                           # confirm the emulator/phone is attached
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
```

### Device/emulator workflow notes for agents

- **Don't assume a device is attached.** Run `adb devices` first; if none, ask the
  maintainer to start an emulator or plug in the phone rather than guessing.
- **Don't launch long-running emulators or interactive tools without asking.** Prefer unit
  tests (no device needed) for fast feedback; reserve `connectedAndroidTest`/manual runs for
  when a device is confirmed present.
- **The physical phone is personal hardware.** Only install debug builds of this app; never
  run destructive `adb` commands (wipe, uninstall unrelated packages, etc.).
- When you can't verify on a device, say so explicitly instead of claiming it "works on
  device."

---

## Working with the upstream XMage repo (`../mage`)

- It's the **reference for server behavior and the wire contract**, not code to copy.
  `Mage.Common` (`mage.view.*`, `mage.remote.SessionImpl`, `mage.interfaces.MageServer`,
  `ClientCallback`) tells you *what data exists and how the game loop works*.
- **Never** add `Mage.Common`/`Mage` as an Android dependency (Java-1.8 / Swing-adjacent /
  JBoss-serialization baggage — see [`docs/architecture.md`](docs/architecture.md)).
- The only place upstream shapes may appear is inside `:core:network` mappers, and even
  there prefer re-declaring the minimal fields we consume over importing upstream classes.
- Keep [`docs/architecture.md`](docs/architecture.md) updated as we learn the protocol —
  it's the shared source of truth for the integration.

---

## For agents specifically

- **Ask before large scaffolds.** Don't generate a full module tree or dozens of files
  unprompted; propose, then build what's agreed.
- **Small, reviewable changes.** One concern per change; keep diffs readable.
- **Match surrounding code** — its conventions win over your defaults once the codebase has
  a shape.
- **Report honestly.** Build failed → say so with output. Test skipped → say so. Verified on
  device → only if you actually did.
- **Don't reintroduce desktop UX.** If a request would recreate the Swing layout on a phone,
  flag the tension with [`docs/ux-principles.md`](docs/ux-principles.md) before doing it.
- Commit/push only when the maintainer asks; then follow **Repository & git workflow**
  above — branch off `main`, land changes via a PR, never push to `main` directly.
