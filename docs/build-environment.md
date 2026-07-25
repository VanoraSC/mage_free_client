# Build Environment

How we build and run the project. Two paths, by design:

- **Android (`:app`) — on the host.** The Android build (`./gradlew :app:check` /
  `:app:assembleDebug`) runs on the developer's machine using the host JDK 17 + Android SDK. It
  works today and needs no container.
- **Bridge / JVM + upstream XMage — in containers.** The JVM bridge build, the heavy `../mage`
  Maven build, and the reference XMage server run in **Docker containers** driven by Docker
  Compose. This is where the environment is heavy, fiddly, and worth making reproducible.

This split is deliberate (see the decision log below): we containerize only the heavy path.

## Why containers here

- **The bridge's dependencies are heavyweight.** Story 0003 makes the bridge depend on
  `org.mage:mage-common`, which requires building the upstream `../mage` reactor with Maven
  (Java-1.8-era code, a large card database). Story 0002/0005/0006 need a **running XMage server**
  (JBoss Remoting on 17171). These are exactly the things a container declares once and reuses.
- **Reproducibility.** A declared image retires the fragile host facts we currently thread through
  every build (a `JAVA_HOME` that isn't on `PATH`, a hand-wired `mvn install`). Clean-room agents
  and CI then build identically.
- **Layered growth.** New capabilities are added as new image layers as later epics need them —
  the environment grows without re-deriving setup.

## Architecture

```
docker/
├── jvm/Dockerfile          # the JVM build image, built in layers
├── server/Dockerfile         # (or a stage) the runnable Mage.Server image
└── docker-compose.yml        # services: build, xmage-server
scripts/
└── dev                       # thin wrapper: `./scripts/dev gradle :bridge:check`, `./scripts/dev up xmage-server`, ...
```

### The build image (`docker/jvm/Dockerfile`), in layers
1. **Base** — `maven:3.9-eclipse-temurin-17` (Maven + Temurin JDK 17, matching the locked
   toolchain) + `git`, `curl`, `unzip`.
2. **Upstream mage layer** — clone `magefree/mage` at a **pinned commit** and
   `mvn -pl Mage.Common -am -DskipTests install`, baking `org.mage:mage-common` (+ `org.mage:mage`)
   into the image's local Maven repo (`/root/.m2`). Only Mage.Common + its reactor deps are built —
   the card database (`Mage.Sets`) is skipped. This is the heavy, cached layer (see story 0021).
3. *(future layers appended as epics need them.)*

The bridge's Gradle build runs via the committed wrapper inside this image; `~/.gradle` is a mounted
cache volume so downloads persist across runs. `/root/.m2` is **not** volume-mounted, so the baked
`mage-common` stays visible.

### Compose services (`docker/docker-compose.yml`)
- **`build`** — the build image; mounts the repo and the `~/.gradle` cache (mage-common is baked
  into the image's `/root/.m2`); used to run Gradle/Maven for `:bridge` (and any JVM module).
- **`xmage-server`** — runs `Mage.Server` on `17171` with `authenticationActivated="false"` (local
  config), on the compose network. The bridge's env-gated integration tests connect to it at
  `XMAGE_SERVER=xmage-server:17171` (see story 0022). **This realizes story 0002's "reference
  environment" as a container.**

### The helper script (`scripts/dev`)
A thin bash wrapper over `docker compose` so nobody types raw compose commands:
- `./scripts/dev gradle <args>` → `docker compose run --rm build ./gradlew <args>`
- `./scripts/dev up xmage-server` / `./scripts/dev down`
- `./scripts/dev mvn <args>` (upstream/maven tasks)

Clean-room agents and CI invoke `./scripts/dev …` for bridge work instead of host Gradle — so the
host JDK/`JAVA_HOME` facts are no longer needed for the bridge path.

## How this changes the workflow

- **Bridge stories (0002–0006)** build and verify **in-container** (`./scripts/dev gradle
  :bridge:check`; integration tests against the `xmage-server` service). Their earlier host-based
  instructions (a `scripts/xmage-server/run-local-server.sh`, `XMAGE_SERVER=localhost:17171`) are
  **superseded** by this container workflow and will be updated as those stories are implemented.
- **Android stories** are unchanged — host build, host JDK 17 + Android SDK.
- **CI (later)** uses the same images for reproducible bridge builds; the Android job stays a
  standard host/runner Android build.

## Prerequisites

- **Docker Desktop with the WSL2 backend** (the maintainer works in WSL2). `docker` + `docker
  compose` available in the shell.
- The repo lives on the Windows filesystem (`/mnt/c/...` under WSL). Bind-mounting works; note the
  possible I/O-perf hit vs a repo inside the WSL filesystem — flagged, not solved here.

## Unknowns the implementation will validate

- Whether the **`../mage` Maven build** builds cleanly on JDK 17 in-container (it targets an older
  Java; may need flags or a specific JDK). Story 0021 resolves this empirically.
- **Bind-mount performance** on `/mnt/c` under WSL2 for large Gradle/Maven builds.
- Image size of the baked mage layer (the card DB is large) — acceptable, but noted.

## Implementation order

Recommended build order right now (IDs are identifiers, not a strict sequence — the earlier
"global order" no longer holds since the app epics shipped ahead of the bridge):

1. **Build infrastructure — stories 0020 → 0021 → 0022** (this document).
2. **Bridge — stories 0002/0003/0004/0005/0006** (built in-container on top of 0020–0022).
3. **Epic 4 — stories 0016–0019** (the app connect flow, once the bridge runs).

## Decisions

1. **Scope → the heavy path only.** Containerize the JVM bridge build, the upstream mage build, and
   the reference XMage server. **Android `:app` stays on the host** (it works and instrumented tests
   need a device regardless).
2. **Driver → Docker Compose + a `scripts/dev` helper.** Headless-friendly for clean-room agents
   and CI; no VS Code devcontainer for now.
3. **Reference server → a container** (`xmage-server` compose service), which implements the
   objective of story 0002.
