# Containerized build environment (heavy / JVM path)

The **bridge build**, the **upstream `magefree/mage` build**, and the **reference XMage server**
run in Docker containers. The Android `:app` build stays on the host. Full design:
[`../docs/build-environment.md`](../docs/build-environment.md).

## Prerequisites
- **Docker Desktop with the WSL2 backend** (`docker` + `docker compose` available in the shell).
- Run everything from the **repo root** via `./scripts/dev` (a thin wrapper over `docker compose`).

## Usage
```bash
# Build the JVM bridge in-container (JDK 17; Android modules are skipped via MAGE_JVM_ONLY).
./scripts/dev gradle :bridge:check

# Start / stop the reference XMage server (port 17171, authentication disabled).
./scripts/dev up xmage-server
./scripts/dev down

# A shell in the build container, or raw maven.
./scripts/dev sh
./scripts/dev mvn -version
```

Bridge integration tests reach the server over the compose network at **`XMAGE_SERVER=xmage-server:17171`**
(verified reachable from the build container). It is also published to the host on `localhost:17171`.

## Images
- **`mage-free-client/build`** — JDK 17 + Maven + `git`; a cached layer builds `magefree/mage` at a
  pinned commit and bakes `org.mage:mage-common:1.4.60` into `/root/.m2` (story 0021). Used for all
  JVM/bridge builds.
- **`mage-free-client/xmage-server`** — a multi-stage image that full-reactor-builds XMage, assembles
  the server distribution, and runs `mage.server.Main` on 17171 with `authenticationActivated=false`
  (story 0022). The Java-1.8-era server runs on JDK 17 with no extra flags.

## Notes
- **First builds are slow, then cached.** The `build` image's mage layer takes minutes; the
  `xmage-server` image does the full reactor + card database (~30–60 min). Both cache as image layers.
- **Server startup** loads the card database (~30–60 s) before it listens on 17171.
- Caches persist in the `gradle-cache` volume (Gradle) and the images' baked `/root/.m2` (mage-common).
- The repo is bind-mounted from `/mnt/c/...` under WSL2; large builds may see some I/O overhead.
