# 0081 — Dependency injection: Hilt to Koin

- **Epic:** EPIC-18 — Multiplatform Foundation
- **Depends on:** 0080 (the KMP convention plugin and the proof that a converted module resolves
  from every consumer — not strictly required to compile, but this story is far harder to review if
  the build is also moving underneath it)

## 1. Objective

Replace Hilt with Koin across the whole app. Hilt is the one genuine blocker to a second platform:
it is a Dagger-based annotation processor that generates Android components and has no multiplatform
story, so every module that uses it is pinned to Android for reasons that have nothing to do with
what the module does.

**This is the item whose cost grows fastest.** Every ViewModel added by the UI rebuild would
otherwise be written once against Hilt and again against whatever replaces it, so doing it before
Phases 1–4 is the difference between a wide mechanical change and a wide mechanical change repeated.

## 2. Context & background — the actual surface, counted

Measured across the repo rather than estimated:

| What | Count | Where |
|---|---|---|
| Files carrying a Hilt or `javax.inject` annotation | **34** | 10 modules |
| Modules applying the `magefree.hilt` convention plugin | **10** | `:app`, `:core:cards`, `:core:decks`, `:core:network`, `:feature:cards`, `:feature:connect`, `:feature:decks`, `:feature:game`, `:feature:lobby`, `:feature:tables` |
| `@Module @InstallIn` DI modules | **10** | one per consuming module, plus `:app`'s `ConnectionModule` and `:feature:cards`' `CardArt.kt` |
| `@HiltViewModel` ViewModels | **14** | `:app` (2), `:feature:*` (12) |
| `hiltViewModel()` call sites | **18** | 9 Composable files |
| `@HiltAndroidApp` | **1** | `MageApp` |
| `@AndroidEntryPoint` | **1** | `MainActivity` |
| `@EntryPoint` + `EntryPointAccessors` | **1** | `CardArt.kt`'s `CardArtLoaderEntryPoint` |

**There is no Hilt test infrastructure.** Zero occurrences of `@HiltAndroidTest`, `HiltAndroidRule`
or an equivalent anywhere in the repo — every test constructs its subject directly or uses a fake.
That materially lowers the risk of this story: the test suites do not have to be migrated alongside
the graph, they simply have to keep passing.

**The one non-mechanical site is `CardArt.kt`.** `rememberCardArtRenderer` reaches the app-wide
`CardImageLoader` singleton from a Composable through `EntryPointAccessors.fromApplication(...)`,
because a Composable is not an injection site. Koin's equivalent is a direct `koinInject()` /
`get()`, which is simpler — but it is the one call whose shape changes rather than its annotation,
so it is the one to convert deliberately and test.

**Hilt is also why KSP is configured the way it is.** Every consuming module sets
`hilt { enableAggregatingTask = false }` to route aggregation through KSP, because Hilt's legacy
javac aggregating task bundles a `kotlin-metadata-jvm` that cannot read Kotlin 2.4 metadata
(`docs/stories/README.md` § *Standard, deliberate accommodations*). **That accommodation exists only
for Hilt and goes with it.** KSP itself stays — Room in `:core:decks` still needs it.

## 3. Scope

**In scope**
- Koin added to `gradle/libs.versions.toml` with an **explicitly pinned version** (see §4).
- A `magefree.koin` convention plugin replacing `HiltConventionPlugin`; the `magefree.hilt` plugin
  and every `hilt { enableAggregatingTask = false }` block removed.
- All 10 `@Module @InstallIn` classes rewritten as Koin modules.
- All 14 `@HiltViewModel` ViewModels and their `@Inject constructor`s converted; all 18
  `hiltViewModel()` call sites converted to Koin's Compose ViewModel resolution.
- `MageApp` starts the Koin container in place of `@HiltAndroidApp`; `MainActivity` loses
  `@AndroidEntryPoint`.
- `CardArt.kt`'s entry point replaced with direct Koin resolution.
- `androidx-hilt-navigation-compose` removed from the catalog if nothing else uses it.

**Out of scope**
- **Moving any module off Android.** This story swaps the DI framework and nothing else; the
  `Context` dependencies in `:core:network`, `:core:decks` and `:core:cards` are stories 0082–0084.
  A Koin module may still take a `Context` when it is done.
- Restructuring the object graph. Same bindings, same scopes, same lifetimes — a graph redesign
  bundled into a framework swap makes any resulting defect undiagnosable.
- KSP removal. Room needs it.

