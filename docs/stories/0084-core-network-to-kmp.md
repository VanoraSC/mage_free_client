# 0084 — `:core:network` to KMP: three files, and a boundary that already exists

- **Epic:** EPIC-18 — Multiplatform Foundation
- **Depends on:** 0083 (`:core:network` has `api(project(":core:decks"))`), 0082, 0081, 0080

## 1. Objective

Move `:core:network` off Android. It is the last `:core:*` conversion because of the dependency
chain, and it is the smallest — the module that carries the bridge client, the session, the reconnect
loop and every repository has **three** Android-coupled files.

## 2. Context & background

**The plan's §9.2 recorded more debt here than exists, and the story corrects it.** Two of the three
items it listed as debt are already the pattern working:

- **`ProcessLifecycleOwner` is already behind an interface.** `AppLifecycleObserver` is a plain
  `interface { val isForeground: Flow<Boolean> }`, with `ProcessAppLifecycleObserver` as the Android
  implementation and `AlwaysForegroundAppLifecycleObserver` as the no-platform default. That is the
  same shape as `ConnectivityObserver`/`AndroidConnectivityObserver`, which §9.2 correctly calls
  "working". The abstraction is not missing; only the implementation's *location* is wrong.
- **`Context` does not reach into `ServerRepository`.** Its constructor takes
  `DataStore<Preferences>` and nothing else. The `Context` is in `NetworkModule`, which builds that
  DataStore through the `preferencesDataStore` delegate.

`ui-modernization-plan.md` §9.2 has been corrected accordingly. The real surface, every
`android.*`/`androidx.*` import in `src/main`:

| File | What it uses | Shape of the fix |
|---|---|---|
| `AndroidConnectivityObserver.kt` | `ConnectivityManager`, `Network*` | Move to `androidMain` unchanged |
| `ProcessAppLifecycleObserver.kt` | `androidx.lifecycle.ProcessLifecycleOwner` | Move to `androidMain` unchanged |
| `di/NetworkModule.kt` | `Context`, `preferencesDataStore` | DataStore constructed from a supplied path |

`ServerRepository.kt` imports `androidx.datastore.preferences.core.*`, which is the **multiplatform**
half of DataStore — the artifact changes, the file does not.

**The dependency the module carries is Android-only for one reason.**
`implementation(libs.androidx.lifecycle.process)` exists solely for `ProcessAppLifecycleObserver`, so
it moves to the Android source set with it.

**The Ktor client is already multiplatform**, and `ktor-client-okhttp` is an engine choice rather
than a portability problem — a JVM target can keep OkHttp. It does not have to change in this story,
and changing it would add an untested variable to a port.

## 3. Scope

**In scope**
- `:core:network` applies `magefree.kmp.library` with `jvm()` + `androidTarget()`.
- `AndroidConnectivityObserver` and `ProcessAppLifecycleObserver` move to `androidMain`, along with
  the `androidx.lifecycle.process` dependency. Their code is unchanged.
- `androidx.datastore:datastore-preferences` → the multiplatform `datastore-preferences-core`, with
  the store constructed from a supplied file path rather than the `Context` delegate.
- `NetworkModule` (Koin) in common; the two Android observers and the DataStore path bound at the
  Android edge.

**Out of scope**
- Any change to the reconnect loop, the resume/park protocol, or the session state machine. Stories
  0023–0025 and 0050 are load-bearing and hard-won; this story moves files and changes a build.
- Swapping the Ktor engine.
- The bridge (`:bridge`) — it is a JVM service and is not part of this epic.

## 4. Design & approach

**The observers move; they do not get rewritten.** Both already implement their interfaces correctly
and are the reference examples §9.2 points other modules at. A port that "improves" them while
relocating them destroys the ability to tell a relocation bug from a behaviour change.

**Server-list persistence is real user data.** `ServerRepository` holds the servers a person has
added. Changing how its DataStore is constructed changes **where the file lives** unless the supplied
path reproduces the delegate's location exactly. Get this wrong and every saved server silently
disappears on upgrade, with no error and no crash — the app just looks new. So: reproduce the
existing path, and prove it with an upgrade test rather than a fresh-install one.

**Reconnect behaviour is the thing most likely to break quietly.** The lifecycle and connectivity
observers feed a back-off loop whose failure mode is "reconnects more slowly than it should", which
no unit test notices and no fresh smoke test reaches. The existing `:core:network` suite plus a
deliberate connectivity interruption on-device is what covers it.

## 5. Verification

- **Standard 1:** the "existing saved servers survive" test proven failing first, by pointing the
  store at a different path, then passing.
- **Standard 2 (reachability):** name what supplies the DataStore path and each observer
  implementation on Android and on JVM, and confirm each is exercised rather than declared.
- **Hermetic gate:** `./gradlew check` with the existing `:core:network` suite unchanged, including
  `KtorBridgeClientSignOutTest`, which asserts on real WebSocket frames.
- **Live:** this module is what stories 0045 and 0050 exist to exercise. Run the env-gated live
  integration tests against the reference server.
- **Eyes-on (standard 3) — hand Pete this checklist.**
  1. **Before installing**, note the servers saved in your server list.
  2. Install **over** the existing app (do not clear data). Confirm the same servers are still listed.
  3. Connect and sign in; confirm the connection status bar reports connected.
  4. Turn Wi-Fi off, wait for the status to show disconnected, then turn it back on. Confirm it
     reconnects **promptly** — a slow reconnect is the shape of a broken connectivity observer.
  5. Background the app for a minute, then return to it. Confirm it is still connected or recovers
     without a re-sign-in — that is the lifecycle observer.
  6. Sign out and confirm the app returns to the server list cleanly.

## 6. Acceptance criteria

- [ ] `:core:network` is a KMP module; no `android.*`/`androidx.*` import remains outside
      `androidMain` except multiplatform DataStore.
- [ ] `androidx.lifecycle.process` is declared only for the Android target.
- [ ] Saved servers written by the pre-port build are still present after upgrading, proven by test.
- [ ] The existing suite passes unchanged, including the live integration tests.
- [ ] `./gradlew check` and `:app:assembleDebug` pass.
- [ ] Pete has completed the eyes-on checklist, including the upgrade and the reconnect steps.

## 7. References

- `core/network/src/main/kotlin/magefree/network/reconnect/AppLifecycleObserver.kt` and
  `ConnectivityObserver.kt` — the interfaces that already exist.
- `core/network/src/main/kotlin/magefree/network/di/NetworkModule.kt` — the module's only real
  `Context`.
- `core/network/src/main/kotlin/magefree/network/ServerRepository.kt` — takes a `DataStore`, not a
  `Context`.
- `docs/stories/0024-app-reconnect-and-lifecycle-session.md` — what the two observers feed.
- `docs/ui-modernization-plan.md` §9.2 — corrected by this story's findings.
