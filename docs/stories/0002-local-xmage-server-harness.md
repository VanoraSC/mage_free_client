# 0002 — Local XMage server harness (reference environment)

- **Epic:** EPIC-01 — Bridge & Server Integration
- **Depends on:** 0001
- **Status:** ready

## 1. Objective

Give the project a **repeatable way to build and run a real XMage server locally** and a
gated **integration-test hook** in `:bridge` that confirms the server is reachable. Every
later bridge story (0003+) verifies correctness against a live server; this story makes that
server easy and consistent to stand up, without adding any XMage code to the bridge yet.

## 2. Context & background

- The upstream engine is the sibling repo **`../mage`** (`magefree/mage`), a **Maven**
  reactor at `org.mage:mage-root:1.4.60`. It is reference/tooling only — never an Android or
  bridge compile dependency (that boundary is set in [`AGENTS.md`](../../AGENTS.md); the
  client library consumed later, in 0003, is `org.mage:mage-common`).
- **Server entry point:** `mage.server.Main` (`../mage/Mage.Server/src/main/java/mage/server/Main.java`).
- **Server config:** `../mage/Mage.Server/config/config.xml`. Relevant keys:
  - `serverAddress` (use `0.0.0.0` for local), `port` (default **`17171`**),
    `secondaryBindPort` (`-1` = arbitrary).
  - **`authenticationActivated`** — `"false"` means clients may sign on with any username
    **without registering**. Set it `false` locally so tests can log in freely.
- **Distribution:** `Mage.Server/pom.xml` uses the assembly plugin
  (`src/main/assembly/distribution.xml`, `finalName=mage-server`, `appendAssemblyId=false`)
  to build a **zip**: `Mage.Server/target/mage-server.zip`, containing `lib/`
  (`mage-server-1.4.60.jar` + deps), `plugins/`, and the `release/` launch scripts. The
  launch command is `java -Xmx1024m -jar ./lib/mage-server-1.4.60.jar` run from the unzipped
  dir (see `release/startServer.sh`), with `config/config.xml` present in that working dir.
- **First build is heavy.** The reactor builds the full card database (Mage.Sets); the first
  `mvn install` takes many minutes and produces an on-disk card DB. Subsequent runs are fast.
  Requires a **JDK 17+** and Maven.
- The transport is JBoss Remoting **bisocket** over Java serialization — this runs fine on a
  normal JVM (the bridge and these tests), just not on Android.

## 3. Scope

**In scope**
- A documented, parameterized procedure + thin wrapper script(s) to build and launch a local
  server with `authenticationActivated=false` on port 17171.
- A committed local config (or a documented override) that flips `authenticationActivated`
  off and binds locally.
- A `:bridge` test-support helper that resolves the target server from an env var, and an
  **env-gated** integration test that asserts TCP reachability. Skipped (not failed) when the
  env var is absent, so `./gradlew check` stays hermetic and fast.
- Notes on the heavy first build and the generated card DB.

**Out of scope**
- Adding `org.mage:mage-common` as a dependency or any `SessionImpl`/auth handshake — that is
  **0003** (this story only checks that the port is open).
- Any WebSocket/protocol work (0004+).
- Running a live XMage server inside CI (documented as a future option; not required here).

## 4. Design & approach

Target additions to the bridge repo:

```
scripts/xmage-server/
├── run-local-server.sh      # build (if needed) + launch a local server
├── run-local-server.bat     # Windows equivalent
├── config.local.xml         # config.xml with authenticationActivated="false", serverAddress="0.0.0.0", port="17171"
└── README.md                # exact build/run steps, JDK/Maven prereqs, first-build warning

bridge/src/test/kotlin/magefree/bridge/testsupport/
└── XMageServerTarget.kt     # reads XMAGE_SERVER (host:port), default "localhost:17171"

bridge/src/test/kotlin/magefree/bridge/
└── LocalServerReachabilityIT.kt   # env-gated TCP reachability test
```

Behavior:

