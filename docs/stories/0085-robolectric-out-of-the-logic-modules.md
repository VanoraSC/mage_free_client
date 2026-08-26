# 0085 — Robolectric out of the logic modules

- **Epic:** EPIC-18 — Multiplatform Foundation
- **Depends on:** 0082, 0083 (the modules must be off Android before their tests can be)

## 1. Objective

Turn the seven Robolectric tests in `:core:cards` and `:core:decks` into plain JVM tests running on
the `jvm()` target, and leave the five Compose tests exactly where they are.

**Widened once in flight, at Pete's direction (2026-08-25): `:core:network`'s suite moved too.** Its
216 non-Android tests were never Robolectric tests — they were plain JVM work sitting in an Android
source set because that was the only test source set the module had. Leaving them would have left the
portability detector covering a third of `:core:*`. 264 tests now run on the `jvm()` target; three
stay on Android because the Android edge is their subject.

This is the story that makes Phase 0's claim *checkable*: a `:core:*` test suite that runs on the JVM
is a portability regression detector rather than a second copy of the Android tests.

**It fires on `./gradlew check`, not in CI, and that is a deliberate decision.** This repository has
no CI and is not getting one here — Pete runs the device verification, and the build and unit tests
run locally. So the detector's trigger is `check`, which the Kotlin plugin already wires `jvmTest`
into. That is verified in this story rather than assumed (§5).

## 2. Context & background

**Fifteen Robolectric files exist; seven are in scope and eight are not.** Enumerated:

| In scope — logic modules | |
|---|---|
| `core/cards/.../CardCatalogBundleTest.kt` | catalog over the bundled asset |
| `core/cards/.../CardCatalogExactNameTest.kt` | the NOCASE name lookup |
| `core/cards/.../CardCatalogFixtureTest.kt` | catalog queries against a fixture |
| `core/cards/.../art/CardArtFallbackTest.kt` | art URL resolution |
| `core/cards/.../art/CardArtUserAgentTest.kt` | the real outgoing request's header (story 0056) |
| `core/cards/.../art/CardImageLoaderTest.kt` | loader construction and cache policy |
| `core/decks/.../DeckRepositoryTest.kt` | Room-backed deck persistence |

| Out of scope — Compose tests, and they stay Android | |
|---|---|
| `app/src/testDebug/.../ConnectEntryReachabilityTest.kt` | |
| `app/src/testDebug/.../FeatureDestinationWiringTest.kt` | |
| `feature/cards/src/testDebug/.../CardSearchTypingTest.kt` | |
| `feature/decks/src/testDebug/.../AddCardsTypingTest.kt` | |
| `feature/game/src/testDebug/.../GameBoardScreenTest.kt` | |

| Out of scope — they test the Android edge itself (stories 0083, 0084) | |
|---|---|
| `core/decks/.../ExistingDeckDatabaseTest.kt` | a pre-port deck library opens through the shipping Android construction — `getDatabasePath` and `AndroidSQLiteDriver` are the subject |
| `core/decks/.../legality/FormatBundleLoaderTest.kt` | `formats.json` really ships in the APK and reads back through `AndroidBundledFiles` |
| `core/network/.../SavedServersSurviveUpgradeTest.kt` | a pre-port server list is found where the `preferencesDataStore` delegate puts it — the `Context` and `filesDir` are the subject |

**The five Compose tests are not collateral — they are the hermetic gate.** `docs/stories/README.md` records
that Compose tests running under Robolectric in `src/testDebug` are what catches a control that
"rendered, reported a click, and did nothing", and that device-only tests not running pre-merge is
how an entire epic once stayed unmounted. **They stay on Android under Robolectric.** This story does
not touch them, and the desktop target does not become where they run (`ui-modernization-plan.md`
§11).

**Why the seven can move at all.** They use Robolectric for exactly one reason each: `:core:cards`
needs an Android SQLite implementation and an `AssetManager`; `:core:decks` needs Room's Android
runtime. Stories 0082 and 0083 replace both with multiplatform equivalents — an `androidx.sqlite`
driver and a platform-supplied resource source — at which point the Android runtime is no longer
required and Robolectric is only inertia.

**There is a known Robolectric hazard on the horizon, and it is worth naming.** An AGP 9 migration
attempt produced four unexplained test failures in `:feature:game` — Robolectric Compose tests, of
the kind this story deliberately leaves alone. That work is not merged and AGP stays at 8.13.2. If
this story's changes appear to interact with it, they should not: the seven files moving here are
not Compose tests and not in `:feature:game`.

## 3. Scope

**In scope**
- The seven listed tests moved to `commonTest` or `jvmTest` and running on the `jvm()` target.
- `libs.robolectric` and `libs.androidx.test.ext.junit` removed from `:core:cards` and `:core:decks`
  if nothing else in those modules needs them. **`:core:decks` keeps them**, and correctly so — see §6.
