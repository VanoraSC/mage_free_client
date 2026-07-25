# 0022 — Reference XMage server container

- **Epic:** Build Infrastructure (see [`../build-environment.md`](../build-environment.md))
- **Depends on:** 0021
- **Status:** ready

## 1. Objective

Provide a **runnable reference XMage server as a container** — a `Mage.Server` image plus an
`xmage-server` Compose service on port 17171 with authentication disabled — and a reachability
check from the build container. This **realizes the objective of story 0002** (a repeatable local
reference environment) via containers, and is the server the bridge's integration tests (0005/0006)
connect to.

## 2. Context & background

- Story 0002 called for a "documented, repeatable local `Mage.Server` + an integration-test helper
  that reaches it." This story delivers that as a **container**, superseding 0002's host-based
  approach (see [`../build-environment.md`](../build-environment.md) and the note at the top of
  [`0002-local-xmage-server-harness.md`](0002-local-xmage-server-harness.md)).
- The server is `mage.server.Main`, default port **17171**, config `Mage.Server/config/config.xml`
  with `authenticationActivated` — set **`false`** locally so tests sign in with any username.
- Builds on 0021's upstream mage build (the server jars come from the same reactor).

## 3. Scope

**In scope**
- A server image (`docker/server/Dockerfile`, or a stage reusing 0021's build) that packages the
  built `Mage.Server` distribution and a **local `config.xml`** with `authenticationActivated="false"`,
  `serverAddress="0.0.0.0"`, `port="17171"`; entrypoint runs `mage.server.Main`.
- An **`xmage-server`** Compose service exposing `17171` on the compose network (and optionally to
  the host for manual use).
- A reachability check runnable from the `build` container against `xmage-server:17171`, and the
  `XMAGE_SERVER` convention (`host:port`) the bridge integration tests use.
- `scripts/dev` support: `./scripts/dev up xmage-server` / `down`.

**Out of scope**
- Any bridge code that connects via `SessionImpl` (that is bridge story 0003/0005) — this story only
  stands the server up and proves it is reachable.
- Multi-server / tournament / production hardening.

## 4. Prerequisites & toolchain

Deltas from the [Project toolchain baseline](stories/README.md#project-toolchain-baseline):

- Requires 0021 (the upstream build). JDK 17 runtime for the server (confirm the server *runs* on
  17 even if it targets older Java; document any flag needed). Port 17171 (and `secondaryBindPort`
  behavior) exposed on the compose network.

## 5. Design & approach

- **Server image:** from 0021's build, take the `Mage.Server` distribution (the assembly zip /
  `lib` + `plugins`), drop in the local `config.xml` (auth off, bind `0.0.0.0:17171`), and set the
  entrypoint to launch `mage.server.Main` with a sensible `-Xmx`.
- **Compose service `xmage-server`:** `ports: ["17171:17171"]` (host optional), on the shared
  network so the `build` service reaches it at `xmage-server:17171`.
- **Reachability:** a small check from the `build` container (a TCP connect to `xmage-server:17171`),
  and the documented env var `XMAGE_SERVER=xmage-server:17171` that bridge integration tests read.
- Startup is slow (loads the card DB) — document the readiness wait (poll the port / a log line)
  and a compose `healthcheck`.

## 6. Implementation steps

1. Create the server image (config with auth off; entrypoint `mage.server.Main`) from 0021's build.
2. Add the `xmage-server` Compose service (port 17171, network, healthcheck) + `scripts/dev up/down`.
3. Add a reachability check from the `build` container to `xmage-server:17171`; document `XMAGE_SERVER`.
4. Update the top of `0002-local-xmage-server-harness.md` to point here (0002 realized via container).
5. Document startup time + readiness in `docker/README.md` / `../build-environment.md`.

## 7. Testing & verification

- **Server runs & is reachable:** `./scripts/dev up xmage-server`, wait for ready, then a TCP
  connect to `xmage-server:17171` from the `build` container succeeds.
- **Auth is off:** config confirms `authenticationActivated="false"` (real login is exercised by the
  bridge stories, not here).

```bash
./scripts/dev up xmage-server
./scripts/dev gradle :bridge:check   # (bridge reachability test target lands with story 0002/0005)
```

## 8. Acceptance criteria

- [ ] An `xmage-server` Compose service runs `Mage.Server` on 17171 with `authenticationActivated="false"`,
      reachable from the `build` service at `xmage-server:17171`.
- [ ] A reachability check from the build container passes; `XMAGE_SERVER` (`host:port`) is documented
      as the integration-test target.
- [ ] Startup readiness (healthcheck / wait) is documented; the card-DB load time is noted.
- [ ] Story 0002 is updated to point here (its host-based harness is superseded by this container).
- [ ] No bridge `SessionImpl`/connect code is added; Android host path unchanged.

## 9. References

- [`0002-local-xmage-server-harness.md`](0002-local-xmage-server-harness.md) — the objective this realizes (now container-based).
- [`../architecture.md`](../architecture.md) — the server contract + why a live reference server is the correctness anchor.
- `../mage/Mage.Server/config/config.xml`, `.../mage/server/Main.java` — the config + entrypoint.
- [`../build-environment.md`](../build-environment.md) — the compose design.