- **`run-local-server.sh`** takes `MAGE_REPO` (env or arg, default `../mage`):
  1. If `Mage.Server/target/mage-server.zip` is missing, run `mvn -q -DskipTests clean install`
     in `$MAGE_REPO` (warn that the first run is slow).
  2. Unzip the distribution to a work dir (e.g. `$MAGE_REPO/Mage.Server/target/mage-server/`).
  3. Copy `scripts/xmage-server/config.local.xml` into the run dir as `config/config.xml`
     (source it from `$MAGE_REPO/Mage.Server/config/config.xml`, then apply the
     `authenticationActivated="false"` change — keep the local copy under version control so
     the override is explicit and repeatable).
  4. Launch `java -Xmx1024m -jar ./lib/mage-server-1.4.60.jar` from the run dir; the server
     listens on `0.0.0.0:17171`.
- **`XMageServerTarget`**: parses `System.getenv("XMAGE_SERVER")` as `host:port`, defaulting
  to `localhost:17171`; exposes `host`, `port`.
- **`LocalServerReachabilityIT`**: JUnit5, annotated
  `@EnabledIfEnvironmentVariable(named = "XMAGE_SERVER", matches = ".+")`. Opens a
  `java.net.Socket` to `host:port` with a short connect timeout and asserts it connects.
  Because it is env-gated, `./gradlew check` skips it unless the operator opts in.

Rationale: XMage's server is slow to boot (loads all cards), so per-test server startup is
avoided. Integration tests connect to an **already-running** local server, opted into via
`XMAGE_SERVER`. This keeps the default gate hermetic while giving a real target when wanted.

## 5. Implementation steps

1. Add `scripts/xmage-server/config.local.xml`: copy `../mage/Mage.Server/config/config.xml`
   and set `authenticationActivated="false"`, `serverAddress="0.0.0.0"`, `port="17171"`.
2. Write `run-local-server.sh` and `run-local-server.bat` implementing the build/unzip/launch
   flow above, parameterized by `MAGE_REPO` (default `../mage`). Confirm the actual zip path
   and version string against `$MAGE_REPO` (the parent pom version is `1.4.60`).
3. Write `scripts/xmage-server/README.md`: JDK 17+ & Maven prereqs, the first-build time/DB
   warning, how to run, and how to point tests at it (`export XMAGE_SERVER=localhost:17171`).
4. Add `XMageServerTarget.kt` test-support helper in `:bridge` test sources.
5. Add `LocalServerReachabilityIT.kt`, env-gated as above.
6. Confirm `./gradlew check` passes with the test **skipped** (no env var set).
7. Manually: start the server via the script, `export XMAGE_SERVER=localhost:17171`, run the
   test, confirm it passes; document the observed steps in the script README.

## 6. Testing & verification

- **Hermetic gate:** `./gradlew check` passes; `LocalServerReachabilityIT` reports *skipped*
  when `XMAGE_SERVER` is unset.
- **Live check (manual/opt-in):**
  ```bash
  ./scripts/xmage-server/run-local-server.sh      # in one terminal; wait for "Started" log
  XMAGE_SERVER=localhost:17171 ./gradlew :bridge:test --tests '*LocalServerReachabilityIT'
  ```
  The test connects to `localhost:17171` and passes.

## 7. Acceptance criteria

- [ ] `scripts/xmage-server/` contains the run scripts, `config.local.xml`
      (`authenticationActivated="false"`), and a README with prereqs and the first-build warning.
- [ ] Running the script builds (if needed) and launches a local XMage server listening on
      `17171`, reachable by a TCP client.
- [ ] `XMageServerTarget` resolves `XMAGE_SERVER` (`host:port`), defaulting to `localhost:17171`.
- [ ] `LocalServerReachabilityIT` passes against a running local server and is **skipped**
      when `XMAGE_SERVER` is unset.
- [ ] `./gradlew check` remains green and hermetic (no live server needed for the default gate).
- [ ] The bridge still has **no** `org.mage` compile dependency (that arrives in 0003).

## 8. References

- `../mage/Mage.Server/src/main/java/mage/server/Main.java` — server entry point.
- `../mage/Mage.Server/config/config.xml` — config keys (`port`, `authenticationActivated`).
- `../mage/Mage.Server/src/main/assembly/distribution.xml` and `release/startServer.sh` — how
  the server zip is built and launched.
- [`../architecture.md`](../architecture.md) — why a live local server is the correctness anchor.
- [`0001-bridge-module-scaffold.md`](0001-bridge-module-scaffold.md) — the module this test hook lives in.