- The JVM target's missing platform edge, without which none of this can run: `JvmBundledFiles`
  (classpath resources; the JVM half of story 0082's boundary, named there but never written) and
  `BundledSQLiteDriver`, which carries its own SQLite where `sqlite-framework` wraps the platform's.

**Out of scope**
- The five Compose Robolectric tests. Not moved, not converted, not run on desktop.
- **CI.** The repository has none and this story does not add one; `./gradlew check` is the trigger.
- ~~`:core:network`'s suite.~~ **Now in scope** (Pete, 2026-08-25): its 216 non-Android tests moved
  too. They were never Robolectric tests, but they were plain JVM work sitting in an Android source
  set, and leaving them there would have left the portability detector covering a third of `:core:*`.
- Rewriting what any test asserts. A moved test that also changed its assertions proves nothing
  about the move.
- Adding new test coverage. This is a relocation.

## 4. Design & approach

**Move, then delete the crutch — in that order and in separate commits.** Move each test with its
assertions byte-identical, confirm it passes on the JVM target, and only then remove the Robolectric
dependency. A single commit that does both cannot answer "did this test pass because it moved
correctly, or because it stopped running?"

**A test count is the guard.** The failure mode of this story is silent: a test that no longer runs
looks exactly like a test that passes. Record the executed test count in both modules before and
after, and confirm they match. Then break one assertion and confirm the build fails.

**`CardArtUserAgentTest` deserves individual attention.** It inspects a real outgoing HTTP request,
which is the whole reason story 0056's defect was catchable at all — card art had never loaded
anywhere while every other test was green. If it cannot be made to work on the JVM target as
faithfully, leaving it on Android is the correct answer; a weakened version of it is not. It moved
intact: MockWebServer runs on the JVM unchanged, and all six tests still assert on the wire.

**`CardCatalogExactNameTest` got simpler on the JVM, not harder.** Story 0082 could not read
`EXPLAIN QUERY PLAN` through the driver — `AndroidSQLiteStatement` splits into a SELECT variant and
an "other" variant, an EXPLAIN lands in the latter, and `step()` returns false without rows — so it
read the plan through `android.database.sqlite.SQLiteDatabase` against the same prepared file.
`BundledSQLiteDriver` has no such split and returns the plan rows like any other query. The move
therefore *removed* an indirection, and both index-usage assertions (story 0042 defect D) stand.

**The catalog's copy-once cache will absorb a break, and it caught me proving standard 1.**
`CardCatalogDatabase` copies the bundle into `BundledFiles.writableDirectory` under a name stamped
with `ASSET_VERSION` + `LOCAL_REVISION`, and reuses it. On device that directory goes with the app;
on a dev machine `JvmBundledFiles` puts it under the JVM temp root, where it **survives between test
runs**. So a deliberate break to the asset name or to `prepare()` that does not also bump
`LOCAL_REVISION` is read straight past, and the test passes green — which is exactly what a broken
test looks like. Delete the private copy (or bump the revision) when checking that a catalog test
still discriminates. This is the copy-once mechanism working as designed, not a defect, but it is a
trap worth knowing about before concluding a test is decorative.

## 5. Verification

- **Standard 1:** for each moved test, prove it still discriminates — break the behaviour it covers
  and confirm it fails on the JVM target. Seven tests, seven checks; this is the whole point of the
  story and skipping it makes the suite decorative.
- **Standard 2 (reachability):** confirm the JVM test tasks are actually wired into `./gradlew check`,
  not merely runnable by name. A portability check nothing invokes is not a check. Confirmed by
  reading the executed task list: `:core:cards:jvmTest` and `:core:decks:jvmTest` both run under
  `check` without being named.
- **Hermetic gate:** `./gradlew check` runs the same number of `:core:cards` and `:core:decks` tests
  as before, on the JVM target, and the five Compose Robolectric tests still run on Android.
- **No eyes-on checklist.** This story changes test infrastructure and ships no behaviour change;
  `:app:assembleDebug` plus a launch is the device check. Inventing a checklist here would train
  everyone to skim the next one.

## 6. Acceptance criteria

- [ ] The seven listed tests, plus `:core:network`'s 216, run on the `jvm()` target with their
      assertions unchanged.
- [ ] Each is proven to still fail when the behaviour it covers is broken. For `:core:network`'s bulk
      move this is one break per shared production seam rather than one per class, with every moved
      class recorded as failing under at least one — except the four env-gated live IT classes, which
      skip without a reference bridge and are unchanged by the move.
- [ ] The executed test count in `:core:cards`, `:core:decks` and `:core:network` matches the
      pre-move count.
- [ ] Robolectric is no longer a dependency of `:core:cards`.
      **Corrected:** this criterion originally said "of either module". `:core:decks` keeps
      Robolectric, and should: `ExistingDeckDatabaseTest` and `FormatBundleLoaderTest` (stories 0083
      and 0084) test the Android edge *itself* — `getDatabasePath`, `AndroidSQLiteDriver`, APK assets
      — so an Android runtime is their subject, not their crutch. §2's inventory already listed them
      as out of scope; this line had not caught up.
- [ ] The five Compose Robolectric tests still run on Android under `./gradlew check`.
- [ ] `:core:cards:jvmTest` and `:core:decks:jvmTest` run under `./gradlew check` without being named.
- [ ] The APK builds and launches.

## 7. References

- `docs/stories/README.md` § *Verification standards* — why the Compose Robolectric tests stay.
- `docs/ui-modernization-plan.md` §11 Phase 0 step 6, and the desktop section explaining why tests
  do not move there.
- `docs/stories/0056-card-art-user-agent.md` — what `CardArtUserAgentTest` is guarding.
