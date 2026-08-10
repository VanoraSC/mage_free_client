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
├── bridge/Dockerfile         # the runnable bridge image (story 0045)
└── docker-compose.yml        # services: build, xmage-server, bridge
scripts/
├── dev                       # thin wrapper: `./scripts/dev gradle :bridge:check`, `./scripts/dev up xmage-server`, ...
└── smoke-on-device.sh        # the on-device smoke: drives the installed APK against a live bridge (story 0048)
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
- **`bridge`** — the bridge itself, **running** (story 0045). Built by `docker/bridge/Dockerfile`: a
  build stage on the `build` image assembles `:bridge:installDist` (mage-common resolves from the
  image's baked `/root/.m2`), and a thin `eclipse-temurin:17-jre` runtime carries only the
  distribution. `depends_on: xmage-server`; its upstream is pinned with `XMAGE_UPSTREAM=xmage-server:17171`
  (the app never names an XMage host — see `architecture.md` decision #6), and its `/v1/session`
  WebSocket is published to the host on **`localhost:8080`**.

  The start script is launched with `JAVA_OPTS` carrying the JDK-17 `--add-opens` set the
  JBoss-serialization handshake needs — the same set the `xmage-server` entrypoint and the `:bridge`
  test task pass. **Keep all three in sync**; without them the upstream connect dies mid-handshake and
  every login looks like an auth failure.

  `depends_on` waits for the server *container*, not for it to finish loading the card database, so
  give `xmage-server` its ~1–2 min before driving a login through the bridge.

### The helper script (`scripts/dev`)
A thin bash wrapper over `docker compose` so nobody types raw compose commands:
- `./scripts/dev gradle <args>` → `docker compose run --rm build ./gradlew <args>`
- `./scripts/dev up xmage-server` / `./scripts/dev up bridge` / `./scripts/dev down`
- `./scripts/dev mvn <args>` (upstream/maven tasks)

### Driving the app's networking stack against the bridge (story 0045)
`:core:network` is an Android module, so its tests run **on the host** (this image has no Android SDK)
— and they need nothing but the bridge's URL, since the module speaks pure WebSocket + JSON and has no
`mage.*`/`:bridge` dependency. With both services up:

```bash
./scripts/dev up bridge                      # starts xmage-server + bridge; bridge lands on :8080
BRIDGE_URL=localhost:8080 ./gradlew :core:network:testDebugUnitTest --tests 'magefree.network.live.*'
```

`BRIDGE_URL` is the app-side mirror of `XMAGE_SERVER`: **unset, the live tests report *skipped***, so
`:core:network:check` stays hermetic and offline by default.

Clean-room agents and CI invoke `./scripts/dev …` for bridge work instead of host Gradle — so the
host JDK/`JAVA_HOME` facts are no longer needed for the bridge path.

## The on-device smoke (story 0048)

`scripts/smoke-on-device.sh` drives the **installed debug APK** on an emulator, against the **real
bridge and XMage server**, through the path a player actually walks — sign in → lobby → decks (with
the device offline) → host a table → match start → sign out — asserting on-screen content at every
step. It exists because everything above `:core:network` had never been exercised on a device: story
0047 found an entire epic's UI built, tested, merged and unreachable.

It is **not** part of `check` and never runs in CI: it needs a device and live servers. It is the
manual gate you run before believing an APK works. The hermetic half of story 0048 — the wiring guards
in `:app:testDebugUnitTest` — is what runs on every build.

### Prerequisites

1. A running emulator (or device) visible to `adb devices`. The script uses `uiautomator` dumps and
   `input` events, both stock.
2. `bridge` + `xmage-server` up (`./scripts/dev up bridge`), with the bridge published on the host's
   `localhost:8080`. **From inside the emulator the host is `10.0.2.2`**, which is the script's
   default — a physical device needs `--host <your-LAN-ip>`.
3. A built debug APK: `./gradlew :app:assembleDebug` (the script installs
   `app/build/outputs/apk/debug/app-debug.apk` unless `--apk` says otherwise).
4. `adb` on `PATH`, or `ADB=/path/to/adb`. Under Git Bash the script sets `MSYS_NO_PATHCONV=1` itself,
   without which every on-device path (`/sdcard/...`) is silently rewritten to a Windows path.

### Running it

```bash
./scripts/smoke-on-device.sh --serial emulator-5554 --out build/smoke
```

Useful flags: `--host` / `--port` (the bridge the app is pointed at), `--apk`, `--skip-install`
(reuse the installed app, `pm clear` its data), `--keep-app`.

Each run is **idempotent and cold**: it uninstalls first, and picks a fresh username and deck name, so
runs never collide with each other or with leftover server state. Usernames stay inside XMage's
`[a-z0-9_]{3,14}`.

### What a pass looks like

Every step prints `PASS:` lines naming what it checked, and drops a numbered screenshot into the
output directory. The run ends with:

```
SMOKE PASSED — all 5 steps, signed in as smoke1424233
evidence: build/smoke
```

and exit code 0. Evidence in `--out`: `smoke.log`, `NN-<step>.png` per step, and for anything that
failed, the screenshot plus the raw `uiautomator` XML of that moment.

### What a failure looks like

A failed expectation names **what was expected and everything that was actually on screen**, and the
run exits non-zero:

```
!! DEFECT 1 at step 3 (decks-offline): searching the catalog for 'Forest' returns matches
   expected: a screen matching /^Showing [1-9][0-9]*$/ within 12s
   actually on screen:
     · Search cards to add
     · Showing 0
     ...
```

Expectation failures **record and continue** where the rest of the run is still worth measuring — one
broken step must not hide every step behind it — and the summary lists all of them. Failures that make
continuing meaningless (no device, sign-in never completes, a screen that never appears) abort at once.

### Two device facts the script encodes

Both were learned the hard way during story 0047's verification, and both produce convincing false
failures if ignored:

- **Dismiss the soft keyboard before tapping a bottom-anchored button.** The sign-in layout does not
  resize for the IME, so a bottom button keeps reporting bounds that sit behind the keyboard and the
  tap lands on the IME — no UI change, no logs, no bridge contact. Dismiss with **BACK**, not ESCAPE:
  in Compose, ESCAPE closes the *dialog* and throws away the form you just filled in.
- **Locate controls by visible text, not content description.** Material3's `NavigationBarItem`
  publishes no `contentDescription` on its merged node. (uiautomator's tree *does* expose it for the
  icon-only lobby and deck actions, which have no text at all, so those are the one exception.) This is
  a testing-correctness concern, not an accessibility one.

Two more the script works around: `adb shell input text` drops characters, so every field it fills is
read back and retyped on a mismatch; and Compose relayouts move controls between actions, so every tap
re-reads the screen instead of reusing a coordinate.

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
