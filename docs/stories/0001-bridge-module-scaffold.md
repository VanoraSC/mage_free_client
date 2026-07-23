# 0001 — Bridge module scaffold & health endpoint

- **Epic:** EPIC-01 — Bridge & Server Integration
- **Depends on:** none (first story)
- **Status:** ready

## 1. Objective

Stand up the monorepo's Gradle build and a runnable, empty **`:bridge`** service (Kotlin/JVM,
Ktor) with a single `/health` endpoint and a passing test/lint gate. This is pure
scaffolding: it establishes how we build, run, test, and lint the bridge, so every later
bridge story has a home. **No XMage code, networking to XMage, or protocol work happens
here.**

## 2. Context & background

- This repo is a **monorepo**: the JVM bridge and (later) the Android app share it. This
  story creates only the root Gradle build and the `:bridge` module. Android modules are
  added by their own epics — do **not** scaffold them here (see the "don't scaffold empty
  modules prematurely" rule in [`AGENTS.md`](../../AGENTS.md)).
- Stack defaults are fixed in [`AGENTS.md`](../../AGENTS.md): **Gradle Kotlin DSL + a version
  catalog** (`gradle/libs.versions.toml`), Kotlin (latest stable), Ktor for the bridge,
  kotlinx.serialization for JSON, JUnit5 + coroutines-test for tests, and **ktlint/detekt
  clean**. Pin all versions in the catalog — no hard-coded versions in build files.
- The bridge's real purpose (later stories) is in [`../architecture.md`](../architecture.md);
  you don't need it for this story beyond knowing the service is named the bridge.
- There is currently **no Gradle wrapper** in the repo; this story adds it.

## 3. Scope

**In scope**
- Root Gradle Kotlin DSL build with a version catalog and the Gradle wrapper (committed).
- A `:bridge` module: Kotlin/JVM application, Ktor server, `/health` route, structured
  logging, port from config/env.
- Unit/integration test of `/health` using Ktor's `testApplication`.
- ktlint (or detekt) wired so `./gradlew check` runs lint + tests.
- Brief run/build notes in the bridge module's own `README.md`.

**Out of scope**
- Any XMage / `Mage.Common` dependency or connection (stories 0002–0003).
- The bridge↔app WebSocket protocol (story 0004).
- Any Android module.
- Containerization/deployment.

## 4. Design & approach

Target layout after this story:

```
mage_free_client/
├── settings.gradle.kts         # includes :bridge
├── build.gradle.kts            # root: shared plugin/versions config (no app logic)
├── gradle/
│   └── libs.versions.toml      # version catalog (Kotlin, Ktor, kotlinx-serialization, logback, junit, ktlint)
├── gradlew / gradlew.bat / gradle/wrapper/…   # committed wrapper
└── bridge/
    ├── build.gradle.kts        # Kotlin/JVM application + Ktor + serialization + ktlint
    ├── README.md               # how to run/build the bridge
    └── src/
        ├── main/kotlin/magefree/bridge/
        │   ├── Application.kt   # Ktor entry (main + Application.module())
        │   └── routes/HealthRoutes.kt
        ├── main/resources/
        │   ├── application.conf # ktor { deployment { port = 8080, port = ${?BRIDGE_PORT} } }
        │   └── logback.xml
        └── test/kotlin/magefree/bridge/HealthRoutesTest.kt
```

Key decisions:
- **Package root:** `magefree.bridge`.
- **Ktor engine:** Netty (`ktor-server-netty`); `ContentNegotiation` + `kotlinx.serialization` JSON installed.
- **`/health`** returns HTTP 200 with `{"status":"ok","service":"mage-bridge"}` (a small
  `@Serializable` data class, not a raw string) so ContentNegotiation is exercised from day one.
- **Port:** default `8080`, overridable via `BRIDGE_PORT` env var through `application.conf`.
- **Logging:** logback via `ktor-server-call-logging`.
- **Application structure:** `fun main()` starts the Netty engine pointed at
  `Application.module()`; `module()` installs plugins and registers routes. This keeps
  `module()` testable with `testApplication`.

## 5. Implementation steps

1. Add the **Gradle wrapper** (latest stable Gradle) and commit `gradlew`, `gradlew.bat`,
   and `gradle/wrapper/`.
2. Create `gradle/libs.versions.toml` with pinned versions for: Kotlin, Ktor
   (server-core, server-netty, server-content-negotiation, serialization-kotlinx-json,
   server-call-logging, server-test-host), kotlinx-serialization-json, logback-classic,
   junit-jupiter, kotlinx-coroutines-test, and the ktlint (or detekt) Gradle plugin.
3. Create root `settings.gradle.kts` (`rootProject.name = "mage-free-client"`, `include(":bridge")`)
   and a minimal root `build.gradle.kts` that declares shared plugin versions via the catalog.
4. Create `bridge/build.gradle.kts`: apply Kotlin/JVM, `application`, kotlinx.serialization,
   and ktlint plugins; set `application.mainClass` to `magefree.bridge.ApplicationKt`; add
   the Ktor + serialization + logback + test dependencies from the catalog; use JUnit5
   (`tasks.test { useJUnitPlatform() }`).
5. Implement `Application.kt` (`main` + `Application.module()` installing ContentNegotiation
   and CallLogging) and `routes/HealthRoutes.kt` (`Route.healthRoutes()` with the `/health`
   GET).
6. Add `application.conf` (port + `${?BRIDGE_PORT}`) and `logback.xml`.
7. Write `HealthRoutesTest.kt` using `testApplication { … }`: GET `/health`, assert 200 and
   the JSON body.
8. Write `bridge/README.md` with the run/build/test commands.
9. Ensure `./gradlew ktlintCheck` (or `detekt`) and `./gradlew check` are clean.

## 6. Testing & verification

- **Unit/integration (Ktor):** `HealthRoutesTest` boots `module()` via `testApplication`,
  calls `GET /health`, asserts status `200 OK` and body
  `{"status":"ok","service":"mage-bridge"}`.
- **Lint:** ktlint/detekt reports no violations.
- **Manual smoke (optional):** `./gradlew :bridge:run`, then `curl http://localhost:8080/health`
  returns the JSON.

Commands:

```bash
./gradlew check          # lint + tests — the pre-"done" gate
./gradlew :bridge:run    # start the service on :8080 (Ctrl-C to stop)
```

## 7. Acceptance criteria

- [ ] Gradle wrapper is committed; `./gradlew --version` works with no local Gradle install.
- [ ] `./gradlew check` passes (tests + lint) from a clean checkout.
- [ ] `./gradlew :bridge:run` serves `GET /health` → `200` with
      `{"status":"ok","service":"mage-bridge"}`.
- [ ] `BRIDGE_PORT=9090 ./gradlew :bridge:run` serves health on `9090`.
- [ ] All dependency versions live in `gradle/libs.versions.toml`; none hard-coded in build files.
- [ ] No XMage/`Mage.Common` dependency is present; no Android module was added.
- [ ] `bridge/README.md` documents run/build/test.

## 8. References

- [`AGENTS.md`](../../AGENTS.md) — stack defaults, module rules, git workflow.
- [`../architecture.md`](../architecture.md) — what the bridge becomes (background only).
- [`README.md`](README.md) — story system, numbering, and the Epic 1 decomposition.
