# 0080 — KMP build foundation: `:protocol` and `:core:model`

- **Epic:** EPIC-18 — Multiplatform Foundation
- **Depends on:** — (first story of the epic)

## 1. Objective

Convert the two modules that are already platform-clean — `:protocol` and `:core:model` — from
Kotlin/JVM to Kotlin Multiplatform with a single `jvm()` target, and add the `build-logic` convention
plugin that every later `:core:*` module will apply.

This story **changes no Kotlin source**. Its entire purpose is to prove the build plumbing before
anything hard depends on it: that a KMP module still resolves from a plain JVM consumer (`:bridge`),
from Android library consumers, and from `:app`; that ktlint and the test tasks still run; and that
CI stays green. Every later story in this epic assumes this works.

## 2. Context & background

**These two modules are already JVM-only, not Android.** Both apply `kotlin("jvm")` +
`java-library` with `jvmToolchain(17)`, and neither imports anything from `android.*` or
`androidx.*` — confirmed by grep across both source trees, zero hits. `:core:model` is deliberately
dependency-free; `:protocol` carries only `kotlinx-serialization-json`. That is why they are first:
the conversion is a build-structure change with no code to untangle.

**`:protocol` has a non-Android consumer, which is the point.** `:bridge` is a plain Kotlin/JVM
Ktor service and depends on `:protocol`. It is the closest thing the repo has to "a second client",
and it must keep compiling unchanged — a KMP module that only works for Android consumers has proved
nothing.

**The consumer question is the real risk.** Android library modules (`:core:network`,
`:core:cards`, `:core:decks`, `:feature:*`) consume `:core:model` and `:protocol` today as project
dependencies. After the conversion they consume a KMP module whose only target is `jvm()`. Kotlin's
platform-compatibility rules allow an `androidJvm` consumer to resolve a `jvm` variant — this is the
same mechanism by which every Android app consumes `kotlinx-coroutines-core` — but it is a
resolution behaviour this repo has never exercised, and `FAIL_ON_PROJECT_REPOS` plus AGP's
consistent-resolution settings make dependency resolution here less forgiving than average.
**Verify it, do not assume it.** If it does not resolve, the fallback is adding an `androidTarget()`
to the two modules, which costs the Android Gradle plugin in their build files and is a worse
outcome — so it is a fallback, not the plan.

**Toolchain guardrail.** Kotlin 2.4.10 already provides the `multiplatform` plugin; no new version
enters the catalog for this story. `settings.gradle.kts` keeps `:core:model` outside the
`MAGE_JVM_ONLY` guard — it must still configure in the JVM-only build container (story 0020), and
that stays true after the conversion.

## 3. Scope

**In scope**
- A `magefree.kmp.library` convention plugin in `build-logic/convention/`, applying the
  multiplatform plugin, a `jvm()` target, `jvmToolchain(17)`, ktlint, and the shared test wiring —
  so the pattern exists once rather than being re-declared per module.
- `:protocol` and `:core:model` converted to apply it; their `dependencies { }` blocks moved into
  `kotlin { sourceSets { commonMain / commonTest } }`.
- Source directory layout: `src/main/kotlin` → `src/commonMain/kotlin`, and `src/test/kotlin` →
  **`src/jvmTest/kotlin`**, not `commonTest`. Moves only, no content edits.

  `:protocol`'s suite is written against `org.junit.jupiter`, which is JVM-only — putting it in
  `commonTest` would mean rewriting every assertion onto `kotlin.test` in the same change that ports
  the build, which this story forbids for the reason stated below. `commonTest` becomes worth having
  when a second target does.
- Whatever consumer-side build changes turn out to be required for `:bridge`, `:app` and the Android
  library modules to keep resolving both modules.
- `docs/build-environment.md` updated if the JVM-only container path changes.

**Out of scope**
- Any second target (`androidTarget`, `iosX64`, `js`, …). One `jvm()` target only. The value of this
  story is the plumbing, and a target nothing builds for is unfalsifiable.
- Any change to Kotlin source in either module — including formatting. A diff that mixes a build
  conversion with source edits cannot be reviewed for either.
- Any other module. `:core:network`, `:core:decks` and `:core:cards` are stories 0082–0084 and each
  has real Android code to remove first.
- DI. Hilt is untouched here; it is story 0081.

