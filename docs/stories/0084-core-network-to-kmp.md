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
than a portability problem — a JVM target can keep OkHttp. The engine does not change in this story;
changing it would add an untested variable to a port.

**But the engine still cannot be *named* from common code, and that is a fourth file.**
`KtorBridgeClient.defaultHttpClient()` writes `HttpClient(OkHttp)`, and `OkHttp` comes from a
JVM-family artifact. Ktor's engine-less `HttpClient { }` overload — the one that finds an engine on
the classpath — is not an alternative: it is declared in `HttpClientJvm.kt`, so it is JVM-only too.
The engine therefore moves behind an `expect fun bridgeHttpClient()`, with both targets' `actual`
returning the same OkHttp client. Same engine on Android, same client, one indirection.

**Two of the three Android files are a straight relocation; the DataStore is not.** See §4.

## 3. Scope

**In scope**
- `:core:network` applies `magefree.kmp.android.library` — the convention plugin story 0082 added for
  a multiplatform module that still ships on Android, carrying `jvm()` + `androidTarget()`.
- `AndroidConnectivityObserver` and `ProcessAppLifecycleObserver` move to `androidMain`, along with
  the `androidx.lifecycle.process` dependency. Their code is unchanged.
- The Ktor engine moves behind an `expect`/`actual`; `ktor-client-okhttp` is declared per target.
- `NetworkModule` (Koin) in common; the two Android observers, the DataStore and the IO dispatcher
  bound at the Android edge.
- **`java.*` out of `commonMain` — in this module and in `:core:cards`, which 0082 left carrying it.**
  Not in the story as first written; added once §4's finding showed the build does not enforce it and
  that every replacement is Stable with no behaviour change. `:core:cards` is in scope for this one
  item because the two modules share the problem and fixing one and not the other would leave the
  rule half-true.

**No DataStore artifact swap is needed, and the delegate stays.** `androidx.datastore:datastore-preferences`
1.1.7 is *already* a multiplatform artifact — it resolves to `-android`, `-jvm`, `-ios*`, `-linux*`
and `-macos*` variants, and `:core:cards` has consumed it from `commonMain` since story 0082. The
coordinate does not change. Neither does the construction: see §4.

**Out of scope**
- Any change to the reconnect loop, the resume/park protocol, or the session state machine. Stories
  0023–0025 and 0050 are load-bearing and hard-won; this story moves files and changes a build.
- Swapping the Ktor engine.
- The bridge (`:bridge`) — it is a JVM service and is not part of this epic.

## 4. Design & approach

**The observers move; they do not get rewritten.** Both already implement their interfaces correctly
and are the reference examples §9.2 points other modules at. A port that "improves" them while
relocating them destroys the ability to tell a relocation bug from a behaviour change.

**Server-list persistence is real user data, so the construction does not move at all.**
`ServerRepository` holds the servers a person has added. Changing how its DataStore is constructed
changes **where the file lives** unless the supplied path reproduces the delegate's location exactly
(`filesDir/datastore/server_targets.preferences_pb` — the delegate calls DataStore's own
`preferencesDataStoreFile(name)` to get it). Get this wrong and every saved server silently
disappears on upgrade, with no error and no crash — the app just looks new.

The safest way to reproduce a path exactly is not to spell it out. The `preferencesDataStore`
delegate stays, in `androidMain`, and the type the rest of the module sees — `DataStore<Preferences>`
— is already multiplatform, so nothing above that binding is Android-shaped. A JVM host supplies the
same binding through `PreferenceDataStoreFactory.createWithPath`. Prove it with an upgrade test
rather than a fresh-install one either way.

**Reconnect behaviour is the thing most likely to break quietly.** The lifecycle and connectivity
observers feed a back-off loop whose failure mode is "reconnects more slowly than it should", which
no unit test notices and no fresh smoke test reaches. The existing `:core:network` suite plus a
deliberate connectivity interruption on-device is what covers it.

**`commonMain` was never enforced free of `java.*`, and this story closes the gap it found.**
`compileCommonMainKotlinMetadata` is **SKIPPED** in every module here (`Task is enabled` is false):
Kotlin only enables the shared-source-set metadata compilation when a source set is shared across
more than one *platform type*, and `androidTarget()` and `jvm()` are both `platform.type = jvm`. So
`commonMain` is never compiled on its own — it is compiled as part of each JVM compilation, with the
JDK on the classpath — and `java.*` resolved there unnoticed. Ten files across `:core:cards` and
`:core:network` had come to rely on that. There are now none.

**Every replacement is Stable on the pinned Kotlin 2.4.10; none needed an opt-in.**

| Was | Now | Behaviour change |
|---|---|---|
| `@Volatile` (resolving to `kotlin.jvm.Volatile`) | `kotlin.concurrent.Volatile` — common, Stable since 1.9 | none |
| `java.util.UUID` | `kotlin.uuid.Uuid` — **Stable since Kotlin 2.4** | none |
| `java.io.IOException` | `okio.IOException` — an `actual typealias` **to** `java.io.IOException` on the JVM, so it still catches exactly what DataStore throws | none |
| `java.util.concurrent.CancellationException` | `kotlin.coroutines.cancellation.CancellationException` | none |
| `Locale.ENGLISH` case conversion | the no-arg `lowercase()` (`Locale.ROOT` on the JVM) | none for ASCII set codes |
| `ConcurrentHashMap`, `CopyOnWriteArrayList`, `AtomicReference`, `AtomicInteger` | `expect class` in `commonMain`, `actual typealias` to the same JDK class per target | **none — it is the same class** |

**The concurrency primitives keep their exact implementations, which is what made this safe inside a
port.** A typealias `actual` compiles to the aliased class: `PendingRequests`' correlation registry
is still a `ConcurrentHashMap`, same memory model, same lock-free reads on the socket's inbound path.
Nothing was rewritten; only the name `commonMain` sees changed. §3 puts the reconnect loop out of
scope, and a change with no behaviour delta does not breach that.

The alternatives were checked and rejected, each for a reason rather than a preference:

- **`kotlin.concurrent.atomics`** is still `@ExperimentalAtomicApi` in 2.4, and using it would mean
  re-expressing the registry as a CAS loop over an immutable map — provably equivalent, but a rewrite.
- **`kotlinx.atomicfu`** is described by the Kotlin documentation as *"used only for internal purposes
  and is not supported for general usage"*.
- **Touchlab's `stately-concurrent-collections`** has no `jvmMain` source set at all: its
  `ConcurrentMutableMap` is a plain `mutableMapOf()` behind one global lock, so every read would
  block. It is not `ConcurrentHashMap` semantics despite the name.

Two mechanical constraints, recorded because they cost time to rediscover: a typealias `actual` must
match the aliased class down to its **type-parameter names** (hence `E` and `V` rather than `T` in
`magefree.network.concurrent`), and it cannot satisfy an `expect class … : MutableMap<K, V>`, because
`ConcurrentHashMap.keys` returns its own `KeySetView`. The map's `expect` therefore declares only the
operations the registry performs, with no supertype.

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
- [ ] No `java.*` import remains in any `commonMain` across the converted `:core:*` modules, and every
      replacement is a Stable API or a typealias to the class it replaced.
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
