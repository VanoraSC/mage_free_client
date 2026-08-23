# 0085 — Robolectric out of the logic modules

- **Epic:** EPIC-18 — Multiplatform Foundation
- **Depends on:** 0082, 0083 (the modules must be off Android before their tests can be)

## 1. Objective

Turn the seven Robolectric tests in `:core:cards` and `:core:decks` into plain JVM tests running on
the `jvm()` target, and leave the other five — which are Compose tests — exactly where they are.

This is the story that makes Phase 0's claim *checkable on a schedule*: a `:core:*` test suite that
runs on the JVM is a portability regression detector that fires on every commit.

## 2. Context & background

**Twelve Robolectric files exist; seven are in scope and five are not.** Enumerated:

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

**Those five are not collateral — they are the hermetic gate.** `docs/stories/README.md` records
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
  if nothing else in those modules needs them.
- `testOptions { unitTests { isIncludeAndroidResources = true; isReturnDefaultValues = true } }`
  removed from both modules' `android { }` blocks if it becomes unnecessary.
- CI running the `:core:*` JVM test tasks, so the portability claim is checked per commit.

**Out of scope**
- The five Compose Robolectric tests. Not moved, not converted, not run on desktop.
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
faithfully, leaving it on Android is the correct answer; a weakened version of it is not.

## 5. Verification

- **Standard 1:** for each moved test, prove it still discriminates — break the behaviour it covers
  and confirm it fails on the JVM target. Seven tests, seven checks; this is the whole point of the
  story and skipping it makes the suite decorative.
- **Standard 2 (reachability):** confirm the JVM test tasks are actually wired into `./gradlew check`
  and into CI, not merely runnable by name. A portability check nothing invokes is not a check.
- **Hermetic gate:** `./gradlew check` runs the same number of `:core:cards` and `:core:decks` tests
  as before, on the JVM target, and the five Compose Robolectric tests still run on Android.
- **No eyes-on checklist.** This story changes test infrastructure and ships no behaviour change;
  `:app:assembleDebug` plus a launch is the device check. Inventing a checklist here would train
  everyone to skim the next one.

## 6. Acceptance criteria

- [ ] The seven listed tests run on the `jvm()` target with their assertions unchanged.
- [ ] Each is proven to still fail when the behaviour it covers is broken.
- [ ] The executed test count in `:core:cards` and `:core:decks` matches the pre-move count.
- [ ] Robolectric is no longer a dependency of either module.
- [ ] The five Compose Robolectric tests still run on Android under `./gradlew check`.
- [ ] CI runs the `:core:*` JVM test tasks.
- [ ] The APK builds and launches.

## 7. References

- `docs/stories/README.md` § *Verification standards* — why the Compose Robolectric tests stay.
- `docs/ui-modernization-plan.md` §11 Phase 0 step 6, and the desktop section explaining why tests
  do not move there.
- `docs/stories/0056-card-art-user-agent.md` — what `CardArtUserAgentTest` is guarding.
