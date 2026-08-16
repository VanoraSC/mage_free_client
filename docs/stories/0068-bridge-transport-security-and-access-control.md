# 0068 — Bridge TLS via nginx reverse proxy

- **Epic:** EPIC-01 — Bridge & Server Integration
- **Depends on:** 0001 (bridge scaffold), 0005 (session bridge connect/login)
- **Status:** ready — **scope resolved (Pete, 2026-08-15):** TLS termination via an **nginx reverse
  proxy**, added to `docker/docker-compose.yml`. Bridge-level access control is **explicitly out of
  scope** — accepted risk, not a gap to close here (see §3).

## 1. Objective

Let the app reach the bridge over `wss://` — required the moment the bridge isn't sitting on the exact
LAN the phone is also on (§0's setup in `docs/verification-test-plan.md` only works because both are
local to each other and the debug build allows cleartext). Terminate TLS at an **nginx** reverse proxy
in front of the bridge, which keeps speaking plain `ws://` behind it — the standard shape for a
containerized service, and it keeps certificate handling out of the bridge's own JVM process/config.

**Access control is deliberately not this story's problem.** Traced in the original investigation: the
bridge has no bridge-level credential today, separate from whatever XMage username/password gets
relayed to the pinned upstream server. Pete's call: *"I'm not super worried about access control. I
just need to build what the app needs."* The app needs `wss://` to reach a bridge that isn't on its own
LAN — it doesn't need a second credential layer to do that. Recorded as an accepted risk (§3), not
silently dropped.

## 2. Context & background — traced from the actual code, not guessed

- **No TLS connector exists.** `bridge/src/main/kotlin/magefree/bridge/Application.kt`:
  `embeddedServer(Netty, port = port, host = "0.0.0.0") { module() }` — a plain HTTP/WS Netty engine,
  no `sslConnector`, no certificate configuration anywhere in the module. This story does **not** add
  one to the bridge itself — TLS is nginx's job, not Ktor's, under the chosen shape.
- **The bind address stays as-is, and that's fine now.** `docs/review-follow-ups.md`'s original note
  ("Bridge binds to a hardcoded `0.0.0.0`") worried about the bridge's own port being the thing exposed
  to the network directly. Once nginx is the only container publishing a port to the host, the bridge's
  `0.0.0.0` bind only matters **inside the compose network**, where it's correct and necessary (nginx
  has to reach it). The actual fix is topological, not a code change in `Application.kt`: stop
  publishing the bridge's port straight to the host once nginx fronts it (§3).
