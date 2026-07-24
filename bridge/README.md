# :bridge

The **bridge** is a headless Kotlin/JVM ([Ktor](https://ktor.io/)) service. This module is the
scaffold: a runnable server with a single `/health` endpoint and a passing test + lint gate. It
does **not** talk to XMage yet — that arrives in later Epic 1 stories (see
[`../docs/stories/README.md`](../docs/stories/README.md)).

## Layout

```
bridge/
├── build.gradle.kts
└── src/
    ├── main/kotlin/magefree/bridge/
    │   ├── Application.kt          # main() + Application.module()
    │   └── routes/HealthRoutes.kt  # GET /health
    ├── main/resources/
    │   ├── application.conf        # port (default 8080, override with BRIDGE_PORT)
    │   └── logback.xml             # structured console logging
    └── test/kotlin/magefree/bridge/
        └── HealthRoutesTest.kt     # testApplication integration test
```

## Run

From the repo root (uses the committed Gradle wrapper — no local Gradle install needed):

```bash
./gradlew :bridge:run
```

The server listens on `http://localhost:8080`. Check the health endpoint:

```bash
curl http://localhost:8080/health
# {"status":"ok","service":"mage-bridge"}
```

### Port

The port defaults to `8080` and is overridable via the `BRIDGE_PORT` environment variable:

```bash
BRIDGE_PORT=9090 ./gradlew :bridge:run
# now serving on http://localhost:9090
```

## Build & test

```bash
./gradlew :bridge:build   # compile + test + lint for this module
./gradlew check           # lint + tests across the project (the pre-"done" gate)
./gradlew test            # unit/integration tests only
./gradlew ktlintCheck     # Kotlin lint only
./gradlew ktlintFormat    # auto-fix lint violations where possible
```

## Endpoints

| Method | Path      | Response                                        |
|--------|-----------|-------------------------------------------------|
| `GET`  | `/health` | `200` `{"status":"ok","service":"mage-bridge"}` |
