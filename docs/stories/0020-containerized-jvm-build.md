# 0020 — Containerized JVM build (base image + compose + helper)

- **Epic:** Build Infrastructure (see [`../build-environment.md`](../build-environment.md))
- **Depends on:** none (first infra story)
- **Status:** ready

## 1. Objective

Stand up the **containerized JVM build path**: a base build image (JDK 17 + Maven), a Docker
Compose `build` service that mounts the repo and caches, and a thin `scripts/dev` wrapper — enough
to run the **`:bridge`** Gradle build inside a container. **Android `:app` stays on the host and is
untouched.** No upstream-mage layer and no XMage server yet (those are 0021 / 0022).

## 2. Context & background

- See [`../build-environment.md`](../build-environment.md) for the overall design and the
  containerize-only-the-heavy-path decision.
- The bridge is a Kotlin/JVM Ktor module built with the committed Gradle wrapper. It currently
  builds fine on the host; this story adds a **reproducible container path** for it (the foundation
  the mage/server layers build on).
- Prereq: **Docker Desktop with the WSL2 backend**; `docker` + `docker compose` on `PATH`.

## 3. Scope

**In scope**
- `docker/jvm/Dockerfile` — base image `maven:3.9-eclipse-temurin-17` (Maven + Temurin JDK 17,
  matching the locked toolchain) + `git`, `curl`, `unzip`. Runs as root (simplest for bind-mount
  writes). *(Note: `docker/jvm/`, not `docker/build/` — the repo's `*/build/` gitignore would
  swallow the latter.)*
- `docker/docker-compose.yml` — a `build` service using that image, with the repo bind-mounted at a
  working dir, named cache volumes for `~/.gradle` and `~/.m2`, and `MAGE_JVM_ONLY=1` so the
  SDK-less container skips the Android modules.
- A `settings.gradle.kts` guard (`if (System.getenv("MAGE_JVM_ONLY") != "1")`) that includes the
  Android modules only on the host; and `gradlew` normalized to LF (`.gitattributes`) so it runs
  in the Linux container.
- `scripts/dev` — a bash wrapper: `./scripts/dev gradle <args>` runs
  `docker compose run --rm build ./gradlew <args>`; plus `up`/`down` passthroughs.
- Docs: a short `docker/README.md` (prereqs, usage) and the run commands.

**Out of scope**
- The upstream `../mage` Maven build layer (**0021**) and the reference XMage server (**0022**).
- Any Android containerization (host stays authoritative for `:app`).
- CI wiring (later; the images are designed to be CI-reusable).

## 4. Prerequisites & toolchain

Deltas from the [Project toolchain baseline](stories/README.md#project-toolchain-baseline):

- Docker Desktop (WSL2). The container pins **JDK 17** (matching the baseline); Gradle comes from
  the committed wrapper inside the container. No change to `gradle/libs.versions.toml` or the wrapper.
- The repo is bind-mounted from `/mnt/c/...` (WSL) — note possible I/O perf; not a blocker.

## 5. Design & approach

```
docker/
├── jvm/Dockerfile        # temurin:17-jdk + git/curl/unzip + maven; non-root user; WORKDIR /workspace
├── docker-compose.yml      # service: build (repo + gradle/m2 cache volumes)
└── README.md               # prereqs + usage
scripts/
└── dev                     # ./scripts/dev gradle <args> | up <svc> | down | mvn <args>
```

- **`build` service:** `image` from `docker/jvm/Dockerfile`; `volumes:` repo → `/workspace`,
  `gradle-cache` → `/home/build/.gradle`, `m2-cache` → `/home/build/.m2`; `working_dir: /workspace`.
  Run Gradle via the wrapper so the Gradle version is the repo's.
- **`scripts/dev`:** dispatch on the first arg — `gradle` → `docker compose run --rm build ./gradlew "$@"`,
  `mvn` → maven passthrough, `up`/`down` → compose lifecycle. Keep it a few lines and readable.
- Keep the base image lean; heavy layers (mage) are added in 0021 so day-to-day rebuilds stay fast.

## 6. Implementation steps

1. Write `docker/jvm/Dockerfile` (base + tools + non-root user + workdir).
2. Write `docker/docker-compose.yml` with the `build` service and cache volumes.
3. Write `scripts/dev` (executable) and `docker/README.md`.
4. Verify: `./scripts/dev gradle :bridge:check` builds the bridge **in-container**, green, and the
   Gradle cache volume persists between runs (second run is faster / offline).
5. Confirm the host Android path is unaffected (`:app` still builds on the host as before).

## 7. Testing & verification

- **In-container bridge build:** `./scripts/dev gradle :bridge:check` → BUILD SUCCESSFUL.
- **Cache persistence:** a second `./scripts/dev gradle :bridge:check` reuses the mounted Gradle
  cache (no re-download).
- **Host untouched:** `:app` host build still works (sanity check, unchanged).

```bash
./scripts/dev gradle :bridge:check
```

## 8. Acceptance criteria

- [ ] `docker/jvm/Dockerfile`, `docker/docker-compose.yml`, and `scripts/dev` exist; `scripts/dev`
      is documented in `docker/README.md`.
- [ ] `./scripts/dev gradle :bridge:check` builds `:bridge` inside the container (JDK 17), green.
- [ ] The `~/.gradle` cache persists across runs via a named volume.
- [ ] No upstream-mage layer and no XMage server were added; Android `:app` remains a host build,
      unchanged; no toolchain/catalog version changes.

## 9. References

- [`../build-environment.md`](../build-environment.md) — the overall design + decisions.
- [`0001-bridge-module-scaffold.md`](0001-bridge-module-scaffold.md) — the `:bridge` module this containerizes the build of.