## 4. Design & approach

**One convention plugin, written once, applied twice.** `build-logic/convention/` already holds
`AndroidApplicationConventionPlugin`, `AndroidLibraryConventionPlugin`, `AndroidComposeConventionPlugin`
and `HiltConventionPlugin`. `magefree.kmp.library` joins them and is what stories 0082–0084 apply as
they arrive, so the KMP configuration never drifts between modules — the same structural guarantee
`AGENTS.md` already requires for Android modules.

**Convert `:core:model` first, then `:protocol`.** `:core:model` has no dependencies and no external
consumer beyond the app modules, so it isolates the pure build question. `:protocol` adds the two
complications worth separating from it: a `kotlinx-serialization` plugin that must apply to the
common source set, and `:bridge` as a JVM consumer.

**The test tasks must keep running, and be seen to.** A converted module whose tests silently stop
being executed looks exactly like a converted module whose tests pass. `:protocol` has **28 tests**
(`GameSerializationTest` 18, `SerializationTest` 10), so the check is concrete: the same count runs
before and after, and a deliberately failing assertion fails the build.

**`:core:model` has no test source set at all** — no `src/test`, and `:core:model:test` reports
`NO-SOURCE`. So there is no test guard for that module and it is dishonest to imply one: its
conversion is verified entirely by its consumers compiling against it (`:core:network`,
`:feature:connect`, `:feature:lobby`, `:feature:tables`, and `:feature:game` on the test classpath).
That is a real check, but it is a different one, and worth naming rather than glossing.

## 5. Verification

- **Standard 1 (prove it fails first)** does not apply in its usual form — there is no behavioural
  defect here. Its equivalent: before converting, record `./gradlew :protocol:test :core:model:test`
  test counts; after converting, confirm the same tests run, then temporarily break one assertion
  and confirm the build fails. A test task that has stopped running is this story's failure mode.
- **Standard 2 (reachability)** applies to the build graph rather than to UI state: for each
  consumer of these modules, name what resolves it. `:bridge` (JVM), `:app` (Android application),
  and each Android library that depends on either — enumerated and each one built.
- **Hermetic gate:** `./gradlew check` clean, and `./gradlew :app:assembleDebug`.
- **The JVM-only container path:** `MAGE_JVM_ONLY=1` still configures and builds both modules, since
  they must remain buildable without the Android SDK.

  **`:bridge` cannot be built on the host at all**, and that is pre-existing rather than something
  this story introduces: `org.mage:mage-common:1.4.60` is baked into the build image's `/root/.m2`
  (story 0021) and is absent from the host, so `:bridge:compileKotlin` fails to resolve it on an
  unmodified `main` exactly as it does here — verified by stashing and re-running. `:bridge:check`
  therefore runs through `./scripts/dev gradle :bridge:check`, in the container.
- **No eyes-on checklist.** This story changes no runtime behaviour and ships no user-visible
  surface; `:app:assembleDebug` plus a launch is the whole device check. Say so rather than
  inventing a checklist — a checklist with nothing on it teaches everyone to skip the next one.

## 6. Acceptance criteria

- [ ] `magefree.kmp.library` exists in `build-logic/convention/` and is applied by both modules.
- [ ] `:protocol` and `:core:model` are KMP modules with a single `jvm()` target and no Kotlin
      source changes (verified by the diff being confined to build files and file moves).
- [ ] `./gradlew check` and `./gradlew :app:assembleDebug` pass.
- [ ] `./scripts/dev gradle :bridge:check` passes in the container, proving the JVM consumer
      compiles against the converted `:protocol` and not merely resolves it.
- [ ] `:protocol` runs the same **28** tests after the conversion as before, and a deliberately
      broken assertion fails the build.
- [ ] The APK launches.

## 7. References

- `build-logic/convention/src/main/kotlin/` — the four existing convention plugins this joins.
- `protocol/build.gradle.kts`, `core/model/build.gradle.kts` — the two files being converted.
- `settings.gradle.kts` — the `MAGE_JVM_ONLY` guard, and why `:core:model` sits outside it.
- `docs/ui-modernization-plan.md` §11 Phase 0 step 1, §9.2 — why this module pair is first.
- `AGENTS.md` § *Portability rules* — what the later stories are held to.