## 4. Design & approach

**Pin an exact Koin version, and treat the choice as a toolchain decision.** `docs/stories/README.md`
forbids "latest" and forbids improvising versions inside a story — but a foundation story adding the
DI framework *is* the deliberate, project-wide context that rule points at. So: an exact Koin 4.x
patch version, confirmed against Kotlin 2.4.10 and against the Compose BOM in use before it is
written into the catalog, recorded in the story's PR with what it was checked against. Not a range,
not `latest`, and not decided silently.

**Convert leaf-first, in dependency order.** `:core:*` modules provide bindings that `:feature:*`
modules consume, so converting a consumer before its provider leaves an unresolvable graph mid-work.
Order: `:core:network`, `:core:decks`, `:core:cards` → `:feature:*` → `:app` last, since `:app` owns
the container start-up and is the point at which the graph must be complete.

**Verify the graph at start-up, not at first use.** This is the story's real hazard and it deserves
naming. Hilt fails at **compile time** when a binding is missing; Koin fails at **runtime**, when
something first asks for it. A mechanical conversion that misses one binding therefore compiles
cleanly and crashes on whichever screen needs it — possibly a screen nobody opened during testing.
Koin's own module-verification check closes exactly this gap, and this story is worthless without
it: **a test that verifies every module's graph resolves must land with the conversion**, not after.
That test is the compile-time safety being given up, bought back.

**`:core:*` Koin modules are written to be platform-free from the start**, even while their bodies
still take a `Context`. The `Context` goes in stories 0082–0084; writing the module declarations in
common-source shape now means those stories delete a parameter rather than rewrite a file.

## 5. Verification

- **Standard 1 (prove it fails first)** applies to the graph-resolution test specifically: with one
  binding deliberately removed, the verification test must fail. A graph check that passes against a
  broken graph is exactly the "manufactures confidence" case the standard exists for.
- **Standard 2 (reachability):** for every `hiltViewModel()` call site replaced, name what now
  provides that ViewModel and confirm the screen it is on was actually opened during verification.
  18 call sites, 9 files — none skipped because it "looks the same as the others".
- **Hermetic gate:** the full existing suite, unchanged, must pass — including the 12 Robolectric
  Compose tests. They construct their subjects directly, so they are an honest regression net here.
- **Live:** the app is exercised through **every** screen that resolves a ViewModel, since runtime
  DI failure is per-screen. Connect, server list, sign-in, lobby, host, join, table room, decks
  library, deck builder, add cards, card search, card inspection, art settings, and the board.
- **Eyes-on (standard 3) — hand Pete this checklist.**
  1. Launch the app. Confirm it reaches the server list without crashing (a DI failure at start-up
     shows here).
  2. Sign in, open the lobby, host a table, join it, and open the table room.
  3. Open Decks → open a deck → open the deck builder → open Add Cards.
  4. Open card search, open a card's inspection view, and open the card-art settings.
  5. Start a game and reach the board.
  6. Report anything that crashed **on opening** rather than on interaction — that is the shape a
     missed binding takes.

## 6. Acceptance criteria

- [ ] No `dagger.hilt` or `javax.inject` import remains anywhere in the repo.
- [ ] `HiltConventionPlugin` and every `hilt { }` block are gone; `magefree.koin` replaces it.
- [ ] The Koin version in the catalog is an exact pin, and the PR records what it was verified
      against.
- [ ] A module-graph verification test covers every Koin module and is proven to fail when a binding
      is removed.
- [ ] `./gradlew check` and `./gradlew :app:assembleDebug` pass with the existing suite unchanged.
- [ ] Pete has completed the eyes-on checklist and every listed screen opened without crashing.

## 7. References

- `build-logic/convention/src/main/kotlin/HiltConventionPlugin.kt` — what is being replaced.
- `app/src/main/kotlin/magefree/app/MageApp.kt`, `MainActivity.kt` — container start-up and the one
  `@AndroidEntryPoint`.
- `feature/cards/src/main/kotlin/magefree/feature/cards/CardArt.kt` — the `@EntryPoint` site, the one
  conversion whose shape changes.
- `core/network/src/main/kotlin/magefree/network/di/NetworkModule.kt` and the nine sibling DI
  modules.
- `docs/stories/README.md` § *Standard, deliberate accommodations* — the Hilt/KSP aggregation note
  that is removed with Hilt.
- `docs/ui-modernization-plan.md` §11 Phase 0 step 2 — why this is the item whose cost grows fastest.