- **No bridge-level authentication** — confirmed by grep, still true, and **staying that way for this
  story**. The only credential in the system remains the XMage username/password relayed to the pinned
  upstream (`docs/architecture.md` decision #6). Accepted as of this story (§3), not fixed here.
- **The app side is unaffected and already correct.** `app/src/debug/AndroidManifest.xml` scopes
  `usesCleartextTraffic` to debug only; a `wss://` bridge needs no cleartext exception at all, debug or
  release. Nothing here changes that file.

## 3. Scope

**In scope**
- **An `nginx` service in `docker/docker-compose.yml`**, sitting in front of `bridge`, terminating TLS
  and proxying WebSocket upgrade requests through to `bridge:8080` over the internal compose network.
  Needs `proxy_http_version 1.1` plus the `Upgrade`/`Connection` header pass-through nginx requires for
  WebSocket proxying — a plain HTTP reverse-proxy config silently breaks the upgrade handshake.
- **A certificate for local/LAN use.** A self-signed cert is the realistic default for a dev/LAN
  bridge — which means the **client must be told to trust it**, since Android (and any TLS client) will
  otherwise reject an untrusted cert exactly the way it currently rejects cleartext. Two ways to handle
  that, and this story should pick one and document it:
  1. A **debug-only trust anchor** for the self-signed cert, mirroring how `usesCleartextTraffic` is
     already debug-scoped — a debug-build `network_security_config.xml` trusting a specific
     dev/test certificate (or user-added CAs), so a debug install can validate it without weakening
     release.
  2. **Document the manual trust step** (install the cert on the test device) and treat it as a
     one-time setup cost, no app change required.
- **Stop publishing the bridge's port directly to the host.** Once nginx is the entry point, `bridge`'s
  `ports: ["8080:8080"]` mapping in `docker-compose.yml` should no longer go straight to the host —
  only nginx's TLS port does. This is the practical resolution of the original bind-address concern
  (§2), achieved without touching `Application.kt`.
- Updating `docker/README.md`/`docs/verification-test-plan.md`'s setup instructions: the app now points
  at nginx's port with **secure connection (wss) on**, not the bridge's port with it off.

**Out of scope — deliberately, accepted risk**
- **Bridge-level access control.** No credential beyond the XMage login relay exists or is added here.
  Anyone who can reach the published TLS port can still attempt a session. Acceptable because the
  bridge is not intended, today, to be a publicly reachable multi-tenant service — it's built for the
  operator's own use. **Revisit if that stops being true** — e.g. if the bridge is ever exposed beyond
  a network the operator controls.
- **A real (non-self-signed) certificate / ACME automation** (Let's Encrypt, etc.) — worth doing for
  anything beyond LAN/dev use, but a separate concern from getting `wss://` working at all.
- Anything about the **app's** cleartext handling (`usesCleartextTraffic`, story 0047) — unaffected.
- Rate limiting, DoS protection, or any hardening beyond "TLS instead of cleartext."

## 4. Design & approach

- **nginx config lives alongside the existing compose setup**, not as a separate deployment story — add
  it to `docker/docker-compose.yml` and a new `docker/nginx/` (or similar) directory for its config,
  matching how `docker/bridge/Dockerfile` and `docker/server/Dockerfile` are already organized.
- **Don't let this block plain local dev.** Whether the existing direct `ws://localhost:8080` path
  (no nginx, no cert trust needed) stays available for the fastest local loop, or whether nginx becomes
  mandatory even for `localhost`, is worth deciding explicitly rather than defaulting — the simplest
  option is keeping both: nginx for anything over the LAN (real-phone testing), direct `ws://` still
  fine for `localhost`-only work (emulator, JVM integration tests) where cleartext was never the
  problem in the first place.
- **The WebSocket upgrade headers are the one place this is easy to get subtly wrong** — confirm live,
  don't assume a generic reverse-proxy nginx config handles it; the standard gotcha is a config that
  proxies plain HTTP fine but silently 502s or hangs the WebSocket upgrade.

## 5. Verification

- **Live, from a real phone off the bridge's own LAN segment** (or at minimum through nginx rather than
  directly to the bridge's port): confirm a `wss://` connection through nginx completes the full
  connect → sign-in flow, not just a TCP/TLS handshake.
- **Confirm the direct bridge port is no longer reachable from outside the compose network** once its
  host publish is removed — the whole point of the topology change.
- **Confirm plain local dev still works** (whichever of §4's two options was chosen) — don't regress
  the fast local loop while fixing the LAN case.

## 6. Acceptance criteria

- [ ] An `nginx` service terminates TLS and correctly proxies the WebSocket upgrade to `bridge:8080`.
- [ ] A cert-trust approach is chosen and documented (debug trust anchor, or a manual one-time device
      trust step) — not left for whoever runs it next to rediscover.
- [ ] The bridge's port is no longer published directly to the host; only nginx's TLS port is.
- [ ] `docs/verification-test-plan.md`'s real-phone setup instructions are updated to the new
      host/port/secure-toggle values.
- [ ] Live-verified: a real phone, off the bridge's own machine, connects and signs in over `wss://`
      through nginx.
- [ ] Bridge-level access control is explicitly noted as out of scope/accepted risk wherever this
      story is referenced, not silently absent.

## 7. References

- `docs/review-follow-ups.md` — "Bridge binds to a hardcoded `0.0.0.0`," the original note this story
  formalizes (now resolved topologically rather than in `Application.kt`).
- `docker/docker-compose.yml`, `docker/README.md` — the compose setup this story extends.
- `bridge/src/main/kotlin/magefree/bridge/Application.kt` — confirmed no TLS connector; unchanged by
  this story.
- `app/src/debug/AndroidManifest.xml` — the app-side cleartext decision, unaffected.
- `docs/architecture.md` — decision #6, the pinned-server posture; unaffected — this story is transport
  security, not upstream credential handling.
- `docs/verification-test-plan.md` — the real-phone/LAN setup instructions this story updates.
